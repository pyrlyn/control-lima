package controllima.providers.colima

import controllima.providers.process.CommandRunner
import controllima.shared.ProtectedNames
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ColimaException(message: String) : RuntimeException(message)

/** Colima profiles. Every call is bounded by a timeout. */
interface ColimaProvider {
    fun list(): List<Vm>

    /** Path of the Docker socket of [profile], if Colima exposes one. */
    fun dockerSocket(profile: String): String
}

class ColimaCliProvider(
    private val runner: CommandRunner,
    private val colima: String = "colima",
    private val home: String = System.getProperty("user.home"),
    private val extraArgs: List<String> = emptyList(),
    private val protectedNames: Set<String> = ProtectedNames.defaults,
    private val timeout: Duration = 10.seconds,
) : ColimaProvider {
    override fun list(): List<Vm> {
        val result = runner.run(listOf(colima) + extraArgs + listOf("list", "--json"), timeout)
        if (result.timedOut) throw ColimaException("colima list timed out after $timeout")
        if (!result.ok) throw ColimaException("colima list failed (${result.exitCode}): ${result.stderr.trim()}")
        return ColimaJson.parseList(result.stdout, protectedNames)
    }

    override fun dockerSocket(profile: String): String = "$home/.colima/$profile/docker.sock"
}

/** Parser for `colima list --json`: one JSON object per line. */
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
}
