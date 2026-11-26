package controllima.providers.host

import controllima.providers.process.CommandRunner
import controllima.shared.HostListener
import java.io.File
import kotlin.time.Duration.Companion.seconds

class HostException(message: String) : RuntimeException(message)

data class DiskSpace(val freeBytes: Long, val totalBytes: Long)

data class FileSize(val allocatedBytes: Long, val apparentBytes: Long)

data class ProcessStats(val cpuPercent: Double, val rssBytes: Long)

/** The machine ControlLima runs on. */
interface HostProvider {
    /** TCP listeners from `lsof -F` (machine-readable field output). */
    fun listeners(): List<HostListener>

    fun diskSpace(path: String): DiskSpace

    /** Actual allocated size of a (sparse) VM disk file, and its apparent size. */
    fun fileSize(path: String): FileSize?

    /** Host CPU % and RSS of the VM process (VZ host agent or QEMU). */
    fun processStats(pid: Long): ProcessStats?

    /** Desktop notification; false when the platform has no notifier or it failed. */
    fun notify(title: String, message: String): Boolean

    fun openBrowser(url: String)

    val isMac: Boolean
}

class LocalHostProvider(
    private val runner: CommandRunner,
    private val osName: String = System.getProperty("os.name"),
) : HostProvider {
    override val isMac: Boolean get() = osName.startsWith("Mac")

    override fun listeners(): List<HostListener> {
        val result = runner.run(listOf("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pcn"), 5.seconds)
        if (result.timedOut) throw HostException("lsof timed out after 5s")
        // lsof exits 1 when nothing matches; that is an empty list, not an error.
        if (result.exitCode != 0 && result.stdout.isBlank() && result.stderr.isNotBlank()) throw HostException("lsof failed: ${result.stderr.trim()}")
        return Lsof.parse(result.stdout)
    }

    override fun diskSpace(path: String): DiskSpace {
        var dir: File? = File(path)
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val target = dir ?: File("/")
        return DiskSpace(target.usableSpace, target.totalSpace)
    }

    override fun fileSize(path: String): FileSize? {
        val file = File(path)
        if (!file.isFile) return null
        // `stat` with an explicit format prints numbers only; BSD and GNU spell the format differently.
        val command = if (isMac) listOf("stat", "-f", "%b", path) else listOf("stat", "-c", "%b", path)
        val blocks = runner.run(command, 5.seconds).takeIf { it.ok }?.stdout?.trim()?.toLongOrNull()
        return FileSize(allocatedBytes = blocks?.times(512) ?: file.length(), apparentBytes = file.length())
    }

    override fun processStats(pid: Long): ProcessStats? {
        if (pid <= 0) return null
        // `=` after each column suppresses the header, leaving two numbers.
        val result = runner.run(listOf("ps", "-o", "%cpu=", "-o", "rss=", "-p", pid.toString()), 5.seconds)
        if (!result.ok) return null
        val fields = result.stdout.trim().split(Regex("\\s+"))
        val cpu = fields.getOrNull(0)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val rssKb = fields.getOrNull(1)?.toLongOrNull() ?: return null
        return ProcessStats(cpu, rssKb * 1024)
    }

    override fun notify(title: String, message: String): Boolean {
        val command = if (isMac) {
            // Text goes in as argv, never spliced into the AppleScript source.
            listOf("osascript", "-e", "on run argv", "-e", "display notification (item 2 of argv) with title (item 1 of argv)", "-e", "end run", title, message)
        } else {
            listOf("notify-send", "--", title, message)
        }
        return runCatching { runner.run(command, 5.seconds).ok }.getOrDefault(false)
    }

    override fun openBrowser(url: String) {
        require(url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) { "only local URLs are opened" }
        runCatching { runner.run(listOf(if (isMac) "open" else "xdg-open", url), 5.seconds) }
    }
}

/** Parser for `lsof -F pcn`: `p<pid>`, `c<command>`, then one `n<addr:port>` per socket. */
object Lsof {
    fun parse(output: String): List<HostListener> {
        val out = mutableListOf<HostListener>()
        var pid = 0
        var command = ""
        for (line in output.lineSequence()) {
            if (line.isEmpty()) continue
            val value = line.substring(1)
            when (line[0]) {
                'p' -> { pid = value.toIntOrNull() ?: 0; command = "" }
                'c' -> command = value
                'n' -> {
                    val colon = value.lastIndexOf(':')
                    if (colon < 0) continue
                    val port = value.substring(colon + 1).toIntOrNull() ?: continue
                    val ip = value.substring(0, colon).removePrefix("[").removeSuffix("]").ifEmpty { "*" }
                    out += HostListener(pid, command, ip, port)
                }
            }
        }
        return out.distinctBy { listOf(it.pid, it.hostIp, it.port) }
    }
}
