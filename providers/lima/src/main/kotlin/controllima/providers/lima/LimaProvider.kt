package controllima.providers.lima

import controllima.providers.process.CommandRunner
import controllima.shared.PortForward
import controllima.shared.ProtectedNames
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ProviderException(message: String) : RuntimeException(message)

/** Lima VMs. Implementations must time out every call; see docs/plan/architecture.md. */
interface LimaProvider {
    fun list(): List<Vm>

    /** True when the guest answers a trivial command within [timeout]. */
    fun probe(name: String, timeout: Duration = 3.seconds): Boolean
}

class LimactlProvider(
    private val runner: CommandRunner,
    private val limactl: String = "limactl",
    private val limaHome: String? = null,
    private val extraArgs: List<String> = emptyList(),
    private val protectedNames: Set<String> = ProtectedNames.defaults,
    private val timeout: Duration = 10.seconds,
) : LimaProvider {
    private val env get() = limaHome?.let { mapOf("LIMA_HOME" to it) } ?: emptyMap()

    override fun list(): List<Vm> {
        val result = runner.run(listOf(limactl) + extraArgs + listOf("list", "--json"), timeout, env)
        if (result.timedOut) throw ProviderException("limactl list timed out after $timeout")
        if (!result.ok) throw ProviderException("limactl list failed (${result.exitCode}): ${result.stderr.trim()}")
        return LimaJson.parseList(result.stdout, protectedNames)
    }

    override fun probe(name: String, timeout: Duration): Boolean =
        runner.run(listOf(limactl) + extraArgs + listOf("shell", "--workdir", "/", name, "true"), timeout, env).ok
}

/** Parser for `limactl list --json`: one JSON object per line. */
object LimaJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseList(output: String, protectedNames: Set<String> = ProtectedNames.defaults): List<Vm> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { json.parseToJsonElement(it).jsonObject.toVm(protectedNames) }
            .toList()

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.toVm(protectedNames: Set<String>): Vm {
        val name = str("name") ?: throw ProviderException("limactl entry without a name")
        val config = this["config"] as? JsonObject
        val forwards = (config?.get("portForwards") as? JsonArray).orEmpty().mapNotNull { element ->
            val pf = element as? JsonObject ?: return@mapNotNull null
            val guest = pf.int("guestPort") ?: return@mapNotNull null
            PortForward(
                guestPort = guest,
                hostPort = pf.int("hostPort") ?: guest,
                hostIp = pf.str("hostIP") ?: "127.0.0.1",
                proto = pf.str("proto") ?: "tcp",
            )
        }
        val ips = (this["network"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("ipAddress") }
        return Vm(
            name = name,
            source = VmSource.LIMA,
            status = when (str("status")) {
                "Running" -> VmStatus.RUNNING
                "Stopped" -> VmStatus.STOPPED
                "Broken" -> VmStatus.BROKEN
                else -> VmStatus.UNKNOWN
            },
            arch = str("arch"),
            vmType = str("vmType"),
            cpus = int("cpus"),
            memoryBytes = long("memory"),
            diskBytes = long("disk"),
            sshPort = int("sshLocalPort")?.takeIf { it > 0 },
            ipAddresses = ips,
            portForwards = forwards,
            protected = name in protectedNames,
        )
    }
}
