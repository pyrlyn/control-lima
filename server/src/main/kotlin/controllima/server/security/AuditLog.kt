package controllima.server.security

import controllima.server.privateDir
import controllima.shared.ApiJson
import controllima.shared.AuditEntry
import controllima.shared.Role
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Audit log: JSON lines, rotated at 10 MB, rotated files kept for one year (docs/plan/auth.md).
 * Parameters that look like secrets are redacted before they are written.
 */
class AuditLog(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 10L * 1024 * 1024,
    private val keepMillis: Long = 365L * 86_400_000,
) {
    private val seenClients = mutableSetOf<String>()
    private val failedTimes = ArrayDeque<Long>()

    fun record(
        actor: String,
        role: Role?,
        action: String,
        target: String? = null,
        params: Map<String, String> = emptyMap(),
        result: String = "ok",
        durationMs: Long = 0,
    ) {
        val entry = AuditEntry(clock(), actor, role, action, target, redact(params), result, durationMs)
        write(ApiJson.encodeToString(AuditEntry.serializer(), entry))
    }

    /** "Token use from a new client": recorded once per credential and User-Agent. */
    fun noteClient(principal: Principal, userAgent: String?) {
        val key = principal.key + "|" + userAgent.orEmpty().take(200)
        val isNew = synchronized(seenClients) { seenClients.add(key) }
        if (isNew) record(principal.actor, principal.role, "client.new", params = mapOf("userAgent" to userAgent.orEmpty().take(200)))
    }

    /** Failed auth is audited, but throttled so a hostile page cannot fill the disk. */
    fun failedAuth(path: String, reason: String) {
        val now = clock()
        val allowed = synchronized(failedTimes) {
            while (failedTimes.isNotEmpty() && now - failedTimes.first() > 60_000) failedTimes.removeFirst()
            (failedTimes.size < 20).also { if (it) failedTimes.addLast(now) }
        }
        if (allowed) record("anonymous", null, "auth.failed", path.take(200), mapOf("reason" to reason.take(200)), "denied")
    }

    @Synchronized
    private fun write(line: String) {
        file.parentFile?.let(::privateDir)
        if (file.isFile && file.length() + line.length > maxBytes) rotate()
        val path = file.toPath()
        val fresh = !file.exists()
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if (fresh) runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun rotate() {
        val rotated = File(file.parentFile, "${file.nameWithoutExtension}-${clock()}.log")
        file.renameTo(rotated)
        val cutoff = clock() - keepMillis
        rotatedFiles().filter { it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private fun rotatedFiles(): List<File> =
        file.parentFile?.listFiles { f -> f.name.startsWith("${file.nameWithoutExtension}-") && f.name.endsWith(".log") }?.toList().orEmpty()

    /** Newest first, at most [limit] entries. */
    fun query(fromMillis: Long? = null, toMillis: Long? = null, actor: String? = null, limit: Int = 1000): List<AuditEntry> {
        val files = synchronized(this) { (rotatedFiles().sortedBy { it.name } + file).filter { it.isFile } }
        val out = ArrayDeque<AuditEntry>()
        for (f in files) {
            f.useLines { lines ->
                for (line in lines) {
                    val e = runCatching { ApiJson.decodeFromString(AuditEntry.serializer(), line) }.getOrNull() ?: continue
                    if (fromMillis != null && e.timeMillis < fromMillis) continue
                    if (toMillis != null && e.timeMillis > toMillis) continue
                    if (actor != null && e.actor != actor) continue
                    out.addLast(e)
                    if (out.size > limit) out.removeFirst()
                }
            }
        }
        return out.reversed()
    }

    fun sizeBytes(): Long = (rotatedFiles() + file).filter { it.isFile }.sumOf { it.length() }

    companion object {
        private val SECRET_KEY = Regex("(?i)(password|passwd|secret|token|api[_-]?key|authorization)")

        fun redact(params: Map<String, String>): Map<String, String> =
            params.mapValues { (k, v) -> if (SECRET_KEY.containsMatchIn(k)) "•••" else v.take(500) }
    }
}
