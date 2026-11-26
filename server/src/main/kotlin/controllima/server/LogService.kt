package controllima.server

import controllima.server.http.forbidden
import controllima.server.http.notFound
import controllima.server.security.Redactor
import controllima.shared.KubeRoutes
import controllima.shared.LogLine
import controllima.shared.LogSource
import controllima.shared.Role
import controllima.shared.Settings
import controllima.shared.Targets
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Log sources from docs/plan/logs.md. A source id is `<target>#<kind>[:<file>]`; files are resolved
 * from the instance directory we already know, never from the id, so an id cannot point anywhere else.
 */
class LogService(
    private val paths: DataPaths,
    private val backends: Backends,
    private val inventory: Inventory,
    private val containers: ContainerInventory,
    private val kube: KubernetesService? = null,
    private val settings: () -> Settings,
) {
    private fun redactor() = Redactor(settings().redactionPatterns)

    fun sources(target: String?, role: Role): List<LogSource> {
        val out = mutableListOf<LogSource>()
        val vms = inventory.snapshot.value.vms.filter { target == null || it.ref.targetId == target }
        for (vm in vms) out += vmSources(vm)
        if (target == null || target.startsWith("container:")) {
            val list = if (target != null) containers.cached().filter { it.targetId == target } else containers.cached()
            list.forEach { c -> out += LogSource("${c.targetId}#docker", c.targetId, "docker", "${c.name}: stdout/stderr") }
        }
        // Pods are listed only on request (from the Kubernetes page): a cluster can have hundreds.
        KubeRoutes.parsePodTarget(target.orEmpty())?.let { (cluster, ns, pod) ->
            val names = kube?.cachedContainers(cluster, ns, pod).orEmpty()
            if (names.isEmpty()) out += LogSource("$target#pod", target!!, "pod", "$ns/$pod")
            names.forEach { c -> out += LogSource("$target#pod:$c", target!!, "pod", "$ns/$pod: $c") }
        }
        if (target == null || target == SELF) {
            out += LogSource("$SELF#server", SELF, "server", "ControlLima server log", paths.serverLog.isFile, if (paths.serverLog.isFile) null else "No log written yet")
            if (role.allows(Role.ADMIN)) out += LogSource("$SELF#audit", SELF, "audit", "Audit log", paths.auditLog.isFile)
        }
        return out
    }

    private fun vmSources(vm: Vm): List<LogSource> {
        val t = vm.ref.targetId
        val dir = vm.dir?.let(::File)
        val running = vm.status == VmStatus.RUNNING
        val reason = if (running) null else "The VM is not responding or not running; only host-side logs are available"
        val out = mutableListOf<LogSource>()
        listOf("ha.stdout" to "Host agent (stdout)", "ha.stderr" to "Host agent (stderr)").forEach { (kind, label) ->
            val f = dir?.let { File(it, "$kind.log") }
            out += LogSource("$t#$kind", t, kind, label, f?.isFile == true, if (f?.isFile == true) null else "File not found")
        }
        dir?.listFiles { f -> SERIAL.matches(f.name) }.orEmpty().sortedBy { it.name }.forEach { f ->
            out += LogSource("$t#serial:${f.name}", t, "serial", "Serial console (${f.name})")
        }
        out += LogSource("$t#journal", t, "journal", "Guest journal", running && vm.sshConfigFile != null, reason)
        out += LogSource("$t#cloud-init", t, "cloud-init", "cloud-init output", running && vm.sshConfigFile != null, reason)
        if (vm.source == VmSource.COLIMA) {
            val f = backends.colima().daemonLog(vm.name)?.let(::File)
            out += LogSource("$t#daemon", t, "daemon", "Colima daemon", f?.isFile == true, if (f?.isFile == true) null else "File not found")
        }
        return out
    }

    private data class Parsed(val target: String, val kind: String, val file: String?)

    private fun parse(source: String): Parsed {
        val hash = source.lastIndexOf('#')
        require(hash > 0) { "Invalid log source" }
        val kind = source.substring(hash + 1)
        return Parsed(source.substring(0, hash), kind.substringBefore(':'), kind.substringAfter(':', "").ifEmpty { null })
    }

    private fun vmOf(target: String): Vm {
        val ref = Targets.parseVm(target) ?: notFound("Unknown log target")
        return inventory.find(ref) ?: notFound("No VM ${ref.name}")
    }

    private fun fileOf(p: Parsed, role: Role): File? = when {
        p.target == SELF && p.kind == "server" -> paths.serverLog
        p.target == SELF && p.kind == "audit" -> if (role.allows(Role.ADMIN)) paths.auditLog else forbidden("The audit log is for admins only")
        p.kind == "ha.stdout" || p.kind == "ha.stderr" -> vmOf(p.target).dir?.let { File(it, "${p.kind}.log") }
        p.kind == "serial" -> {
            val name = p.file?.takeIf { SERIAL.matches(it) } ?: throw IllegalArgumentException("Invalid serial log")
            vmOf(p.target).dir?.let { File(it, name) }
        }
        p.kind == "daemon" -> backends.colima().daemonLog(vmOf(p.target).also { require(it.source == VmSource.COLIMA) }.name)?.let(::File)
        else -> null
    }

    /** Last [tail] lines, redacted. */
    suspend fun tail(source: String, tail: Int, role: Role): List<LogLine> = withContext(Dispatchers.IO) {
        val p = parse(source)
        val n = tail.coerceIn(1, MAX_TAIL)
        val r = redactor()
        val lines = when (p.kind) {
            "docker" -> dockerLines(p.target, n)
            "pod" -> podLines(p, n)
            "journal", "cloud-init" -> {
                val vm = vmOf(p.target)
                val ssh = vm.sshConfigFile ?: throw IllegalStateException("VM has no SSH config")
                val instance = backends.limaInstance(vm)
                val remote = if (p.kind == "journal") "journalctl -o json --no-pager -n $n" else "sudo -n tail -n $n /var/log/cloud-init-output.log"
                val result = backends.guest.exec(ssh, instance, remote, 15.seconds)
                check(result.ok) { if (result.timedOut) "The guest did not answer in time" else "Reading the guest log failed (exit ${result.exitCode})" }
                result.stdout.lines().filter { it.isNotEmpty() }.map { if (p.kind == "journal") journalLine(it) else LogLine(null, "stdout", it) }
            }
            else -> {
                val file = fileOf(p, role) ?: notFound("Unknown log source")
                if (!file.isFile) notFound("Log file not found")
                tailLines(file, n).map { lineFor(p.kind, it) }
            }
        }
        lines.takeLast(n).map { it.copy(line = r.redact(it.line)) }
    }

    /** Sends the last [tail] lines, then new ones until the caller is cancelled. */
    suspend fun follow(source: String, tail: Int, role: Role, emit: suspend (LogLine) -> Unit) {
        val p = parse(source)
        val r = redactor()
        val n = tail.coerceIn(0, MAX_TAIL)
        val out = Channel<LogLine>(capacity = 10_000)
        val handle: Closeable = when (p.kind) {
            "docker" -> openDocker(p.target, n, onEnd = { out.close() }) { out.trySend(it) }
            // The API's follow mode needs a long-lived request the KubeClient does not offer yet: send the tail and end.
            "pod" -> {
                withContext(Dispatchers.IO) { podLines(p, n.coerceAtLeast(1)) }.forEach { out.trySend(it) }
                out.close()
                Closeable { }
            }
            "journal", "cloud-init" -> {
                val vm = vmOf(p.target)
                val ssh = vm.sshConfigFile ?: throw IllegalStateException("VM has no SSH config")
                val remote = if (p.kind == "journal") "journalctl -o json --no-pager -f -n $n" else "sudo -n tail -n $n -F /var/log/cloud-init-output.log"
                val stream = backends.runner.stream(backends.guest.command(ssh, backends.limaInstance(vm), remote), emptyMap(),
                    onLine = { out.trySend(if (p.kind == "journal") journalLine(it) else LogLine(null, "stdout", it)) },
                    onExit = { out.close() })
                Closeable { stream.close() }
            }
            else -> {
                val file = fileOf(p, role) ?: notFound("Unknown log source")
                if (!file.isFile) notFound("Log file not found")
                tailLines(file, n).forEach { out.trySend(lineFor(p.kind, it)) }
                followFile(file, p.kind) { out.trySend(it) }
            }
        }
        try {
            for (line in out) emit(line.copy(line = r.redact(line.line)))
        } finally {
            handle.close()
        }
    }

    private suspend fun podLines(p: Parsed, n: Int): List<LogLine> {
        val (cluster, ns, pod) = KubeRoutes.parsePodTarget(p.target) ?: notFound("Unknown pod")
        val k = kube ?: notFound("Kubernetes is not available")
        return k.podLogs(cluster, ns, pod, p.file, n.coerceIn(1, 10_000))
    }

    private fun dockerProvider(target: String) = Targets.parseContainer(target)?.let { (profile, id) ->
        (containers.docker(profile) ?: throw IllegalStateException("Docker is not available for profile $profile")) to id
    } ?: notFound("Unknown container")

    private fun dockerLines(target: String, n: Int): List<LogLine> {
        val (docker, id) = dockerProvider(target)
        val lines = java.util.concurrent.ConcurrentLinkedQueue<LogLine>()
        val done = java.util.concurrent.CountDownLatch(1)
        val handle = docker.logs(id, n, follow = false, sinceSeconds = null, onEnd = done::countDown) { lines += it }
        try {
            check(done.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "Docker did not return the logs in time" }
        } finally {
            handle.close()
        }
        return lines.toList()
    }

    private fun openDocker(target: String, n: Int, onEnd: () -> Unit, onLine: (LogLine) -> Unit): Closeable {
        val (docker, id) = dockerProvider(target)
        return docker.logs(id, n, follow = true, sinceSeconds = null, onEnd = onEnd, onLine = onLine)
    }

    /** Polls the file once a second; handles truncation and rotation by starting over from the top. */
    private fun followFile(file: File, kind: String, onLine: (LogLine) -> Unit): Closeable {
        val thread = Thread({
            var pos = file.length()
            val partial = StringBuilder()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    val len = file.length()
                    if (len < pos) pos = 0
                    if (len == pos) continue
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(pos)
                        val bytes = ByteArray((len - pos).coerceAtMost(4L shl 20).toInt())
                        raf.readFully(bytes)
                        pos += bytes.size
                        partial.append(bytes.decodeToString())
                    }
                    while (true) {
                        val nl = partial.indexOf("\n")
                        if (nl < 0) break
                        onLine(lineFor(kind, partial.substring(0, nl)))
                        partial.delete(0, nl + 1)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: java.io.IOException) {
            }
        }, "log-follow").apply { isDaemon = true; start() }
        return Closeable { thread.interrupt() }
    }

    private fun lineFor(kind: String, raw: String): LogLine = when (kind) {
        "ha.stdout", "ha.stderr", "daemon" -> logrusLine(raw, if (kind == "ha.stderr") "stderr" else "stdout")
        else -> LogLine(null, "stdout", raw)
    }

    companion object {
        const val SELF = "controllima"
        const val MAX_TAIL = 50_000
        private val SERIAL = Regex("^serial[A-Za-z0-9_.-]*\\.log$")
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads at most the last 8 MB, so a huge log never loads whole. */
        fun tailLines(file: File, n: Int): List<String> {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = (len - (8L shl 20)).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                val lines = bytes.decodeToString().split('\n').let { if (start > 0) it.drop(1) else it }
                return lines.dropLastWhile { it.isEmpty() }.takeLast(n)
            }
        }

        /** Lima's host agent and Colima's daemon write logrus JSON (`level`, `msg`, `time`); other lines pass through. */
        fun logrusLine(raw: String, stream: String): LogLine {
            val o = raw.takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
                ?: return LogLine(null, stream, raw)
            val msg = (o["msg"] as? JsonPrimitive)?.contentOrNull ?: raw
            val extra = o.filterKeys { it !in setOf("msg", "level", "time") }.entries.joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v}" }
            val ts = (o["time"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            return LogLine(ts, stream, if (extra.isEmpty()) msg else "$msg $extra", (o["level"] as? JsonPrimitive)?.contentOrNull)
        }

        /** `journalctl -o json`: MESSAGE may be a byte array for non-UTF-8 text. */
        fun journalLine(raw: String): LogLine {
            val o = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return LogLine(null, "stdout", raw)
            val message = when (val m = o["MESSAGE"]) {
                is JsonPrimitive -> m.contentOrNull.orEmpty()
                is JsonArray -> m.mapNotNull { it.jsonPrimitive.intOrNull?.toByte() }.toByteArray().decodeToString()
                else -> ""
            }
            val unit = (o["SYSLOG_IDENTIFIER"] ?: o["_SYSTEMD_UNIT"])?.let { (it as? JsonPrimitive)?.contentOrNull }
            val ts = (o["__REALTIME_TIMESTAMP"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.div(1000)
            val level = when ((o["PRIORITY"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) {
                0, 1, 2, 3 -> "error"; 4 -> "warning"; 5, 6 -> "info"; 7 -> "debug"; else -> null
            }
            return LogLine(ts, "stdout", if (unit != null) "$unit: $message" else message, level)
        }
    }
}
