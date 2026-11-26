package controllima.server

import controllima.providers.colima.ColimaJson
import controllima.providers.lima.LimaJson
import controllima.providers.process.CommandResult
import controllima.providers.process.CommandRunner
import controllima.shared.RemoteHost
import controllima.shared.RemoteHostStatus
import controllima.shared.Settings
import controllima.shared.Vm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * Lima and Colima VMs on other machines (docs/pages/vms.md, Remote hosts), listed read-only over the
 * user's own ssh setup. Only two fixed commands ever run remotely; nothing on a remote host is changed.
 */
class RemoteHostsService(
    private val runner: CommandRunner,
    private val settings: () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var cache: Pair<List<RemoteHost>, List<RemoteHostStatus>>? = null
    private var cachedAt = 0L

    suspend fun list(maxAgeMillis: Long = CACHE_MILLIS): List<RemoteHostStatus> = lock.withLock {
        val hosts = settings().remoteHosts
        val hit = cache?.takeIf { it.first == hosts && clock() - cachedAt < maxAgeMillis }
        hit?.second ?: coroutineScope {
            hosts.map { h -> async(Dispatchers.IO) { check(h) } }.awaitAll()
        }.also {
            cache = hosts to it
            cachedAt = clock()
        }
    }

    private fun check(host: RemoteHost): RemoteHostStatus {
        val protectedNames = settings().protectedNames.toSet()
        fun status(reachable: Boolean, error: String?, vms: List<Vm> = emptyList()) =
            RemoteHostStatus(host.name, host.ssh, reachable, error, vms, clock())

        val lima = ssh(host, LIMA_LIST)
        if (lima.timedOut || lima.exitCode == SSH_FAILED) return status(false, failure(lima, "ssh did not connect"))
        val vms = mutableListOf<Vm>()
        val problems = mutableListOf<String>()
        when {
            lima.ok -> vms += runCatching { LimaJson.parseList(lima.stdout, protectedNames) }
                .getOrElse { problems += "limactl printed unreadable JSON"; emptyList() }
            lima.exitCode != NOT_FOUND -> problems += failure(lima, "limactl failed")
        }
        val colima = ssh(host, COLIMA_LIST)
        when {
            colima.ok -> runCatching { ColimaJson.parseList(colima.stdout, protectedNames) }
                // Colima's instances also appear in limactl as `colima[-profile]`; its own entry wins.
                .onSuccess { c -> vms.removeAll { it.name == "colima" || it.name.startsWith("colima-") }; vms += c }
                .onFailure { problems += "colima printed unreadable JSON" }
            // A host without Colima is normal, not an error.
            colima.exitCode != NOT_FOUND -> problems += failure(colima, "colima failed")
        }
        if (lima.exitCode == NOT_FOUND && colima.exitCode == NOT_FOUND) problems += "Neither limactl nor colima is installed"
        return status(true, problems.joinToString("; ").ifEmpty { null }, vms.sortedBy { it.name })
    }

    /** The target is validated ([controllima.shared.Names.isValidSshTarget]) and `--` ends ssh's options. */
    private fun ssh(host: RemoteHost, command: String): CommandResult =
        runner.run(listOf("ssh") + SSH_OPTIONS + listOf("--", host.ssh, command), timeout = 20.seconds)

    private fun failure(r: CommandResult, fallback: String): String = when {
        r.timedOut -> "Timed out"
        else -> r.stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(300) ?: fallback
    }

    companion object {
        const val MAX_HOSTS = 20
        const val CACHE_MILLIS = 30_000L
        private const val SSH_FAILED = 255
        private const val NOT_FOUND = 127

        /** No prompts (a password prompt would hang the server) and a quick give-up on a dead host. */
        val SSH_OPTIONS = listOf(
            "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
        )

        // A non-interactive ssh shell skips the login profile, so Homebrew's bin is not on PATH yet.
        private const val PATH = "export PATH=\"\$PATH:/opt/homebrew/bin:/usr/local/bin:\$HOME/.local/bin\"; "
        const val LIMA_LIST = "${PATH}limactl list --json"
        const val COLIMA_LIST = "${PATH}colima list --json"
    }
}
