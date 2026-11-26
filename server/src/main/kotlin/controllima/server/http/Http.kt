package controllima.server.http

import controllima.shared.ApiError
import controllima.shared.ApiJson
import kotlinx.serialization.KSerializer

/** Framework-free HTTP request. Header names are case-insensitive. */
class Request(
    val method: String,
    val path: String,
    val query: Map<String, List<String>> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    val bytes: ByteArray = ByteArray(0),
) {
    private val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    /** Path parameters filled in by [Router]. */
    var params: Map<String, String> = emptyMap()
        internal set

    val body: String get() = bytes.decodeToString()

    fun header(name: String): String? = headers[name.lowercase()]

    fun param(name: String): String? = query[name]?.firstOrNull()

    fun path(name: String): String = params.getValue(name)

    val cookies: Map<String, String> by lazy {
        header("Cookie").orEmpty().split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
        }.toMap()
    }

    val isWrite: Boolean get() = method != "GET" && method != "HEAD" && method != "OPTIONS"
}

class Response(
    val status: Int,
    val bytes: ByteArray,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
) {
    val body: String get() = bytes.decodeToString()

    fun withHeaders(extra: Map<String, String>) = Response(status, bytes, contentType, headers + extra)

    companion object {
        fun text(status: Int, body: String, contentType: String = "application/json") = Response(status, body.encodeToByteArray(), contentType)

        fun <T> json(value: T, serializer: KSerializer<T>, status: Int = 200) = text(status, ApiJson.encodeToString(serializer, value))

        fun error(status: Int, code: String, message: String) = json(ApiError(code, message), ApiError.serializer(), status)

        fun noContent() = Response(204, ByteArray(0))
    }
}

/** Thrown by handlers to answer with an API error; [Router] turns it into a [Response]. */
class HttpError(val status: Int, val code: String, override val message: String) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw HttpError(400, "bad_request", message)
fun notFound(message: String): Nothing = throw HttpError(404, "not_found", message)
fun conflict(message: String): Nothing = throw HttpError(409, "conflict", message)
fun forbidden(message: String): Nothing = throw HttpError(403, "forbidden", message)

/** Text channel of one WebSocket connection. [receive] returns null once the peer closed. */
interface WsChannel {
    suspend fun send(text: String)
    suspend fun receive(): String?
    suspend fun close(reason: String = "")
}

/** What the app answers to a WebSocket upgrade request: a refusal, or the session body. */
sealed interface Upgrade {
    class Refused(val response: Response) : Upgrade
    class Accepted(val session: suspend (WsChannel) -> Unit) : Upgrade
}

/** The application seen by an [HttpServer]: plain requests and WebSocket upgrades. */
interface HttpApp {
    suspend fun handle(request: Request): Response
    suspend fun upgrade(request: Request): Upgrade
    fun isWebSocketPath(path: String): Boolean
}

/** Our HTTP server abstraction. The rest of the app never imports Ktor. */
interface HttpServer {
    val port: Int
    fun start(wait: Boolean = false)
    fun stop()
}

/** Minimal router with `{param}` segments. Matching is exact per segment; params are percent-decoded. */
class Router<R> {
    private class Route<R>(val method: String, val segments: List<String>, val value: R)

    private val routes = mutableListOf<Route<R>>()

    fun add(method: String, pattern: String, value: R) {
        routes += Route(method, split(pattern), value)
    }

    /** The matching route value and its params, or null; `405` is reported as an empty match list. */
    fun match(method: String, path: String): Match<R>? {
        val parts = split(path)
        var pathMatched = false
        for (route in routes) {
            val params = matchSegments(route.segments, parts) ?: continue
            pathMatched = true
            if (route.method == method) return Match(route.value, params, true)
        }
        return if (pathMatched) Match(null, emptyMap(), false) else null
    }

    class Match<R>(val value: R?, val params: Map<String, String>, val methodAllowed: Boolean)

    private fun matchSegments(pattern: List<String>, parts: List<String>): Map<String, String>? {
        if (pattern.size != parts.size) return null
        val params = mutableMapOf<String, String>()
        for (i in pattern.indices) {
            val p = pattern[i]
            if (p.startsWith("{") && p.endsWith("}")) params[p.substring(1, p.length - 1)] = decode(parts[i] ) ?: return null
            else if (p != parts[i]) return null
        }
        return params
    }

    private fun split(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }

    companion object {
        fun decode(value: String): String? = runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()
    }
}
