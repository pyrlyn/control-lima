package controllima.providers.host

import controllima.providers.process.CommandRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

/** Notification-channel secrets. Never logged, never returned by the API (docs/plan/alerts.md). */
interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun delete(key: String)
}

/**
 * macOS Keychain through the `security` CLI. Secrets go through `security -i` on stdin, so they
 * never show up in the process list.
 */
class KeychainSecretStore(private val runner: CommandRunner, private val service: String = "control-lima") : SecretStore {
    override fun put(key: String, value: String) {
        checkKey(key)
        require(value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' }) { "secret contains characters the Keychain CLI cannot quote" }
        val result = runner.run(listOf("security", "-i"), 10.seconds, stdin = "add-generic-password -U -a \"$key\" -s \"$service\" -w \"$value\"\n")
        if (!result.ok) throw HostException("Keychain write failed (${result.exitCode})")
    }

    override fun get(key: String): String? {
        checkKey(key)
        val result = runner.run(listOf("security", "find-generic-password", "-a", key, "-s", service, "-w"), 10.seconds)
        return if (result.ok) result.stdout.trimEnd('\n') else null
    }

    override fun delete(key: String) {
        checkKey(key)
        runner.run(listOf("security", "delete-generic-password", "-a", key, "-s", service), 10.seconds)
    }

    private fun checkKey(key: String) = require(KEY.matches(key)) { "invalid secret key" }

    companion object {
        private val KEY = Regex("^[a-zA-Z0-9_.-]{1,64}$")
    }
}

/** Linux (and fallback) store: a properties file with mode 0600 in the data dir. */
class FileSecretStore(private val file: File) : SecretStore {
    private val lock = Any()

    override fun put(key: String, value: String) = synchronized(lock) {
        val props = load()
        props[key] = value
        save(props)
    }

    override fun get(key: String): String? = synchronized(lock) { load().getProperty(key) }

    override fun delete(key: String) = synchronized(lock) {
        val props = load()
        props.remove(key)
        save(props)
    }

    private fun load(): Properties = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun save(props: Properties) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
