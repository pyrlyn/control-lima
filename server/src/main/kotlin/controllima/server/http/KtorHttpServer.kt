package controllima.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketUpgrade
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlin.time.Duration.Companion.seconds

/** The Ktor-backed [HttpServer]; the only file that knows Ktor's server API. */
class KtorHttpServer(
    private val host: String,
    override val port: Int,
    private val app: HttpApp,
) : HttpServer {
    private var engine: EmbeddedServer<*, *>? = null

    override fun start(wait: Boolean) {
        val server = embeddedServer(Netty, port = port, host = host) { controlLima(app) }
        engine = server
        server.start(wait = wait)
    }

    override fun stop() {
        engine?.stop(500, 2_000)
        engine = null
    }
}

/** Ktor wiring for [HttpApp]; public so tests can mount it on the Ktor test host. */
fun Application.controlLima(app: HttpApp) {
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
        maxFrameSize = 1L shl 20
    }
    routing {
        route("{...}") {
            handle {
                val request = call.toRequest()
                if (request.method == "GET" && app.isWebSocketPath(request.path) && call.request.headers["Upgrade"].equals("websocket", ignoreCase = true)) {
                    when (val upgrade = app.upgrade(request)) {
                        is Upgrade.Refused -> call.reply(upgrade.response)
                        is Upgrade.Accepted -> call.respond(WebSocketUpgrade(call, null, false) { KtorWsChannel(this).use(upgrade.session) })
                    }
                } else {
                    call.reply(app.handle(request))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.toRequest(): Request {
    val headers = request.headers.entries().associate { (k, v) -> k to v.joinToString(",") }
    val query = request.queryParameters.entries().associate { it.key to it.value }
    val method = request.httpMethod.value
    val bytes = if (method == "GET" || method == "HEAD") ByteArray(0) else runCatching { receive<ByteArray>() }.getOrDefault(ByteArray(0))
    return Request(method, request.path(), query, headers, bytes)
}

private suspend fun ApplicationCall.reply(response: Response) {
    // Ktor owns Content-Type and Content-Length; everything else is copied as-is.
    for ((name, value) in response.headers) {
        if (!name.equals("Content-Type", true) && !name.equals("Content-Length", true)) this.response.headers.append(name, value, safeOnly = false)
    }
    val status = HttpStatusCode.fromValue(response.status)
    if (response.status == 204 || response.status == 304) respond(status) else respond(ByteArrayContent(response.bytes, ContentType.parse(response.contentType), status))
}

/** Raw Ktor session adapted to [WsChannel]; answers pings and stops at a close frame. */
private class KtorWsChannel(private val session: WebSocketSession) : WsChannel {
    override suspend fun send(text: String) {
        try {
            session.outgoing.send(Frame.Text(text))
        } catch (_: ClosedSendChannelException) {
            throw java.io.IOException("WebSocket closed")
        }
    }

    override suspend fun receive(): String? {
        while (true) {
            val frame = try {
                session.incoming.receive()
            } catch (_: ClosedReceiveChannelException) {
                return null
            }
            when (frame) {
                is Frame.Text -> return frame.readText()
                is Frame.Ping -> session.outgoing.send(Frame.Pong(frame.data))
                is Frame.Close -> return null
                else -> Unit
            }
        }
    }

    override suspend fun close(reason: String) {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(120))) }
    }

    suspend fun use(body: suspend (WsChannel) -> Unit) {
        try {
            body(this)
        } catch (_: java.io.IOException) {
            // Peer went away mid-send; nothing to report.
        } finally {
            close()
        }
    }
}
