package controllima.providers.kube

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.network.tls.addCertificateChain
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [KubeHttp] on the Ktor CIO client (approved); the only file that imports Ktor. The connection trusts only
 * the cluster CA from the kubeconfig and presents the kubeconfig's client certificate; Ktor still checks the
 * server certificate's host name or IP. Every request has a 5 s timeout.
 */
class KtorKubeHttp(access: KubeAccess, timeoutMillis: Long = 5_000) : KubeHttp {
    private val base = access.server.trimEnd('/')
    private val token = access.token

    private val trust = KubePem.trustManager(access.caPem)
    private val chain = access.clientCertPem?.let { KubePem.certificates(it).toTypedArray() }
    private val key = access.clientKeyPem?.let(KubePem::privateKey)

    private fun HttpClientConfig<CIOEngineConfig>.tls() {
        engine {
            https {
                trustManager = trust
                if (chain != null && key != null) addCertificateChain(chain, key)
            }
        }
        followRedirects = false
        expectSuccess = false
    }

    private val client = HttpClient(CIO) {
        tls()
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }

    // Forwarded connections and followed logs may stay idle for hours, so this client has no request or
    // socket timeout; only the wait for the server's answer is bounded, in [portForward] and [streamLines].
    private val streamsClient = lazy {
        HttpClient(CIO) {
            tls()
            engine { requestTimeout = 0 }
            install(WebSockets)
        }
    }

    override fun get(path: String): KubeResponse = call { client.get(base + path) { headers() } }

    override fun delete(path: String): KubeResponse = call { client.delete(base + path) { headers() } }

    override suspend fun streamLines(path: String, onLine: suspend (String) -> Unit) {
        val headers = CompletableDeferred<Unit>()
        try {
            coroutineScope {
                // The stream itself may stay quiet for hours; only the wait for the response headers is bounded.
                val watchdog = launch {
                    delay(HANDSHAKE_MILLIS)
                    if (!headers.isCompleted) throw KubeException("$base did not answer in time")
                }
                streamsClient.value.prepareGet(base + path) { headers() }.execute { response ->
                    headers.complete(Unit)
                    watchdog.cancel()
                    if (response.status.value !in 200..299) {
                        val body = response.bodyAsText()
                        throw KubeApiException(response.status.value, KubeJson.statusMessage(body) ?: "Kubernetes API returned HTTP ${response.status.value}")
                    }
                    val channel = response.bodyAsChannel()
                    while (true) onLine(channel.readUTF8Line() ?: break)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is KubeException || e is KubeApiException) throw e
            throw KubeException("log stream from $base failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    override suspend fun portForward(path: String): KubePortStream = try {
        val session = withTimeout(HANDSHAKE_MILLIS) {
            streamsClient.value.webSocketSession(base.replaceFirst("http", "ws") + path) {
                header(HttpHeaders.SecWebSocketProtocol, PortForwardFrames.PROTOCOL)
                token?.let { bearerAuth(it) }
            }
        }
        KtorPortStream(session)
    } catch (e: Exception) {
        throw KubeException("port-forward through $base failed: ${e.message ?: e::class.simpleName}", e)
    }

    override fun close() {
        client.close()
        if (streamsClient.isInitialized()) streamsClient.value.close()
    }

    private fun HttpRequestBuilder.headers() {
        // The log endpoint answers text/plain, so JSON alone could be refused.
        header(HttpHeaders.Accept, "application/json, */*")
        token?.let { bearerAuth(it) }
    }

    // KubeClient is synchronous like the other providers; callers run it off the request thread.
    private fun call(block: suspend () -> HttpResponse): KubeResponse = try {
        runBlocking {
            val response = block()
            KubeResponse(response.status.value, response.bodyAsText())
        }
    } catch (e: Exception) {
        throw KubeException("cannot reach $base: ${e.message ?: e::class.simpleName}", e)
    }

    private companion object {
        const val HANDSHAKE_MILLIS = 10_000L
    }
}

/** [KubePortStream] over one WebSocket session. */
class KtorPortStream(private val session: WebSocketSession) : KubePortStream {
    private val frames = PortForwardFrames()

    override suspend fun read(): ByteArray? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame !is Frame.Binary) continue
            when (val event = frames.decode(frame.data)) {
                is PortForwardFrames.Event.Data -> return event.bytes
                is PortForwardFrames.Event.Error -> throw KubeException(event.message)
                null -> {}
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = session.send(Frame.Binary(true, frames.encode(bytes)))

    override suspend fun close() {
        // A peer that never answers the close frame must not hold the connection open.
        runCatching { withTimeout(2_000) { session.close() } }
        session.cancel()
    }
}
