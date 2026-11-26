package controllima.providers.docker

import controllima.shared.Container
import controllima.shared.ContainerDetail
import controllima.shared.ContainerPort
import controllima.shared.DockerDisk
import controllima.shared.Image
import controllima.shared.ImageDetail
import controllima.shared.ImageLayer
import controllima.shared.LogLine
import controllima.shared.Mount
import controllima.shared.Names
import controllima.shared.NetworkInfo
import controllima.shared.PruneResult
import controllima.shared.StatsSample
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DockerException(message: String) : RuntimeException(message)

/** Raw counters of one `/containers/{id}/stats?one-shot=true` call; CPU % needs two of them. */
data class DockerStatsRaw(
    val cpuTotal: Long,
    val systemCpu: Long,
    val onlineCpus: Int,
    val memUsage: Long?,
    val memLimit: Long?,
    val memCache: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val blkRead: Long,
    val blkWrite: Long,
)

/** Containers and images of one Docker daemon (Colima profile). */
interface DockerProvider {
    fun containers(): List<Container>
    fun images(): List<Image>

    fun inspect(id: String): ContainerDetail = unsupported()
    fun start(id: String): Unit = unsupported()
    fun stop(id: String): Unit = unsupported()
    fun restart(id: String): Unit = unsupported()
    fun rename(id: String, newName: String): Unit = unsupported()
    fun remove(id: String, force: Boolean = false): Unit = unsupported()
    fun stats(id: String): DockerStatsRaw = unsupported()
    fun image(id: String): ImageDetail = unsupported()
    fun deleteImage(id: String): Unit = unsupported()
    fun pruneImages(dryRun: Boolean): PruneResult = unsupported()
    fun pull(ref: String, onLine: (String) -> Unit): Unit = unsupported()
    fun systemDf(profile: String): DockerDisk = unsupported()
    fun networks(): List<NetworkInfo> = emptyList()
    fun pruneContainers(dryRun: Boolean): PruneResult = unsupported()
    fun pruneBuildCache(): Long = unsupported()
    fun logs(id: String, tail: Int, follow: Boolean, sinceSeconds: Long?, onEnd: () -> Unit = {}, onLine: (LogLine) -> Unit): Closeable = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not supported by this DockerProvider")
}

