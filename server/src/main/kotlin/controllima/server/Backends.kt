package controllima.server

import controllima.providers.colima.ColimaCliProvider
import controllima.providers.colima.ColimaProvider
import controllima.providers.docker.DockerEngineProvider
import controllima.providers.docker.DockerProvider
import controllima.providers.docker.UnixSocketTransport
import controllima.providers.host.HostProvider
import controllima.providers.lima.GuestShell
import controllima.providers.lima.LimaProvider
import controllima.providers.lima.LimactlProvider
import controllima.providers.process.CommandRunner
import controllima.shared.Settings
import controllima.shared.Vm
import controllima.shared.VmSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** CLI flags that override settings for one launch (docs/plan/settings.md). */
data class Overrides(
    val limaHome: String? = null,
    val colimaProfile: String? = null,
    val passthrough: List<String> = emptyList(),
)

/** Every external system the server talks to, built from settings. Tests pass a fake. */
interface Backends {
    /** One provider per configured `LIMA_HOME`; the first is the default home. */
    fun limaProviders(): List<LimaProvider>
    fun colima(): ColimaProvider
    fun colimaProfiles(): List<String>
    fun defaultProfile(): String
    /** Null when the profile's Docker socket does not exist (Colima stopped). */
    fun docker(profile: String): DockerProvider?
    fun dockerSocket(profile: String): String
    val host: HostProvider
    val guest: GuestShell
    val runner: CommandRunner

    fun lima(home: String?): LimaProvider? = limaProviders().firstOrNull { it.home == home }

    /** The Lima instance behind [vm]; Colima names its instances `colima[-<profile>]`. */
    fun limaInstance(vm: Vm): String = if (vm.source == VmSource.COLIMA) colima().limaInstance(vm.name) else vm.name
}

class LocalBackends(
    override val runner: CommandRunner,
    override val host: HostProvider,
    private val settings: () -> Settings,
    private val overrides: Overrides,
    private val homeDir: String = System.getProperty("user.home"),
) : Backends {
    override val guest = GuestShell(runner)
    private val dockerCache = ConcurrentHashMap<String, DockerProvider>()

    @Volatile private var built: Pair<Settings, Built>? = null
    @Volatile private var discovered: Pair<Long, List<String>>? = null

    private class Built(val lima: List<LimaProvider>, val colima: ColimaProvider)

    private fun built(): Built {
        val s = settings()
        built?.let { (cached, value) -> if (cached === s) return value }
        val protectedNames = { settings().protectedNames.toSet() }
        val limaArgs = s.limaArgs + overrides.passthrough
        val defaultHome = overrides.limaHome
        val envHome = System.getenv("LIMA_HOME")
        val defaultPath = defaultHome ?: envHome ?: "$homeDir/.lima"
        val extraHomes = s.limaHomes.filter { File(it).canonicalPath != File(defaultPath).canonicalPath }.distinct()
        val lima = (listOf(defaultHome) + extraHomes).map { home ->
            LimactlProvider(runner, s.limactlPath, home, limaArgs, protectedNames)
        }
        val colima = ColimaCliProvider(runner, s.colimaPath, homeDir, s.colimaArgs + overrides.passthrough, protectedNames)
        return Built(lima, colima).also { built = s to it }
    }

    override fun limaProviders() = built().lima
    override fun colima() = built().colima

    override fun defaultProfile(): String = overrides.colimaProfile ?: settings().defaultProfile

    override fun colimaProfiles(): List<String> {
        val configured = settings().colimaProfiles
        val now = System.currentTimeMillis()
        val auto = if (configured.isNotEmpty()) emptyList() else {
            discovered?.takeIf { now - it.first < 30_000 }?.second
                ?: runCatching { colima().discoverProfiles() }.getOrDefault(emptyList()).also { discovered = now to it }
        }
        return (listOf(defaultProfile()) + configured + auto).distinct()
    }

    override fun dockerSocket(profile: String): String =
        settings().dockerSocketOverrides[profile] ?: colima().dockerSocket(profile)

    override fun docker(profile: String): DockerProvider? {
        val socket = dockerSocket(profile)
        if (!File(socket).exists()) return null
        return dockerCache.getOrPut("$profile|$socket") { DockerEngineProvider(UnixSocketTransport(socket), profile) }
    }
}
