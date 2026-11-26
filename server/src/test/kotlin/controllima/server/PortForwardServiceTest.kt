package controllima.server

import controllima.providers.kube.KubeException
import controllima.providers.kube.KubePortStream
import controllima.server.http.HttpError
import controllima.shared.PortForwardKind
import controllima.shared.PortForwardRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real sockets on 127.0.0.1, a fake cluster behind them; nothing leaves this machine. */
class PortForwardServiceTest {
    /** Answers every write with the same bytes in upper case. */
    private class EchoStream : KubePortStream {
        private val replies = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile var closed = false

        override suspend fun read(): ByteArray? = replies.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { replies.send(bytes.decodeToString().uppercase().encodeToByteArray()) }
        override suspend fun close() {
            closed = true
            replies.close()
        }
    }

    private class FakeBackend : PortForwardBackend {
        val streams = mutableListOf<EchoStream>()
        var checkError: Exception? = null
        var openError: Exception? = null

        override suspend fun check(clusterId: String, request: PortForwardRequest) { checkError?.let { throw it } }
        override suspend fun open(clusterId: String, request: PortForwardRequest): KubePortStream {
            openError?.let { throw it }
            return EchoStream().also { synchronized(streams) { streams += it } }
        }
    }

    private val backend = FakeBackend()
    private val service = PortForwardService(backend, clock = { 42L })
    private val request = PortForwardRequest(PortForwardKind.POD, "default", "web-1", 8080)

    @AfterTest
    fun close() = service.close()

    private fun connect(port: Int) = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `bytes go both ways and are counted`() = runBlocking {
        val forward = service.start("colima/default", request, "admin")
        assertTrue(forward.localPort > 0)
        assertEquals("admin", forward.startedBy)
        assertEquals(42L, forward.startedAtMillis)
        connect(forward.localPort).use { socket ->
            socket.getOutputStream().write("hello".encodeToByteArray())
            val reply = ByteArray(5)
            var read = 0
            while (read < 5) read += socket.getInputStream().read(reply, read, 5 - read)
            assertEquals("HELLO", reply.decodeToString())
        }
        eventually("the stream to close") { backend.streams.singleOrNull()?.closed == true }
        eventually("the connection to end") { service.list().single().activeConnections == 0 }
        val stats = service.list().single()
        assertEquals(1, stats.totalConnections)
        assertEquals(5, stats.bytesOut)
        assertEquals(5, stats.bytesIn)
    }

    @Test
    fun `a busy local port is a conflict`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            val error = assertFailsWith<HttpError> { service.start("colima/default", request.copy(localPort = taken.localPort), "admin") }
            assertEquals(409, error.status)
        }
        assertTrue(service.list().isEmpty())
    }

    @Test
    fun `bad requests and failed checks open no port`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { service.start("colima/default", request.copy(localPort = 80), "admin") }
        assertFailsWith<IllegalArgumentException> { service.start("colima/default", request.copy(port = 0), "admin") }
        backend.checkError = KubeException("pods \"web-1\" not found")
        assertFailsWith<KubeException> { service.start("colima/default", request, "admin") }
        assertTrue(service.list().isEmpty())
    }

    @Test
    fun `a failed connection is reported and closes the local socket`() = runBlocking {
        backend.openError = KubeException("dial tcp 127.0.0.1:8080: connect: connection refused")
        val forward = service.start("colima/default", request, "admin")
        connect(forward.localPort).use { socket -> assertEquals(-1, socket.getInputStream().read()) }
        eventually("the error") { service.list().single().lastError != null }
        assertTrue(service.list().single().lastError!!.contains("connection refused"))
    }

    @Test
    fun `stop closes the listener and open connections`() = runBlocking {
        val forward = service.start("colima/default", request, "admin")
        val socket = connect(forward.localPort)
        socket.getOutputStream().write("x".encodeToByteArray())
        assertEquals('X'.code, socket.getInputStream().read())
        service.stop(forward.id)
        assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
        socket.close()
        eventually("the stream to close") { backend.streams.single().closed }
        assertTrue(service.list().isEmpty())
        assertFailsWith<ConnectException> { connect(forward.localPort) }
        assertEquals(404, assertFailsWith<HttpError> { service.stop(forward.id) }.status)
    }
}
