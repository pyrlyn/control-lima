package controllima.providers.colima

import controllima.providers.process.CommandResult
import controllima.providers.process.CommandRunner
import controllima.shared.Names
import controllima.shared.ProtectedNames
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ColimaException(message: String) : RuntimeException(message)

/** Result of `colima status --json` for a running profile. */
data class ColimaStatus(val dockerSocket: String?, val kubernetes: Boolean, val runtime: String?)

/** Colima profiles. Every call is bounded by a timeout. */
interface ColimaProvider {
    fun list(): List<Vm>

    /** Path of the Docker socket of [profile], if Colima exposes one. */
    fun dockerSocket(profile: String): String

    fun probe(profile: String, timeout: Duration = 3.seconds): Boolean = false
    fun status(profile: String): ColimaStatus? = null
    fun start(profile: String, onLine: (String) -> Unit = {}): CommandResult = unsupported()
    fun stop(profile: String, force: Boolean = false, onLine: (String) -> Unit = {}): CommandResult = unsupported()
    fun delete(profile: String): CommandResult = unsupported()

    /** Profiles found under `~/.colima`, including stopped ones. */
    fun discoverProfiles(): List<String> = emptyList()

    /** Colima VMs are Lima instances in `~/.colima/_lima`; this is the instance name and its ssh.config. */
    fun limaInstance(profile: String): String = if (profile == "default") "colima" else "colima-$profile"
    fun sshConfigFile(profile: String): String? = null
    fun instanceDir(profile: String): String? = null
    fun configFile(profile: String): String? = null
    fun daemonLog(profile: String): String? = null

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not supported by this ColimaProvider")
}

class ColimaCliProvider(
    private val runner: CommandRunner,
    private val colima: String = "colima",
    private val home: String = System.getProperty("user.home"),
    private val extraArgs: List<String> = emptyList(),
    private val protectedNames: () -> Set<String> = { ProtectedNames.defaults },
    private val timeout: Duration = 10.seconds,
) : ColimaProvider {
    private val colimaDir get() = File(home, ".colima")

    private fun cmd(vararg args: String) = listOf(colima) + extraArgs + args.toList()

    private fun profileName(profile: String): String {
        if (!Names.isValidVmName(profile)) throw ColimaException("Invalid Colima profile: $profile")
        return profile
    }

    private fun checked(label: String, result: CommandResult, limit: Duration): CommandResult {
        if (result.timedOut) throw ColimaException("$label timed out after $limit")
        if (!result.ok) throw ColimaException("$label failed (${result.exitCode}): ${result.stderr.trim().lines().lastOrNull().orEmpty()}")
        return result
    }

    override fun list(): List<Vm> {
        val result = checked("colima list", runner.run(cmd("list", "--json"), timeout), timeout)
        return ColimaJson.parseList(result.stdout, protectedNames()).map { vm ->
            vm.copy(dir = instanceDir(vm.name), sshConfigFile = sshConfigFile(vm.name))
        }
    }

    override fun dockerSocket(profile: String): String = "$home/.colima/${profileName(profile)}/docker.sock"

    override fun probe(profile: String, timeout: Duration): Boolean =
        runner.run(cmd("ssh", "--profile", profileName(profile), "--", "true"), timeout).ok

    override fun status(profile: String): ColimaStatus? {
        val result = runner.run(cmd("status", "--profile", profileName(profile), "--json"), timeout)
        return if (result.ok) ColimaJson.parseStatus(result.stdout) else null
    }

    override fun start(profile: String, onLine: (String) -> Unit) =
        checked("colima start", runner.run(cmd("start", "--profile", profileName(profile)), 5.minutes, onLine = onLine), 5.minutes)

    override fun stop(profile: String, force: Boolean, onLine: (String) -> Unit): CommandResult {
        val args = if (force) arrayOf("stop", "--force", "--profile", profileName(profile)) else arrayOf("stop", "--profile", profileName(profile))
        return checked("colima stop", runner.run(cmd(*args), 5.minutes, onLine = onLine), 5.minutes)
    }

    override fun delete(profile: String) =
        checked("colima delete", runner.run(cmd("delete", "--force", "--profile", profileName(profile)), 2.minutes), 2.minutes)

    override fun discoverProfiles(): List<String> =
        colimaDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("_") && Names.isValidVmName(it.name) && File(it, "colima.yaml").exists() }
            .map { it.name }
            .sorted()

    override fun sshConfigFile(profile: String): String? =
        File(colimaDir, "_lima/${limaInstance(profile)}/ssh.config").takeIf { it.exists() }?.path

    override fun instanceDir(profile: String): String? = File(colimaDir, "_lima/${limaInstance(profile)}").takeIf { it.isDirectory }?.path

    override fun configFile(profile: String): String = File(colimaDir, "${profileName(profile)}/colima.yaml").path

    override fun daemonLog(profile: String): String = File(colimaDir, "${profileName(profile)}/daemon/daemon.log").path
}

/** Parsers for `colima list --json` (one JSON object per line) and `colima status --json`. */
object ColimaJson {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(
        val name: String,
        val status: String? = null,
        val arch: String? = null,
        val cpus: Int? = null,
        val memory: Long? = null,
        val disk: Long? = null,
        val runtime: String? = null,
        val address: String? = null,
    )

    @Serializable
    private data class Status(
        @SerialName("docker_socket") val dockerSocket: String? = null,
        val kubernetes: Boolean = false,
        val runtime: String? = null,
    )

    fun parseList(output: String, protectedNames: Set<String> = ProtectedNames.defaults): List<Vm> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { json.decodeFromString(Entry.serializer(), it) }
            .map { e ->
                Vm(
                    name = e.name,
                    source = VmSource.COLIMA,
                    status = when (e.status) {
                        "Running" -> VmStatus.RUNNING
                        "Stopped" -> VmStatus.STOPPED
                        "Broken" -> VmStatus.BROKEN
                        else -> VmStatus.UNKNOWN
                    },
                    arch = e.arch,
                    cpus = e.cpus,
                    memoryBytes = e.memory,
                    diskBytes = e.disk,
                    ipAddresses = listOfNotNull(e.address?.takeIf { it.isNotBlank() }),
                    runtime = e.runtime,
                    protected = e.name in protectedNames,
                )
            }
            .toList()

    fun parseStatus(output: String): ColimaStatus {
        val s = json.decodeFromString(Status.serializer(), output)
        return ColimaStatus(s.dockerSocket?.removePrefix("unix://"), s.kubernetes, s.runtime)
    }
}
