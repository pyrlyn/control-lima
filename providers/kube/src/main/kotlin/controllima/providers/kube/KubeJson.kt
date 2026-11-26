package controllima.providers.kube

import controllima.shared.KubeContainerPort
import controllima.shared.KubeNode
import controllima.shared.KubePod
import controllima.shared.KubeService
import controllima.shared.KubeServicePort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

/** CPU in millicores and memory in bytes, from metrics-server. */
data class KubeUsage(val cpuMillicores: Long?, val memoryBytes: Long?)

/** Parsers for Kubernetes API list responses. Unknown fields are ignored. */
object KubeJson {
    private val json = Json { ignoreUnknownKeys = true }

    private fun items(body: String): List<JsonObject> = (root(body)["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.arr(key: String): List<JsonObject> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

    private fun epochSeconds(ts: String?): Long? = ts?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }

    fun namespaces(body: String): List<String> = items(body).mapNotNull { it.obj("metadata").str("name") }

    fun nodes(body: String): List<KubeNode> = items(body).map { item ->
        val meta = item.obj("metadata")
        val status = item.obj("status")
        val info = status.obj("nodeInfo")
        KubeNode(
            name = meta.str("name").orEmpty(),
            ready = status.arr("conditions").any { it.str("type") == "Ready" && it.str("status") == "True" },
            roles = meta.obj("labels")?.keys.orEmpty().filter { it.startsWith(ROLE_LABEL) }.map { it.removePrefix(ROLE_LABEL) }.filter { it.isNotEmpty() }.sorted(),
            kubeletVersion = info.str("kubeletVersion").orEmpty(),
            osImage = info.str("osImage"),
            internalIp = status.arr("addresses").firstOrNull { it.str("type") == "InternalIP" }?.str("address"),
            cpuCapacityMillicores = KubeQuantity.millicores(status.obj("capacity").str("cpu")),
            memoryCapacityBytes = KubeQuantity.bytes(status.obj("capacity").str("memory")),
        )
    }

    private fun root(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body) as JsonObject }.getOrElse { throw KubeException("unexpected API response") }

    fun pods(body: String): List<KubePod> = items(body).map(::pod)

    /** One `GET .../pods/{name}` answer. */
    fun pod(body: String): KubePod = pod(root(body))

    private fun pod(item: JsonObject): KubePod {
        val meta = item.obj("metadata")
        val spec = item.obj("spec")
        val status = item.obj("status")
        val containers = spec.arr("containers").mapNotNull { it.str("name") }
        val statuses = status.arr("containerStatuses")
        return KubePod(
            namespace = meta.str("namespace").orEmpty(),
            name = meta.str("name").orEmpty(),
            phase = podStatus(meta, status, statuses),
            ready = "${statuses.count { (it["ready"] as? JsonPrimitive)?.booleanOrNull == true }}/${containers.size}",
            restarts = statuses.sumOf { it.int("restartCount") ?: 0 },
            node = spec.str("nodeName"),
            podIp = status.str("podIP"),
            containers = containers,
            ports = spec.arr("containers").flatMap { c -> c.arr("ports") }.mapNotNull { p ->
                KubeContainerPort(p.str("name"), p.int("containerPort") ?: return@mapNotNull null, p.str("protocol") ?: "TCP")
            },
            createdEpochSeconds = epochSeconds(meta.str("creationTimestamp")),
        )
    }

    /** What `kubectl get pods` shows as STATUS, which is more useful than the bare phase. */
    private fun podStatus(meta: JsonObject?, status: JsonObject?, statuses: List<JsonObject>): String {
        if (meta.str("deletionTimestamp") != null) return "Terminating"
        statuses.firstNotNullOfOrNull { it.obj("state").obj("waiting").str("reason") }?.let { return it }
        status.str("reason")?.let { return it }
        return status.str("phase") ?: "Unknown"
    }

    fun services(body: String): List<KubeService> = items(body).map(::service)

    /** One `GET .../services/{name}` answer. */
    fun service(body: String): KubeService = service(root(body))

    private fun service(item: JsonObject): KubeService {
        val meta = item.obj("metadata")
        val spec = item.obj("spec")
        return KubeService(
            namespace = meta.str("namespace").orEmpty(),
            name = meta.str("name").orEmpty(),
            type = spec.str("type") ?: "ClusterIP",
            clusterIp = spec.str("clusterIP"),
            ports = spec.arr("ports").mapNotNull { p ->
                KubeServicePort(
                    name = p.str("name"),
                    port = p.int("port") ?: return@mapNotNull null,
                    targetPort = p.str("targetPort"),
                    nodePort = p.int("nodePort"),
                    protocol = p.str("protocol") ?: "TCP",
                )
            },
            selector = spec.obj("selector")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
        )
    }

    fun nodeMetrics(body: String): Map<String, KubeUsage> = items(body).associate { item ->
        val usage = item.obj("usage")
        item.obj("metadata").str("name").orEmpty() to KubeUsage(KubeQuantity.millicores(usage.str("cpu")), KubeQuantity.bytes(usage.str("memory")))
    }

    /** Keyed by `namespace/name`; container usage is summed per pod. */
    fun podMetrics(body: String): Map<String, KubeUsage> = items(body).associate { item ->
        val meta = item.obj("metadata")
        val usages = item.arr("containers").map { it.obj("usage") }
        val cpu = usages.mapNotNull { KubeQuantity.millicores(it.str("cpu")) }
        val memory = usages.mapNotNull { KubeQuantity.bytes(it.str("memory")) }
        "${meta.str("namespace")}/${meta.str("name")}" to KubeUsage(cpu.takeIf { it.isNotEmpty() }?.sum(), memory.takeIf { it.isNotEmpty() }?.sum())
    }

    /** The `message` of a `kind: Status` error body, or null when the body is something else. */
    fun statusMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (root.str("kind") != "Status") return null
        return root.str("message")?.takeIf { it.isNotBlank() }
    }

    private const val ROLE_LABEL = "node-role.kubernetes.io/"
}
