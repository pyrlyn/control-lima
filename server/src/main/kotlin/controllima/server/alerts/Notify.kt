package controllima.server.alerts

import controllima.providers.host.HostProvider
import controllima.providers.host.SecretStore
import controllima.server.writePrivate
import controllima.shared.ApiJson
import controllima.shared.ChannelKind
import controllima.shared.NotificationChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

/** Outgoing HTTP for notifications; the Ktor client sits behind it (see KtorHttpPoster). */
interface HttpPoster {
    /** POSTs [json] and returns the HTTP status. */
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Int
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String>
}

/** Sends one notification through one channel. */
interface Notifier {
    suspend fun send(channel: NotificationChannel, title: String, message: String): Result<Unit>
}

class ChannelNotifier(
    private val host: HostProvider,
    private val http: HttpPoster,
    private val secrets: SecretStore,
) : Notifier {
    override suspend fun send(channel: NotificationChannel, title: String, message: String): Result<Unit> = runCatching {
        val secret = withContext(Dispatchers.IO) { secrets.get(ChannelStore.secretKey(channel.id)) }
        when (channel.kind) {
            ChannelKind.DESKTOP -> {
                val ok = withContext(Dispatchers.IO) { host.notify(title, message) }
                check(ok) { "desktop notification failed (osascript/notify-send missing or blocked)" }
            }
            ChannelKind.WEBHOOK -> {
                val url = channel.target ?: error("webhook URL missing")
                val body = buildJsonObject { put("title", title); put("message", message); put("source", "control-lima") }.toString()
                val headers = secret?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
                expectOk(http.postJson(url, body, headers))
            }
            ChannelKind.TELEGRAM -> {
                val token = secret ?: error("Telegram bot token missing")
                val chat = channel.target ?: error("Telegram chat id missing")
                val body = buildJsonObject { put("chat_id", chat); put("text", "$title\n$message") }.toString()
                expectOk(http.postJson("https://api.telegram.org/bot$token/sendMessage", body))
            }
            ChannelKind.SLACK -> {
                val url = secret ?: error("Slack webhook URL missing")
                val body = buildJsonObject { put("text", "*$title*\n$message") }.toString()
                expectOk(http.postJson(url, body))
            }
        }
    }

    private fun expectOk(status: Int) = check(status in 200..299) { "HTTP $status" }
}

/**
 * Notification channels without their secrets (`alerts/channels.json`); secrets live in the
 * [SecretStore] and are write-only in the API (docs/plan/alerts.md).
 */
class ChannelStore(private val file: File, private val secrets: SecretStore) {
    private val serializer = ListSerializer(NotificationChannel.serializer())
    private var channels: List<NotificationChannel> = load()

    private fun load(): List<NotificationChannel> =
        if (!file.isFile) listOf(NotificationChannel("desktop", ChannelKind.DESKTOP))
        else runCatching { ApiJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun list(): List<NotificationChannel> = channels.map { it.copy(secret = null) }

    @Synchronized
    fun get(id: String): NotificationChannel? = channels.firstOrNull { it.id == id }

    /** Replaces all channels. A null [NotificationChannel.secret] keeps the stored one; "" deletes it. */
    @Synchronized
    fun replace(incoming: List<NotificationChannel>): List<NotificationChannel> {
        incoming.forEach(::validate)
        require(incoming.map { it.id }.toSet().size == incoming.size) { "Channel ids must be unique" }
        val result = incoming.map { c ->
            val secret = c.secret
            when {
                secret == null -> c.copy(hasSecret = secrets.get(secretKey(c.id)) != null)
                secret.isEmpty() -> { secrets.delete(secretKey(c.id)); c.copy(secret = null, hasSecret = false) }
                else -> { secrets.put(secretKey(c.id), secret); c.copy(secret = null, hasSecret = true) }
            }
        }
        channels.filter { old -> result.none { it.id == old.id } }.forEach { runCatching { secrets.delete(secretKey(it.id)) } }
        channels = result
        save()
        return list()
    }

    @Synchronized
    fun recordResult(id: String, result: String) {
        channels = channels.map { if (it.id == id) it.copy(lastResult = result.take(200)) else it }
        runCatching { save() }
    }

    private fun save() = writePrivate(file, ApiJson.encodeToString(serializer, channels.map { it.copy(secret = null) }))

    companion object {
        private val ID = Regex("^[a-zA-Z0-9_.-]{1,64}$")
        private val TELEGRAM_CHAT = Regex("^(-?\\d{1,20}|@[A-Za-z0-9_]{5,64})$")
        private val TELEGRAM_TOKEN = Regex("^\\d{1,20}:[A-Za-z0-9_-]{20,100}$")

        fun secretKey(id: String) = "channel.$id"

        fun validate(c: NotificationChannel) {
            require(ID.matches(c.id)) { "Invalid channel id: ${c.id}" }
            val target = c.target
            val secret = c.secret
            when (c.kind) {
                ChannelKind.DESKTOP -> Unit
                ChannelKind.WEBHOOK -> require(target != null && isHttpUrl(target)) { "Webhook needs an http(s) URL" }
                ChannelKind.TELEGRAM -> {
                    require(target != null && TELEGRAM_CHAT.matches(target)) { "Telegram needs a chat id" }
                    require(secret.isNullOrEmpty() || TELEGRAM_TOKEN.matches(secret)) { "Telegram bot token looks wrong" }
                }
                ChannelKind.SLACK -> require(secret.isNullOrEmpty() || (isHttpUrl(secret) && secret.startsWith("https://"))) { "Slack needs an https incoming-webhook URL" }
            }
        }

        fun isHttpUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null
        }.getOrDefault(false)
    }
}
