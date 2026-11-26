package controllima.server

import controllima.shared.ApiJson
import controllima.shared.BuildInfo
import controllima.shared.Names
import controllima.shared.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8787,
    /** Random per launch; required on every /api call (header, or ?token= for page loads and WebSockets). */
    val token: String,
    val version: String = BuildInfo.VERSION,
) {
    init {
        require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
            "ControlLima listens on localhost only (got $host); see docs/plan/security.md"
        }
        require(port in 1..65535) { "port must be 1..65535" }
        require(token.length >= 16) { "token too short" }
    }

    val allowedOrigins: Set<String> get() = setOf("http://127.0.0.1:$port", "http://localhost:$port", "http://[::1]:$port")

    /** Host header values accepted (DNS rebinding guard). Port 80 may be omitted by clients. */
    val allowedHosts: Set<String>
        get() = buildSet {
            for (h in listOf("127.0.0.1", "localhost", "[::1]")) {
                add("$h:$port")
                if (port == 80) add(h)
            }
        }
}

/** Where ControlLima keeps its files (docs/plan/architecture.md, "Configuration and data dirs"). */
class DataPaths(val configDir: File, val dataDir: File) {
    val configFile get() = File(configDir, "config.json")
    val auditLog get() = File(dataDir, "audit.log")
    val tokens get() = File(dataDir, "tokens.json")
    val users get() = File(dataDir, "users.json")
    val metrics get() = File(dataDir, "metrics")
    val metricsDb get() = File(dataDir, "metrics.db")
    val backups get() = File(dataDir, "backups")
    val templates get() = File(dataDir, "templates")
    val alerts get() = File(dataDir, "alerts")
    val secrets get() = File(dataDir, "secrets.properties")
    val tmp get() = File(dataDir, "tmp")
    val serverLog get() = File(dataDir, "control-lima.log")

    fun ensure() {
        for (dir in listOf(configDir, dataDir, metrics, backups, templates, alerts, tmp)) privateDir(dir)
    }

    companion object {
        fun default(home: String = System.getProperty("user.home"), dataDirOverride: String? = null): DataPaths {
            val xdgConfig = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.config"
            val xdgData = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/share"
            val data = dataDirOverride?.let { File(it) } ?: File(xdgData, "control-lima")
            val config = if (dataDirOverride != null) File(data, "config") else File(xdgConfig, "control-lima")
            return DataPaths(config, data)
        }
    }
}

/** Data dir permissions are 0700 and files 0600 (docs/plan/security.md). */
fun privateDir(dir: File) {
    dir.mkdirs()
    runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
}

/** Writes [text] to [file] through a temp file and an atomic move, mode 0600. */
fun writePrivate(file: File, bytes: ByteArray) {
    file.parentFile?.let(::privateDir)
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    tmp.writeBytes(bytes)
    runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

fun writePrivate(file: File, text: String) = writePrivate(file, text.encodeToByteArray())

/** `config.json`, validated on every write. CLI flags override it for one launch (see [Overrides]). */
class SettingsStore(private val file: File) {
    private val listeners = mutableListOf<(Settings, Settings) -> Unit>()

    @Volatile
    var current: Settings = load()
        private set

    private fun load(): Settings {
        if (!file.isFile) return Settings()
        return runCatching { ApiJson.decodeFromString(Settings.serializer(), file.readText()) }
            .getOrElse { Settings() }
            .let { s -> runCatching { validate(s) }.fold({ s }, { Settings() }) }
    }

    fun onChange(listener: (old: Settings, new: Settings) -> Unit) = synchronized(listeners) { listeners += listener }

    fun update(settings: Settings): Settings {
        validate(settings)
        val old: Settings
        synchronized(this) {
            old = current
            writePrivate(file, ApiJson.encodeToString(Settings.serializer(), settings))
            current = settings
        }
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(old, settings) } }
        return settings
    }

    companion object {
        private val BINARY_NAMES = setOf("limactl", "colima")

        /** Throws [IllegalArgumentException] with a user-facing message. */
        fun validate(s: Settings) {
            s.limaHomes.forEach { require(File(it).isAbsolute) { "Lima home must be an absolute path: $it" } }
            s.colimaProfiles.forEach { require(Names.isValidVmName(it)) { "Invalid Colima profile name: $it" } }
            require(Names.isValidVmName(s.defaultProfile)) { "Invalid default profile" }
            // Binary paths are executed, so only the real tools are accepted: a bare name or an absolute path to a file with that name.
            checkBinary(s.limactlPath, "limactl")
            checkBinary(s.colimaPath, "colima")
            s.dockerSocketOverrides.forEach { (profile, path) ->
                require(Names.isValidVmName(profile)) { "Invalid profile in socket overrides: $profile" }
                require(File(path).isAbsolute && path.endsWith(".sock")) { "Docker socket override must be an absolute *.sock path" }
            }
            (s.limaArgs + s.colimaArgs).forEach { require(it.startsWith("-") && '\n' !in it) { "Passthrough args must be flags: $it" } }
            require(s.liveIntervalSeconds in 1..60) { "Live interval must be 1..60 s" }
            require(s.backgroundIntervalSeconds in 5..600) { "Background interval must be 5..600 s" }
            require(s.probeTimeoutSeconds in 1..30) { "Probe timeout must be 1..30 s" }
            s.protectedNames.forEach { require(it.isNotBlank() && it.length <= 128) { "Invalid protected name" } }
            require(s.retentionRawHours in 1..168) { "Raw retention must be 1..168 h" }
            require(s.retention1mDays in 1..90 && s.retention15mDays in 1..365 && s.retention1hDays in 1..3650) { "Retention out of range" }
            require(s.backupSchedule.everyDays in 1..30 && s.backupSchedule.keep in 1..100) { "Backup schedule out of range" }
            require(s.remoteHosts.size <= RemoteHostsService.MAX_HOSTS) { "At most ${RemoteHostsService.MAX_HOSTS} remote hosts" }
            require(s.remoteHosts.map { it.name }.toSet().size == s.remoteHosts.size) { "Remote host names must be unique" }
            s.remoteHosts.forEach { h ->
                require(Names.isValidVmName(h.name)) { "Invalid remote host name: ${h.name}" }
                require(Names.isValidSshTarget(h.ssh)) { "Invalid ssh target for ${h.name}: use host, user@host or an ssh config alias" }
            }
            s.redactionPatterns.forEach { p -> require(runCatching { Regex(p) }.isSuccess) { "Invalid redaction pattern: $p" } }
        }

        private fun checkBinary(path: String, expected: String) {
            require(path == expected || (File(path).isAbsolute && File(path).name == expected && expected in BINARY_NAMES)) {
                "$expected path must be '$expected' or an absolute path ending in /$expected"
            }
        }
    }
}
