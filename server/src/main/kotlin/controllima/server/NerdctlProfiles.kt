package controllima.server

import controllima.providers.docker.DockerProvider
import controllima.providers.docker.GuestCommands
import controllima.providers.docker.GuestResult
import controllima.providers.docker.NerdctlProvider
import controllima.shared.Names
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Running Lima VMs whose guest has nerdctl become container profiles `lima:<vm>` (docs/plan/README.md, v2).
 * Detecting nerdctl costs an ssh round trip, so the answer is cached per VM.
 */
class NerdctlProfiles(
    private val backends: Backends,
    private val vms: () -> List<Vm>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val detected = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val providers = ConcurrentHashMap<String, DockerProvider>()

    suspend fun profiles(): List<String> = coroutineScope {
        val running = runningLima()
        detected.keys.retainAll(running.map { it.name }.toSet())
        running.map { vm -> async(Dispatchers.IO) { vm.name.takeIf { hasNerdctl(vm) } } }
            .awaitAll().filterNotNull().distinct().map { Names.NERDCTL_PREFIX + it }
    }

    fun docker(profile: String): DockerProvider? {
        val name = profile.removePrefix(Names.NERDCTL_PREFIX)
        if (!Names.isValidVmName(name)) return null
        val vm = runningLima().firstOrNull { it.name == name } ?: return null
        val ssh = vm.sshConfigFile ?: return null
        return providers.getOrPut("$name|$ssh") { NerdctlProvider(SshGuest(backends, ssh, name), profile) }
    }

    // Unresponsive VMs are left out: a hung guest would stall every container list for the ssh timeout.
    private fun runningLima(): List<Vm> =
        vms().filter { it.source == VmSource.LIMA && it.status == VmStatus.RUNNING && it.sshConfigFile != null }

    private fun hasNerdctl(vm: Vm): Boolean {
        detected[vm.name]?.let { (at, yes) -> if (clock() - at < TTL_MILLIS) return yes }
        val result = runCatching { backends.guest.exec(vm.sshConfigFile!!, vm.name, NerdctlProvider.DETECT) }.getOrNull()
        val yes = result?.ok == true && result.stdout.trim() == "yes"
        // A failed ssh (guest still booting) is asked again next time rather than remembered as "no".
        if (result?.ok == true) detected[vm.name] = clock() to yes
        return yes
    }

    private class SshGuest(private val backends: Backends, private val ssh: String, private val vm: String) : GuestCommands {
        override fun run(command: String, timeout: Duration): GuestResult {
            val r = backends.guest.exec(ssh, vm, command, timeout)
            return GuestResult(r.ok, r.stdout, if (r.timedOut) "nerdctl timed out in $vm" else r.stderr)
        }

        override fun stream(command: String, onLine: (String) -> Unit, onExit: () -> Unit): Closeable {
            val handle = backends.runner.stream(backends.guest.command(ssh, vm, command), onLine = onLine, onExit = { onExit() })
            return Closeable { handle.close() }
        }
    }

    private companion object {
        const val TTL_MILLIS = 10 * 60_000L
    }
}
