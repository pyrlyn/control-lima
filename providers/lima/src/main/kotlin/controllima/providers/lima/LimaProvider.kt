package controllima.providers.lima

import controllima.providers.process.CommandResult
import controllima.providers.process.CommandRunner
import controllima.providers.process.StreamHandle
import controllima.shared.Names
import controllima.shared.PortForward
import controllima.shared.ProtectedNames
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProviderException(message: String) : RuntimeException(message)

data class ProbeResult(val ok: Boolean, val latencyMs: Double)

data class LimaTemplateRef(val name: String, val location: String)

data class LimaInfo(val version: String?, val templates: List<LimaTemplateRef>)

/** Flags for `limactl create`; values are validated before they reach the command line. */
data class CreateFlags(
    val cpus: Int? = null,
    val memoryGiB: Double? = null,
    val diskGiB: Double? = null,
    val mounts: List<String> = emptyList(),
    val portForwards: List<String> = emptyList(),
    val arch: String? = null,
)

/** Lima VMs. Implementations must time out every call; see docs/plan/architecture.md. */
interface LimaProvider {
    /** `LIMA_HOME` this provider talks to; null means limactl's default. */
    val home: String?

    fun list(): List<Vm>

    /** True when the guest answers a trivial command within [timeout]. */
    fun probe(name: String, timeout: Duration = 3.seconds): Boolean = probeTimed(name, timeout).ok
    fun probeTimed(name: String, timeout: Duration = 3.seconds): ProbeResult = throw UnsupportedOperationException()

    fun start(name: String, onLine: (String) -> Unit = {}): CommandResult = unsupported()
    fun stop(name: String, force: Boolean = false, onLine: (String) -> Unit = {}): CommandResult = unsupported()
    fun delete(name: String, force: Boolean = false): CommandResult = unsupported()
    fun rename(oldName: String, newName: String): CommandResult = unsupported()
    fun clone(oldName: String, newName: String, onLine: (String) -> Unit = {}): CommandResult = unsupported()
    fun info(): LimaInfo = unsupported()
    fun templateYaml(locator: String): String = unsupported()
    fun createCommand(name: String, locator: String, flags: CreateFlags): List<String> = unsupported()
    fun create(name: String, locator: String, flags: CreateFlags, onLine: (String) -> Unit = {}): CommandResult = unsupported()
    fun snapshots(name: String): List<String> = emptyList()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not supported by this LimaProvider")
}

