package controllima.server

import controllima.server.http.HttpApp
import controllima.server.http.Request
import controllima.server.http.Response
import controllima.server.http.Upgrade
import controllima.server.http.controlLima
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Ktor adapter passes method, path, query, headers and body through unchanged. */
class KtorSmokeTest {
    private val echo = object : HttpApp {
        override suspend fun handle(request: Request): Response =
            Response.text(201, "${request.method} ${request.path} ${request.param("q")} ${request.header("X-Test")} ${request.body}", "text/plain")
                .withHeaders(mapOf("X-Echo" to "1"))

        override suspend fun upgrade(request: Request): Upgrade = Upgrade.Refused(Response.error(401, "unauthorized", "no"))
        override fun isWebSocketPath(path: String) = path.startsWith("/ws/")
    }

    @Test
    fun `requests and responses round trip`() = testApplication {
        application { controlLima(echo) }
        val r = client.post("/api/x?q=1") {
            header("X-Test", "yes")
            setBody("body")
        }
        assertEquals(201, r.status.value)
        assertEquals("POST /api/x 1 yes body", r.bodyAsText())
        assertEquals("1", r.headers["X-Echo"])
        assertEquals(201, client.get("/anything").status.value)
    }
}
