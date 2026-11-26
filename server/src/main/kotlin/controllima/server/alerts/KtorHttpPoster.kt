package controllima.server.alerts

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** [HttpPoster] on the Ktor client (approved); the only Ktor client-aware code. Every call has a 10 s timeout. */
class KtorHttpPoster : HttpPoster, AutoCloseable {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
        followRedirects = false
        expectSuccess = false
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): Int =
        client.post(url) {
            contentType(ContentType.Application.Json)
            headers.forEach { (k, v) -> header(k, v) }
            setBody(json)
        }.status.value

    override suspend fun getText(url: String, headers: Map<String, String>): Pair<Int, String> {
        val response = client.get(url) { headers.forEach { (k, v) -> header(k, v) } }
        return response.status.value to response.bodyAsText().take(1 shl 20)
    }

    override fun close() = client.close()
}