class LimactlProvider(
    private val runner: CommandRunner,
    private val limactl: String = "limactl",
    private val limaHome: String? = null,
    private val extraArgs: List<String> = emptyList(),
    private val protectedNames: () -> Set<String> = { ProtectedNames.defaults },
    private val timeout: Duration = 10.seconds,
) : LimaProvider {
    override val home: String? get() = limaHome

    private val env get() = limaHome?.let { mapOf("LIMA_HOME" to it) } ?: emptyMap()

    private fun cmd(vararg args: String): List<String> = listOf(limactl) + extraArgs + args.toList()

    private fun checked(label: String, result: CommandResult, limit: Duration): CommandResult {
        if (result.timedOut) throw ProviderException("$label timed out after $limit")
        if (!result.ok) throw ProviderException("$label failed (${result.exitCode}): ${result.stderr.trim().lines().lastOrNull().orEmpty()}")
        return result
    }

    private fun validName(name: String): String {
        if (!Names.isValidVmName(name)) throw ProviderException("Invalid VM name: $name")
        return name
    }

    override fun list(): List<Vm> {
        val result = checked("limactl list", runner.run(cmd("list", "--json"), timeout, env), timeout)
        return LimaJson.parseList(result.stdout, protectedNames(), limaHome)
    }

    override fun probeTimed(name: String, timeout: Duration): ProbeResult {
        val started = System.nanoTime()
        val ok = runner.run(cmd("shell", "--workdir", "/", validName(name), "true"), timeout, env).ok
        return ProbeResult(ok, (System.nanoTime() - started) / 1_000_000.0)
    }

    override fun start(name: String, onLine: (String) -> Unit) =
        checked("limactl start", runner.run(cmd("start", "--tty=false", validName(name)), ACTION_TIMEOUT, env, onLine = onLine), ACTION_TIMEOUT)

    override fun stop(name: String, force: Boolean, onLine: (String) -> Unit): CommandResult {
        val args = if (force) arrayOf("stop", "--force", validName(name)) else arrayOf("stop", validName(name))
        return checked("limactl stop", runner.run(cmd(*args), ACTION_TIMEOUT, env, onLine = onLine), ACTION_TIMEOUT)
    }

    override fun delete(name: String, force: Boolean): CommandResult {
        val args = if (force) arrayOf("delete", "--force", "--tty=false", validName(name)) else arrayOf("delete", "--tty=false", validName(name))
        return checked("limactl delete", runner.run(cmd(*args), DELETE_TIMEOUT, env), DELETE_TIMEOUT)
    }

    override fun rename(oldName: String, newName: String) =
        checked("limactl rename", runner.run(cmd("rename", validName(oldName), validName(newName)), timeout, env), timeout)

    override fun clone(oldName: String, newName: String, onLine: (String) -> Unit) =
        checked("limactl clone", runner.run(cmd("clone", "--tty=false", validName(oldName), validName(newName)), ACTION_TIMEOUT, env, onLine = onLine), ACTION_TIMEOUT)

    override fun info(): LimaInfo = LimaJson.parseInfo(checked("limactl info", runner.run(cmd("info"), timeout, env), timeout).stdout)

    override fun templateYaml(locator: String): String {
        requireLocator(locator)
        return checked("limactl template copy", runner.run(cmd("template", "copy", "--fill", locator, "-"), timeout, env), timeout).stdout
    }

    override fun createCommand(name: String, locator: String, flags: CreateFlags): List<String> {
        requireLocator(locator)
        val args = mutableListOf("create", "--tty=false", "--name=${validName(name)}")
        flags.cpus?.let { require(it in 1..256) { "cpus out of range" }; args += "--cpus=$it" }
        flags.memoryGiB?.let { require(it > 0 && it <= 1024) { "memory out of range" }; args += "--memory=$it" }
        flags.diskGiB?.let { require(it > 0 && it <= 65536) { "disk out of range" }; args += "--disk=$it" }
        flags.arch?.let { require(it in setOf("x86_64", "aarch64")) { "unsupported arch $it" }; args += "--arch=$it" }
        flags.mounts.forEach { require(MOUNT.matches(it)) { "invalid mount $it" }; args += "--mount=$it" }
        flags.portForwards.forEach { require(PORT_FORWARD.matches(it)) { "invalid port forward $it" }; args += "--port-forward=$it" }
        args += locator
        return cmd(*args.toTypedArray())
    }

    override fun create(name: String, locator: String, flags: CreateFlags, onLine: (String) -> Unit) =
        checked("limactl create", runner.run(createCommand(name, locator, flags), ACTION_TIMEOUT, env, onLine = onLine), ACTION_TIMEOUT)

    override fun snapshots(name: String): List<String> {
        val result = runner.run(cmd("snapshot", "list", "--quiet", validName(name)), timeout, env)
        // `--quiet` prints one tag per line: an identifier list, not a human-readable table.
        return if (result.ok) result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    private fun requireLocator(locator: String) {
        if (!(locator.startsWith("template://") || locator.startsWith("/")) || locator.any { it.isWhitespace() }) {
            throw ProviderException("Unsupported template locator: $locator")
        }
    }

    companion object {
        val ACTION_TIMEOUT = 5.minutes
        val DELETE_TIMEOUT = 2.minutes
        private val MOUNT = Regex("^/[^\\s,]*(:w)?$")
        private val PORT_FORWARD = Regex("^\\d{1,5}:\\d{1,5}(,static=true)?$")
    }
}

/** Parser for `limactl list --json` (one JSON object per line) and `limactl info`. */
object LimaJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseList(output: String, protectedNames: Set<String> = ProtectedNames.defaults, home: String? = null): List<Vm> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { json.parseToJsonElement(it).jsonObject.toVm(protectedNames, home) }
            .toList()

    fun parseInfo(output: String): LimaInfo {
        val root = json.parseToJsonElement(output).jsonObject
        val templates = (root["templates"] as? JsonArray).orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            LimaTemplateRef(name, o.str("location") ?: "template://$name")
        }
        return LimaInfo(root.str("version"), templates)
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.toVm(protectedNames: Set<String>, home: String?): Vm {
        val name = str("name") ?: throw ProviderException("limactl entry without a name")
        val config = this["config"] as? JsonObject
        val forwards = (config?.get("portForwards") as? JsonArray).orEmpty().mapNotNull { element ->
            val pf = element as? JsonObject ?: return@mapNotNull null
            if (pf.bool("ignore") == true) return@mapNotNull null
            val guest = pf.int("guestPort") ?: return@mapNotNull null
            PortForward(
                guestPort = guest,
                hostPort = pf.int("hostPort") ?: guest,
                hostIp = pf.str("hostIP") ?: "127.0.0.1",
                proto = pf.str("proto") ?: "tcp",
            )
        }
        val mounts = (config?.get("mounts") as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val location = o.str("location") ?: return@mapNotNull null
            val point = o.str("mountPoint") ?: location
            "$location → $point" + if (o.bool("writable") == true) " (rw)" else " (ro)"
        }
        val ips = (this["network"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("ipAddress")?.takeIf(String::isNotBlank) }
        return Vm(
            name = name,
            source = VmSource.LIMA,
            status = statusOf(str("status")),
            arch = str("arch"),
            vmType = str("vmType"),
            cpus = int("cpus"),
            memoryBytes = long("memory"),
            diskBytes = long("disk"),
            sshPort = int("sshLocalPort")?.takeIf { it > 0 },
            ipAddresses = ips,
            portForwards = forwards,
            protected = name in protectedNames || bool("protected") == true,
            home = home,
            dir = str("dir"),
            sshConfigFile = str("sshConfigFile"),
            mounts = mounts,
        )
    }

    fun statusOf(status: String?): VmStatus = when (status) {
        "Running" -> VmStatus.RUNNING
        "Stopped" -> VmStatus.STOPPED
        "Broken" -> VmStatus.BROKEN
        else -> VmStatus.UNKNOWN
    }

    /** Host-side PID of the VM (hostagent for VZ, QEMU otherwise), for host CPU/RSS. */
    fun driverPid(line: String): Long? {
        val o = json.parseToJsonElement(line).jsonObject
        return o.long("driverPID")?.takeIf { it > 0 } ?: o.long("hostAgentPID")?.takeIf { it > 0 }
    }

    /** Guest addresses from `ip -j addr` (JSON), excluding loopback and link-local. */
    fun parseIpAddr(output: String): List<String> =
        runCatching { json.parseToJsonElement(output).jsonArray }.getOrNull().orEmpty().flatMap { iface ->
            val o = iface as? JsonObject ?: return@flatMap emptyList()
            if (o.str("ifname") == "lo") return@flatMap emptyList()
            (o["addr_info"] as? JsonArray).orEmpty().mapNotNull { a ->
                val ai = a as? JsonObject ?: return@mapNotNull null
                val ip = ai.str("local") ?: return@mapNotNull null
                if (ai.str("scope") == "link" || ip.startsWith("fe80")) null else "$ip (${o.str("ifname")})"
            }
        }
}

/**
 * SSH access to a guest through Lima's generated `ssh.config` (both Lima and Colima VMs are Lima
 * instances). One persistent session per VM streams /proc for stats (docs/plan/metrics.md).
 */
class GuestShell(private val runner: CommandRunner, private val ssh: String = "ssh") {
    fun command(sshConfigFile: String, instance: String, remote: String): List<String> {
        if (!Names.isValidVmName(instance)) throw ProviderException("Invalid instance name: $instance")
        if (!File(sshConfigFile).isAbsolute) throw ProviderException("ssh config must be an absolute path")
        return listOf(
            ssh, "-F", sshConfigFile,
            "-o", "BatchMode=yes", "-o", "ConnectTimeout=3",
            "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
            "lima-$instance", "--", remote,
        )
    }

    fun exec(sshConfigFile: String, instance: String, remote: String, timeout: Duration = 5.seconds): CommandResult =
        runner.run(command(sshConfigFile, instance, remote), timeout)

    fun statsStream(sshConfigFile: String, instance: String, intervalSeconds: Int, onSnapshot: (GuestSnapshot) -> Unit, onExit: (Int?) -> Unit): StreamHandle {
        val parser = GuestStatsParser(onSnapshot)
        return runner.stream(command(sshConfigFile, instance, GuestStatsParser.script(intervalSeconds)), onLine = parser::accept, onExit = onExit)
    }

    /** Guest IPs via `ip -j addr`. */
    fun ipAddresses(sshConfigFile: String, instance: String): List<String> {
        val result = exec(sshConfigFile, instance, "ip -j addr")
        return if (result.ok) LimaJson.parseIpAddr(result.stdout) else emptyList()
    }
}
