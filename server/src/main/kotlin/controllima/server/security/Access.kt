package controllima.server.security

import controllima.server.ServerConfig
import controllima.server.http.Request
import controllima.server.writePrivate
import controllima.shared.ApiJson
import controllima.shared.ApiRoutes
import controllima.shared.CreatedToken
import controllima.shared.Role
import controllima.shared.TokenInfo
import controllima.shared.UserInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Who is calling. [key] binds the CSRF token to this credential. */
data class Principal(val actor: String, val role: Role, val key: String, val via: Via) {
    enum class Via { LAUNCH_TOKEN, NAMED_TOKEN, SESSION }
}

object Secrets {
    private val random = SecureRandom()

    fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).toHex()

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHex()

    fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())

    fun hmac(key: ByteArray, text: String): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.encodeToByteArray()).toHex()

    fun pbkdf2(password: String, salt: String, iterations: Int): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.encodeToByteArray(), iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Named tokens with roles, stored hashed (SHA-256 + per-token salt). Token format: `clt_<id>_<secret>`. */
class TokenStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Stored(
        val id: String,
        val name: String,
        val role: Role,
        val salt: String,
        val hash: String,
        val createdMillis: Long,
        val expiresMillis: Long? = null,
        val lastUsedMillis: Long? = null,
    ) {
        fun info() = TokenInfo(id, name, role, createdMillis, expiresMillis, lastUsedMillis)
    }

    private val serializer = ListSerializer(Stored.serializer())
    private var tokens: List<Stored> = load()

    private fun load(): List<Stored> =
        if (!file.isFile) emptyList() else runCatching { ApiJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun save() = writePrivate(file, ApiJson.encodeToString(serializer, tokens))

    @Synchronized
    fun list(): List<TokenInfo> = tokens.map { it.info() }

    @Synchronized
    fun create(name: String, role: Role, expiresInDays: Int?): CreatedToken {
        require(NAME.matches(name)) { "Token name must be 1-64 letters, digits, '-', '_' or '.'" }
        require(tokens.none { it.name == name }) { "A token named $name already exists" }
        require(expiresInDays == null || expiresInDays in 1..3650) { "Expiry must be 1..3650 days" }
        val id = Secrets.randomHex(6)
        val secret = Secrets.randomHex(24)
        val salt = Secrets.randomHex(16)
        val now = clock()
        val stored = Stored(id, name, role, salt, Secrets.sha256(salt + secret), now, expiresInDays?.let { now + it * 86_400_000L })
        tokens = tokens + stored
        save()
        return CreatedToken(stored.info(), "clt_${id}_$secret")
    }

    @Synchronized
    fun revoke(idOrName: String): Boolean {
        val before = tokens.size
        tokens = tokens.filterNot { it.id == idOrName || it.name == idOrName }
        if (tokens.size != before) save()
        return tokens.size != before
    }

    @Synchronized
    fun verify(token: String): TokenInfo? {
        if (!token.startsWith("clt_")) return null
        val parts = token.split('_')
        if (parts.size != 3) return null
        val stored = tokens.firstOrNull { it.id == parts[1] } ?: return null
        if (!Secrets.constantTimeEquals(Secrets.sha256(stored.salt + parts[2]), stored.hash)) return null
        val now = clock()
        if (stored.expiresMillis != null && now > stored.expiresMillis) return null
        // Persist last-used at most once a minute: it is informational and writes are not free.
        if (stored.lastUsedMillis == null || now - stored.lastUsedMillis > 60_000) {
            tokens = tokens.map { if (it.id == stored.id) it.copy(lastUsedMillis = now) else it }
            runCatching { save() }
        }
        return stored.info()
    }

    companion object {
        private val NAME = Regex("^[a-zA-Z0-9_.-]{1,64}$")
    }
}