class DockerEngineProvider(
    private val transport: DockerTransport,
    private val profile: String = "default",
) : DockerProvider {
    // Unversioned paths: the daemon picks its own API version, and Docker 29 rejects versions below 1.44.
    override fun containers(): List<Container> = DockerJson.parseContainers(transport.get("/containers/json?all=true"), profile)

    override fun images(): List<Image> {
        val containers = runCatching { containers() }.getOrDefault(emptyList())
        return DockerJson.parseImages(transport.get("/images/json"), profile).map { image ->
            val users = containers.filter { c -> c.imageId == image.id || c.image == image.id || c.image in image.tags }.map { it.name }
            image.copy(usedBy = users)
        }
    }

    override fun inspect(id: String): ContainerDetail =
        DockerJson.parseInspect(transport.get("/containers/${ref(id)}/json"), profile)

    override fun start(id: String) = action("POST", "/containers/${ref(id)}/start")
    override fun stop(id: String) = action("POST", "/containers/${ref(id)}/stop")
    override fun restart(id: String) = action("POST", "/containers/${ref(id)}/restart")

    override fun rename(id: String, newName: String) {
        if (!Names.isValidContainerName(newName)) throw DockerException("Invalid container name: $newName")
        action("POST", "/containers/${ref(id)}/rename?name=${enc(newName)}")
    }

    override fun remove(id: String, force: Boolean) = action("DELETE", "/containers/${ref(id)}?force=$force")

    override fun stats(id: String): DockerStatsRaw = DockerJson.parseStats(transport.get("/containers/${ref(id)}/stats?stream=false&one-shot=true"))

    override fun image(id: String): ImageDetail {
        val base = images().firstOrNull { it.id == id || it.id.removePrefix("sha256:").startsWith(id.removePrefix("sha256:")) }
            ?: throw DockerException("Image not found: $id")
        return DockerJson.parseImageDetail(base, transport.get("/images/${enc(base.id)}/json"), transport.get("/images/${enc(base.id)}/history"))
    }

    override fun deleteImage(id: String) {
        val image = images().firstOrNull { it.id == id } ?: throw DockerException("Image not found: $id")
        if (image.usedBy.isNotEmpty()) throw DockerException("Image is used by ${image.usedBy.joinToString()}")
        action("DELETE", "/images/${enc(id)}")
    }

    override fun pruneImages(dryRun: Boolean): PruneResult {
        val dangling = images().filter { it.dangling && it.usedBy.isEmpty() }
        if (dryRun) return PruneResult(true, dangling.map { it.id }, dangling.sumOf { it.sizeBytes })
        val response = transport.request("POST", "/images/prune?filters=${enc("""{"dangling":["true"]}""")}", timeout = 2.minutes)
        if (!response.ok) throw DockerException(HttpResponse.message(response, "/images/prune"))
        return DockerJson.parsePrune(response.body, "ImagesDeleted")
    }

    override fun pruneContainers(dryRun: Boolean): PruneResult {
        val stopped = containers().filter { it.state == "exited" || it.state == "created" || it.state == "dead" }
        if (dryRun) return PruneResult(true, stopped.map { it.name }, 0)
        val response = transport.request("POST", "/containers/prune", timeout = 2.minutes)
        if (!response.ok) throw DockerException(HttpResponse.message(response, "/containers/prune"))
        return DockerJson.parsePrune(response.body, "ContainersDeleted")
    }

    override fun pruneBuildCache(): Long {
        val response = transport.request("POST", "/build/prune", timeout = 2.minutes)
        if (!response.ok) throw DockerException(HttpResponse.message(response, "/build/prune"))
        return DockerJson.parsePrune(response.body, "CachesDeleted").bytes
    }

    override fun pull(ref: String, onLine: (String) -> Unit) {
        if (!IMAGE_REF.matches(ref)) throw DockerException("Invalid image reference: $ref")
        val (image, tag) = splitRef(ref)
        transport.stream("POST", "/images/create?fromImage=${enc(image)}&tag=${enc(tag)}").use { stream ->
            if (stream.status !in 200..299) throw DockerException("Pull of $ref returned ${stream.status}")
            stream.input.bufferedReader().lineSequence().forEach { line ->
                val progress = DockerJson.parsePullLine(line)
                progress.error?.let { throw DockerException("Pull of $ref failed: $it") }
                progress.text?.let(onLine)
            }
        }
    }

    override fun systemDf(profile: String): DockerDisk = DockerJson.parseSystemDf(transport.request("GET", "/system/df", timeout = 30.seconds).also {
        if (!it.ok) throw DockerException(HttpResponse.message(it, "/system/df"))
    }.body, profile)

    override fun networks(): List<NetworkInfo> = DockerJson.parseNetworks(transport.get("/networks"))

    override fun logs(id: String, tail: Int, follow: Boolean, sinceSeconds: Long?, onEnd: () -> Unit, onLine: (LogLine) -> Unit): Closeable {
        val tty = runCatching { inspectTty(id) }.getOrDefault(false)
        val path = "/containers/${ref(id)}/logs?stdout=1&stderr=1&timestamps=1&tail=${tail.coerceIn(0, 50_000)}&follow=$follow" +
            (sinceSeconds?.let { "&since=$it" } ?: "")
        val stream = transport.stream("GET", path)
        if (stream.status !in 200..299) {
            stream.close()
            throw DockerException("Logs of $id returned ${stream.status}")
        }
        val reader = Thread({
            runCatching { DockerLogDecoder.decode(stream.input, tty, onLine) }
            runCatching { stream.close() }
            onEnd()
        }, "docker-logs").apply { isDaemon = true; start() }
        return Closeable { runCatching { stream.close() }; reader.interrupt() }
    }

    private fun inspectTty(id: String): Boolean = DockerJson.parseTty(transport.get("/containers/${ref(id)}/json"))

    private fun action(method: String, path: String) {
        val response = transport.request(method, path, timeout = 30.seconds)
        if (!response.ok && response.status != 304) throw DockerException(HttpResponse.message(response, path))
    }

    private fun ref(id: String): String {
        if (!Names.isValidContainerRef(id)) throw DockerException("Invalid container reference: $id")
        return id
    }

    companion object {
        private val IMAGE_REF = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/:@-]{0,254}$")

        fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        /** `repo:tag` → (repo, tag); digests and registry ports are kept intact. */
        fun splitRef(ref: String): Pair<String, String> {
            if (ref.contains('@')) return ref to ""
            val lastColon = ref.lastIndexOf(':')
            return if (lastColon > ref.lastIndexOf('/')) ref.substring(0, lastColon) to ref.substring(lastColon + 1) else ref to "latest"
        }

        /** CPU %, memory and rates from two consecutive raw samples (Docker's own CPU formula). */
        fun sample(targetId: String, prev: DockerStatsRaw?, cur: DockerStatsRaw, elapsedMillis: Long, nowMillis: Long): StatsSample {
            val seconds = elapsedMillis / 1000.0
            fun rate(a: Long?, b: Long): Double? = if (a == null || seconds <= 0 || b < a) null else (b - a) / seconds
            val cpu = prev?.let {
                val cpuDelta = cur.cpuTotal - it.cpuTotal
                val sysDelta = cur.systemCpu - it.systemCpu
                if (cpuDelta >= 0 && sysDelta > 0) cpuDelta.toDouble() / sysDelta * cur.onlineCpus * 100.0 else null
            }
            return StatsSample(
                targetId = targetId,
                epochMillis = nowMillis,
                cpuPercent = cpu,
                memoryUsedBytes = cur.memUsage?.let { (it - cur.memCache).coerceAtLeast(0) },
                memoryTotalBytes = cur.memLimit,
                netRxBytesPerSec = rate(prev?.rxBytes, cur.rxBytes),
                netTxBytesPerSec = rate(prev?.txBytes, cur.txBytes),
                diskReadBytesPerSec = rate(prev?.blkRead, cur.blkRead),
                diskWriteBytesPerSec = rate(prev?.blkWrite, cur.blkWrite),
            )
        }
    }
}

/** Container logs are multiplexed in 8-byte framed records unless the container has a TTY. */
object DockerLogDecoder {
    fun decode(input: InputStream, tty: Boolean, onLine: (LogLine) -> Unit) {
        if (tty) {
            input.bufferedReader().lineSequence().forEach { onLine(parse(it, "stdout")) }
            return
        }
        val data = DataInputStream(input)
        val header = ByteArray(8)
        val pending = mutableMapOf<String, StringBuilder>()
        while (true) {
            try {
                data.readFully(header)
            } catch (_: EOFException) {
                break
            }
            val stream = when (header[0].toInt()) { 2 -> "stderr"; 0 -> "stdin"; else -> "stdout" }
            val size = ((header[4].toInt() and 0xff) shl 24) or ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
            val payload = ByteArray(size)
            data.readFully(payload)
            val buffer = pending.getOrPut(stream) { StringBuilder() }
            buffer.append(String(payload, Charsets.UTF_8))
            while (true) {
                val nl = buffer.indexOf("\n")
                if (nl < 0) break
                onLine(parse(buffer.substring(0, nl), stream))
                buffer.delete(0, nl + 1)
            }
        }
        pending.forEach { (stream, rest) -> if (rest.isNotEmpty()) onLine(parse(rest.toString(), stream)) }
    }

    /** Splits the RFC 3339 timestamp Docker prepends with `timestamps=1`. */
    fun parse(raw: String, stream: String): LogLine {
        val space = raw.indexOf(' ')
        val ts = if (space > 0) runCatching { java.time.Instant.parse(raw.substring(0, space)).toEpochMilli() }.getOrNull() else null
        return LogLine(ts, stream, if (ts != null) raw.substring(space + 1) else raw)
    }
}

/** Parsers for Engine API responses. Unknown fields are ignored. */
object DockerJson {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class PortDto(
        @SerialName("PrivatePort") val privatePort: Int,
        @SerialName("PublicPort") val publicPort: Int? = null,
        @SerialName("IP") val ip: String? = null,
        @SerialName("Type") val type: String = "tcp",
    )

    @Serializable
    private data class EndpointDto(@SerialName("IPAddress") val ipAddress: String? = null)

    @Serializable
    private data class NetworksDto(@SerialName("Networks") val networks: Map<String, EndpointDto> = emptyMap())

    @Serializable
    private data class ContainerDto(
        @SerialName("Id") val id: String,
        @SerialName("Names") val names: List<String> = emptyList(),
        @SerialName("Image") val image: String = "",
        @SerialName("ImageID") val imageId: String? = null,
        @SerialName("State") val state: String = "",
        @SerialName("Status") val status: String = "",
        @SerialName("Created") val created: Long? = null,
        @SerialName("Ports") val ports: List<PortDto> = emptyList(),
        @SerialName("Labels") val labels: Map<String, String>? = null,
        @SerialName("NetworkSettings") val networkSettings: NetworksDto? = null,
    )

    @Serializable
    private data class ImageDto(
        @SerialName("Id") val id: String,
        @SerialName("RepoTags") val repoTags: List<String>? = null,
        @SerialName("Size") val size: Long = 0,
        @SerialName("Created") val created: Long? = null,
        @SerialName("Containers") val containers: Int? = null,
    )

    fun parseContainers(body: String, profile: String = "default"): List<Container> =
        json.decodeFromString(ListSerializer(ContainerDto.serializer()), body).map { c ->
            Container(
                id = c.id,
                name = c.names.firstOrNull()?.removePrefix("/") ?: c.id.take(12),
                image = c.image,
                state = c.state,
                status = c.status,
                ports = c.ports.map { ContainerPort(it.privatePort, it.publicPort, it.ip, it.type) }.distinctBy { Triple(it.privatePort, it.publicPort, it.type) },
                ipAddresses = c.networkSettings?.networks?.values?.mapNotNull { it.ipAddress?.takeIf(String::isNotBlank) }.orEmpty(),
                createdEpochSeconds = c.created,
                profile = profile,
                labels = c.labels.orEmpty(),
                imageId = c.imageId,
            )
        }

    fun parseImages(body: String, profile: String = "default"): List<Image> =
        json.decodeFromString(ListSerializer(ImageDto.serializer()), body).map { i ->
            Image(
                id = i.id,
                tags = i.repoTags.orEmpty().filter { it != "<none>:<none>" },
                sizeBytes = i.size,
                createdEpochSeconds = i.created,
                containers = i.containers?.takeIf { it >= 0 },
                profile = profile,
            )
        }

    @Serializable
    private data class InspectDto(
        @SerialName("Id") val id: String,
        @SerialName("Name") val name: String = "",
        @SerialName("Created") val created: String? = null,
        @SerialName("RestartCount") val restartCount: Int = 0,
        @SerialName("State") val state: StateDto = StateDto(),
        @SerialName("Config") val config: ConfigDto = ConfigDto(),
        @SerialName("HostConfig") val hostConfig: HostConfigDto = HostConfigDto(),
        @SerialName("Mounts") val mounts: List<MountDto> = emptyList(),
        @SerialName("NetworkSettings") val networkSettings: InspectNetworksDto = InspectNetworksDto(),
    )

    @Serializable
    private data class StateDto(
        @SerialName("Status") val status: String = "",
        @SerialName("ExitCode") val exitCode: Int? = null,
        @SerialName("StartedAt") val startedAt: String? = null,
    )

    @Serializable
    private data class ConfigDto(
        @SerialName("Image") val image: String = "",
        @SerialName("Cmd") val cmd: List<String>? = null,
        @SerialName("Entrypoint") val entrypoint: List<String>? = null,
        @SerialName("Env") val env: List<String>? = null,
        @SerialName("Labels") val labels: Map<String, String>? = null,
        @SerialName("Tty") val tty: Boolean = false,
    )

    @Serializable
    private data class HostConfigDto(@SerialName("RestartPolicy") val restartPolicy: RestartDto? = null)

    @Serializable
    private data class RestartDto(@SerialName("Name") val name: String? = null)

    @Serializable
    private data class MountDto(
        @SerialName("Source") val source: String = "",
        @SerialName("Destination") val destination: String = "",
        @SerialName("RW") val rw: Boolean = true,
    )

    @Serializable
    private data class InspectNetworksDto(
        @SerialName("Networks") val networks: Map<String, EndpointDto> = emptyMap(),
        @SerialName("Ports") val ports: Map<String, List<HostBindingDto>?> = emptyMap(),
    )

    @Serializable
    private data class HostBindingDto(@SerialName("HostIp") val hostIp: String? = null, @SerialName("HostPort") val hostPort: String? = null)

    fun parseTty(body: String): Boolean = json.decodeFromString(InspectDto.serializer(), body).config.tty

    fun parseInspect(body: String, profile: String = "default"): ContainerDetail {
        val i = json.decodeFromString(InspectDto.serializer(), body)
        val ports = i.networkSettings.ports.flatMap { (key, bindings) ->
            val privatePort = key.substringBefore('/').toIntOrNull() ?: return@flatMap emptyList()
            val type = key.substringAfter('/', "tcp")
            bindings?.map { ContainerPort(privatePort, it.hostPort?.toIntOrNull(), it.hostIp, type) } ?: listOf(ContainerPort(privatePort, null, null, type))
        }
        val created = i.created?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }
        return ContainerDetail(
            container = Container(
                id = i.id,
                name = i.name.removePrefix("/"),
                image = i.config.image,
                state = i.state.status,
                status = i.state.status,
                ports = ports,
                ipAddresses = i.networkSettings.networks.values.mapNotNull { it.ipAddress?.takeIf(String::isNotBlank) },
                createdEpochSeconds = created,
                profile = profile,
                labels = i.config.labels.orEmpty(),
            ),
            command = i.config.entrypoint.orEmpty() + i.config.cmd.orEmpty(),
            env = i.config.env.orEmpty(),
            mounts = i.mounts.map { Mount(it.source, it.destination, it.rw) },
            networks = i.networkSettings.networks.mapValues { it.value.ipAddress.orEmpty() },
            restartPolicy = i.hostConfig.restartPolicy?.name?.takeIf { it.isNotEmpty() },
            restartCount = i.restartCount,
            exitCode = i.state.exitCode,
            startedAt = i.state.startedAt,
        )
    }

    @Serializable
    private data class StatsDto(
        @SerialName("cpu_stats") val cpu: CpuStatsDto = CpuStatsDto(),
        @SerialName("memory_stats") val memory: MemoryStatsDto = MemoryStatsDto(),
        val networks: Map<String, NetDto>? = null,
        @SerialName("blkio_stats") val blkio: BlkioDto = BlkioDto(),
    )

    @Serializable
    private data class CpuStatsDto(
        @SerialName("cpu_usage") val usage: CpuUsageDto = CpuUsageDto(),
        @SerialName("system_cpu_usage") val system: Long = 0,
        @SerialName("online_cpus") val online: Int = 0,
    )

    @Serializable
    private data class CpuUsageDto(@SerialName("total_usage") val total: Long = 0, @SerialName("percpu_usage") val perCpu: List<Long>? = null)

    @Serializable
    private data class MemoryStatsDto(val usage: Long? = null, val limit: Long? = null, val stats: Map<String, Long>? = null)

    @Serializable
    private data class NetDto(@SerialName("rx_bytes") val rx: Long = 0, @SerialName("tx_bytes") val tx: Long = 0)

    @Serializable
    private data class BlkioDto(@SerialName("io_service_bytes_recursive") val bytes: List<BlkioEntryDto>? = null)

    @Serializable
    private data class BlkioEntryDto(val op: String = "", val value: Long = 0)

    fun parseStats(body: String): DockerStatsRaw {
        val s = json.decodeFromString(StatsDto.serializer(), body)
        // cgroup v2 reports inactive_file, v1 reports cache; both are reclaimable page cache.
        val cache = s.memory.stats?.let { it["inactive_file"] ?: it["total_inactive_file"] ?: it["cache"] } ?: 0
        val blk = s.blkio.bytes.orEmpty()
        return DockerStatsRaw(
            cpuTotal = s.cpu.usage.total,
            systemCpu = s.cpu.system,
            onlineCpus = s.cpu.online.takeIf { it > 0 } ?: s.cpu.usage.perCpu?.size ?: 1,
            memUsage = s.memory.usage,
            memLimit = s.memory.limit,
            memCache = cache,
            rxBytes = s.networks.orEmpty().values.sumOf { it.rx },
            txBytes = s.networks.orEmpty().values.sumOf { it.tx },
            blkRead = blk.filter { it.op.equals("read", ignoreCase = true) }.sumOf { it.value },
            blkWrite = blk.filter { it.op.equals("write", ignoreCase = true) }.sumOf { it.value },
        )
    }

    @Serializable
    private data class ImageInspectDto(
        @SerialName("Architecture") val architecture: String? = null,
        @SerialName("Os") val os: String? = null,
        @SerialName("Config") val config: ImageConfigDto? = null,
    )

    @Serializable
    private data class ImageConfigDto(@SerialName("Labels") val labels: Map<String, String>? = null)

    @Serializable
    private data class HistoryDto(
        @SerialName("Id") val id: String = "",
        @SerialName("Created") val created: Long? = null,
        @SerialName("CreatedBy") val createdBy: String = "",
        @SerialName("Size") val size: Long = 0,
    )

    fun parseImageDetail(image: Image, inspect: String, history: String): ImageDetail {
        val i = json.decodeFromString(ImageInspectDto.serializer(), inspect)
        val layers = json.decodeFromString(ListSerializer(HistoryDto.serializer()), history)
        return ImageDetail(
            image = image,
            architecture = i.architecture,
            os = i.os,
            labels = i.config?.labels.orEmpty(),
            layers = layers.map { ImageLayer(it.id, it.createdBy, it.size, it.created) },
        )
    }

    fun parsePrune(body: String, listKey: String): PruneResult {
        val o = json.parseToJsonElement(body) as JsonObject
        val items = (o[listKey] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            when (e) {
                is JsonObject -> (e["Deleted"] ?: e["Untagged"])?.jsonPrimitive?.contentOrNull
                else -> e.jsonPrimitive.contentOrNull
            }
        }
        return PruneResult(false, items, o["SpaceReclaimed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0)
    }

    data class PullProgress(val text: String?, val error: String?)

    fun parsePullLine(line: String): PullProgress {
        if (line.isBlank()) return PullProgress(null, null)
        val o = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: return PullProgress(null, null)
        val error = o["error"]?.jsonPrimitive?.contentOrNull
        val status = o["status"]?.jsonPrimitive?.contentOrNull
        val id = o["id"]?.jsonPrimitive?.contentOrNull
        val progress = o["progress"]?.jsonPrimitive?.contentOrNull
        val text = listOfNotNull(id?.let { "$it:" }, status, progress).joinToString(" ").takeIf { it.isNotBlank() }
        return PullProgress(text, error)
    }

    @Serializable
    private data class DfDto(
        @SerialName("LayersSize") val layersSize: Long = 0,
        @SerialName("Images") val images: List<DfImageDto>? = null,
        @SerialName("Containers") val containers: List<DfContainerDto>? = null,
        @SerialName("Volumes") val volumes: List<DfVolumeDto>? = null,
        @SerialName("BuildCache") val buildCache: List<DfCacheDto>? = null,
    )

    @Serializable
    private data class DfImageDto(@SerialName("Size") val size: Long = 0, @SerialName("SharedSize") val shared: Long = 0, @SerialName("Containers") val containers: Int = 0)

    @Serializable
    private data class DfContainerDto(@SerialName("SizeRw") val sizeRw: Long = 0, @SerialName("State") val state: String = "")

    @Serializable
    private data class DfVolumeDto(@SerialName("UsageData") val usage: UsageDto? = null)

    @Serializable
    private data class UsageDto(@SerialName("Size") val size: Long = 0, @SerialName("RefCount") val refCount: Int = 0)

    @Serializable
    private data class DfCacheDto(@SerialName("Size") val size: Long = 0, @SerialName("InUse") val inUse: Boolean = false)

    fun parseSystemDf(body: String, profile: String): DockerDisk {
        val d = json.decodeFromString(DfDto.serializer(), body)
        val images = d.images.orEmpty()
        val unusedImages = images.filter { it.containers == 0 }.sumOf { (it.size - it.shared).coerceAtLeast(0) }
        val stoppedContainers = d.containers.orEmpty().filter { it.state != "running" }.sumOf { it.sizeRw }
        val unusedVolumes = d.volumes.orEmpty().filter { (it.usage?.refCount ?: 1) == 0 }.sumOf { it.usage?.size?.coerceAtLeast(0) ?: 0 }
        val cache = d.buildCache.orEmpty()
        return DockerDisk(
            profile = profile,
            imagesBytes = d.layersSize,
            containersBytes = d.containers.orEmpty().sumOf { it.sizeRw },
            volumesBytes = d.volumes.orEmpty().sumOf { it.usage?.size?.coerceAtLeast(0) ?: 0 },
            buildCacheBytes = cache.sumOf { it.size },
            reclaimableBytes = unusedImages + stoppedContainers + unusedVolumes + cache.filter { !it.inUse }.sumOf { it.size },
        )
    }

    @Serializable
    private data class NetworkDto(
        @SerialName("Name") val name: String,
        @SerialName("Driver") val driver: String? = null,
        @SerialName("IPAM") val ipam: IpamDto? = null,
    )

    @Serializable
    private data class IpamDto(@SerialName("Config") val config: List<IpamConfigDto>? = null)

    @Serializable
    private data class IpamConfigDto(@SerialName("Subnet") val subnet: String? = null)

    fun parseNetworks(body: String): List<NetworkInfo> =
        json.decodeFromString(ListSerializer(NetworkDto.serializer()), body).map {
            NetworkInfo(it.name, "docker", it.driver, it.ipam?.config?.mapNotNull { c -> c.subnet }?.joinToString(", ")?.takeIf(String::isNotEmpty))
        }
}
