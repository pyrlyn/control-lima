package controllima.server

import controllima.shared.ApiRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** The Ktor-backed [HttpServer]. */
class KtorHttpServer(private val config: ServerConfig, private val api: Api) : HttpServer {
    override val port: Int get() = config.port

    private var engine: EmbeddedServer<*, *>? = null

    override fun start(wait: Boolean) {
        val server = embeddedServer(Netty, port = config.port, host = config.host) { module(api) }
        engine = server
        server.start(wait = wait)
    }

    override fun stop() {
        engine?.stop(500, 2_000)
        engine = null
    }
}

/** Ktor wiring for [Api]; exposed for tests with the Ktor test host. */
fun Application.module(api: Api) {
    routing {
        get(ApiRoutes.HEALTH) { call.reply(api.health()) }
        get(ApiRoutes.VMS) { call.guarded(api) { api.vms() } }
    }
}

private suspend fun ApplicationCall.guarded(api: Api, handler: () -> Api.Response) {
    val denied = api.authorize(
        path = request.path(),
        token = request.header(ApiRoutes.TOKEN_HEADER) ?: request.queryParameters["token"],
        origin = request.header("Origin"),
    )
    reply(denied ?: handler())
}

private suspend fun ApplicationCall.reply(response: Api.Response) =
    respondText(response.body, ContentType.parse(response.contentType), HttpStatusCode.fromValue(response.status))