/** Optional local accounts (multi-user mode), passwords hashed with PBKDF2 from the JDK. */
class UserStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Stored(val username: String, val role: Role, val salt: String, val hash: String, val iterations: Int, val createdMillis: Long)

    private val serializer = ListSerializer(Stored.serializer())
    private var users: List<Stored> = if (!file.isFile) emptyList() else runCatching { ApiJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun save() = writePrivate(file, ApiJson.encodeToString(serializer, users))

    @Synchronized
    fun list(): List<UserInfo> = users.map { UserInfo(it.username, it.role, it.createdMillis) }

    @Synchronized
    fun create(username: String, password: String, role: Role): UserInfo {
        require(NAME.matches(username)) { "Username must be 1-32 letters, digits, '-', '_' or '.'" }
        require(password.length >= 10) { "Password must be at least 10 characters" }
        require(users.none { it.username == username }) { "User $username already exists" }
        val salt = Secrets.randomHex(16)
        val stored = Stored(username, role, salt, Secrets.pbkdf2(password, salt, ITERATIONS), ITERATIONS, clock())
        users = users + stored
        save()
        return UserInfo(username, role, stored.createdMillis)
    }

    @Synchronized
    fun delete(username: String): Boolean {
        val before = users.size
        users = users.filterNot { it.username == username }
        if (users.size != before) save()
        return users.size != before
    }

    fun verify(username: String, password: String): UserInfo? {
        val stored = synchronized(this) { users.firstOrNull { it.username == username } }
        // Hash even for unknown users so timing does not reveal which usernames exist.
        val hash = Secrets.pbkdf2(password, stored?.salt ?: "unknown-user-salt", stored?.iterations ?: ITERATIONS)
        if (stored == null || !Secrets.constantTimeEquals(hash, stored.hash)) return null
        return UserInfo(stored.username, stored.role, stored.createdMillis)
    }

    companion object {
        private val NAME = Regex("^[a-zA-Z0-9_.-]{1,32}$")
        const val ITERATIONS = 210_000
    }
}

/**
 * Authentication and request checks from docs/plan/security.md: Host and Origin, token or session cookie,
 * and an HMAC CSRF token bound to the credential.
 */
class AccessControl(
    private val config: ServerConfig,
    val tokens: TokenStore,
    val users: UserStore,
    private val multiUser: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val csrfKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val sessions = ConcurrentHashMap<String, Pair<UserInfo, Long>>()
    private val failedLogins = ConcurrentHashMap<String, MutableList<Long>>()

    /** Null when Host and Origin are acceptable; otherwise the reason. */
    fun checkHostAndOrigin(request: Request): String? {
        val host = request.header("Host")
        if (host != null && host !in config.allowedHosts) return "Host $host is not allowed"
        val origin = request.header("Origin")
        if (origin != null && origin !in config.allowedOrigins) return "Origin $origin is not allowed"
        return null
    }

    fun authenticate(request: Request): Principal? {
        val header = request.header(ApiRoutes.TOKEN_HEADER)
        // Browsers cannot set headers on WebSocket upgrades, so ?token= is accepted there only.
        val token = header ?: request.param("token")?.takeIf { request.path.startsWith("/ws/") && request.method == "GET" }
        if (token != null) {
            if (Secrets.constantTimeEquals(token, config.token)) return Principal("owner", Role.ADMIN, "launch", Principal.Via.LAUNCH_TOKEN)
            tokens.verify(token)?.let { return Principal("token:${it.name}", it.role, "token:${it.id}", Principal.Via.NAMED_TOKEN) }
            return null
        }
        if (multiUser()) {
            val sid = request.cookies[ApiRoutes.SESSION_COOKIE] ?: return null
            val (user, expires) = sessions[sid] ?: return null
            if (clock() > expires) {
                sessions.remove(sid)
                return null
            }
            // A deleted or demoted user loses access at once.
            val current = users.list().firstOrNull { it.username == user.username } ?: return null.also { sessions.remove(sid) }
            return Principal(current.username, current.role, "session:${Secrets.sha256(sid)}", Principal.Via.SESSION)
        }
        return null
    }

    fun csrfToken(principal: Principal): String = Secrets.hmac(csrfKey, principal.key)

    fun checkCsrf(request: Request, principal: Principal): Boolean {
        val sent = request.header(ApiRoutes.CSRF_HEADER) ?: return false
        return Secrets.constantTimeEquals(sent, csrfToken(principal))
    }

    /** Login for multi-user mode. Returns the session id, or null; throws when locked out. */
    fun login(username: String, password: String): Pair<String, UserInfo>? {
        val now = clock()
        val recent = failedLogins.getOrPut(username) { mutableListOf() }
        synchronized(recent) {
            recent.removeAll { now - it > LOCKOUT_MILLIS }
            if (recent.size >= MAX_FAILED) throw LockedOut()
        }
        val user = users.verify(username, password)
        if (user == null) {
            synchronized(recent) { recent += now }
            return null
        }
        failedLogins.remove(username)
        val sid = Secrets.randomHex(32)
        sessions[sid] = user to now + SESSION_MILLIS
        return sid to user
    }

    fun logout(request: Request) {
        request.cookies[ApiRoutes.SESSION_COOKIE]?.let { sessions.remove(it) }
    }

    fun sessionCookie(sid: String?): String =
        if (sid == null) "${ApiRoutes.SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
        else "${ApiRoutes.SESSION_COOKIE}=$sid; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_MILLIS / 1000}"

    class LockedOut : RuntimeException("Too many failed logins; try again in 5 minutes")

    companion object {
        const val SESSION_MILLIS = 12 * 3_600_000L
        const val LOCKOUT_MILLIS = 5 * 60_000L
        const val MAX_FAILED = 5
    }
}
