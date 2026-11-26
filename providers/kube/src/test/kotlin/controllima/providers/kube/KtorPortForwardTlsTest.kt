package controllima.providers.kube

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [KtorKubeHttp.portForward] against a fake API server over TLS: the cluster CA is trusted, the bearer
 * token, the `ports` query and the `v4.channel.k8s.io` subprotocol arrive, and bytes flow both ways.
 * The certificate comes from the JDK's own keytool, so no certificate library is needed.
 */
class KtorPortForwardTlsTest {
    private val dir = Files.createTempDirectory("cl-kube-tls").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun keytool(vararg args: String) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(listOf(keytool) + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "keytool failed: $out" }
    }

    @Test
    fun `port forward over TLS with a bearer token`(): Unit = runBlocking {
        keytool("-genkeypair", "-alias", "api", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=k3s", "-ext", "SAN=ip:127.0.0.1",
            "-validity", "2", "-keystore", "api.p12", "-storetype", "PKCS12", "-storepass", "changeit")
        keytool("-exportcert", "-rfc", "-alias", "api", "-keystore", "api.p12", "-storepass", "changeit", "-file", "ca.pem")
        val keyStore = KeyStore.getInstance("PKCS12").apply { File(dir, "api.p12").inputStream().use { load(it, "changeit".toCharArray()) } }

        val seen = mutableListOf<String>()
        val server = embeddedServer(Netty, configure = {
            sslConnector(keyStore, "api", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
                host = "127.0.0.1"
                port = 0
            }
        }, module = {
            install(WebSockets)
            routing {
                webSocket("/api/v1/namespaces/default/pods/web-1/portforward", protocol = PortForwardFrames.PROTOCOL) {
                    seen += "auth=${call.request.headers["Authorization"]} ports=${call.request.queryParameters["ports"]}"
                    send(Frame.Binary(true, byteArrayOf(0, 0x90.toByte(), 0x1F)))
                    send(Frame.Binary(true, byteArrayOf(1, 0x90.toByte(), 0x1F)))
                    val bytes = (incoming.receive() as Frame.Binary).readBytes()
                    send(Frame.Binary(true, byteArrayOf(0) + bytes.copyOfRange(1, bytes.size).reversedArray()))
                }
            }
        }).start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val access = KubeAccess("test", "https://127.0.0.1:$port", File(dir, "ca.pem").readBytes(), token = "t0k")
            KtorKubeHttp(access).use { http ->
                val client = HttpKubeClient(http)
                val stream = client.portForward("default", "web-1", 8080)
                stream.write("abc".encodeToByteArray())
                assertEquals("cba", stream.read()!!.decodeToString())
                stream.close()
                assertEquals(listOf("auth=Bearer t0k ports=8080"), seen)
                // A route the server does not have fails the handshake with a readable error.
                assertFailsWith<KubeException> { client.portForward("default", "other", 8080) }
            }
            // A server whose certificate is not the cluster CA is refused.
            val wrongCa = KubeAccess("test", "https://127.0.0.1:$port", fixture("ca.pem").encodeToByteArray(), token = "t0k")
            KtorKubeHttp(wrongCa).use { http -> assertFailsWith<KubeException> { HttpKubeClient(http).portForward("default", "web-1", 8080) } }
        } finally {
            server.stop(0, 0)
        }
    }
}
