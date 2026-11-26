package controllima.providers.kube

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.network.tls.addCertificateChain
import kotlinx.coroutines.runBlocking

/**
 * [KubeHttp] on the Ktor CIO client (approved); the only file that imports Ktor. The connection trusts only
 * the cluster CA from the kubeconfig and presents the kubeconfig's client certificate; Ktor still checks the
 * server certificate's host name or IP. Every request has a 5 s timeout.
 */
class KtorKubeHttp(access: KubeAccess, timeoutMillis: Long = 5_000) : KubeHttp {
    private val base = access.server.trimEnd('/')
    private val token = access.token

    private val client = run {
        val trust = KubePem.trustManager(access.caPem)
        val chain = access.clientCertPem?.let { KubePem.certificates(it).toTypedArray() }
        val key = access.clientKeyPem?.let(KubePem::privateKey)
        HttpClient(CIO) {
            engine {
                https {
                    trustManager = trust
                    if (chain != null && key != null) addCertificateChain(chain, key)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
            followRedirects = false
            expectSuccess = false
        }
    }

    override fun get(path: String): KubeResponse = call { client.get(base + path) { headers() } }

    override fun delete(path: String): KubeResponse = call { client.delete(base + path) { headers() } }

    override fun close() = client.close()

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
}
