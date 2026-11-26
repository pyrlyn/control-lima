package controllima.providers.kube

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class PortForwardTest {
    private fun msg(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `the port prefix of each channel is skipped`() {
        val frames = PortForwardFrames()
        // Port 8080 = 0x1F90, little-endian, on the data and the error channel.
        assertNull(frames.decode(msg(0, 0x90, 0x1F)))
        assertNull(frames.decode(msg(1, 0x90, 0x1F)))
        val data = assertIs<PortForwardFrames.Event.Data>(frames.decode(msg(0, 'h'.code, 'i'.code)))
        assertContentEquals("hi".encodeToByteArray(), data.bytes)
    }

    @Test
    fun `a prefix that shares a message with data or is split still counts`() {
        val joined = PortForwardFrames()
        assertContentEquals(msg(7), assertIs<PortForwardFrames.Event.Data>(joined.decode(msg(0, 0x90, 0x1F, 7))).bytes)
        val split = PortForwardFrames()
        assertNull(split.decode(msg(0, 0x90)))
        assertContentEquals(msg(8), assertIs<PortForwardFrames.Event.Data>(split.decode(msg(0, 0x1F, 8))).bytes)
    }

    @Test
    fun `error channel text becomes an error and unknown channels are ignored`() {
        val frames = PortForwardFrames()
        frames.decode(msg(1, 0x50, 0x00))
        val error = assertIs<PortForwardFrames.Event.Error>(frames.decode(byteArrayOf(1) + "connection refused\n".encodeToByteArray()))
        assertEquals("connection refused", error.message)
        assertNull(frames.decode(msg(5, 1, 2)))
        assertNull(frames.decode(ByteArray(0)))
        assertContentEquals(msg(0, 1, 2), frames.encode(msg(1, 2)))
    }

    /** A fake kubelet: sends the port prefixes, echoes data in upper case, then reports an error. */
    @Test
    fun `stream talks the kubelet protocol over a WebSocket`() = testApplication {
        install(ServerWebSockets)
        routing {
            webSocket("/pf") {
                send(Frame.Binary(true, msg(0, 0x90, 0x1F)))
                send(Frame.Binary(true, msg(1, 0x90, 0x1F)))
                val frame = incoming.receive() as Frame.Binary
                val bytes = frame.readBytes()
                assertEquals(0, bytes[0].toInt())
                send(Frame.Binary(true, byteArrayOf(0) + bytes.copyOfRange(1, bytes.size).decodeToString().uppercase().encodeToByteArray()))
                send(Frame.Binary(true, byteArrayOf(1) + "pod went away".encodeToByteArray()))
            }
            webSocket("/closed") { send(Frame.Binary(true, msg(0, 0x90, 0x1F))) }
        }
        val client = createClient { install(WebSockets) }

        val stream = KtorPortStream(client.webSocketSession("/pf"))
        stream.write("ping".encodeToByteArray())
        assertEquals("PING", stream.read()!!.decodeToString())
        assertEquals("pod went away", assertFailsWith<KubeException> { stream.read() }.message)
        stream.close()

        val closed = KtorPortStream(client.webSocketSession("/closed"))
        assertNull(closed.read())
        closed.close()
    }
}
