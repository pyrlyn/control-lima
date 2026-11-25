package controllima.providers.docker

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal HTTP/1.0 GET over a unix socket (JDK 16+), enough for JSON endpoints.
 * HTTP/1.0 avoids chunked responses; the daemon closes the connection after the body.
 */
class UnixSocketTransport(
    private val socketPath: String,
    private val timeout: Duration = 5.seconds,
) : DockerTransport {
    override fun get(path: String): String {
        if (!Files.exists(Path.of(socketPath))) throw DockerException("Docker socket not found: $socketPath")
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "docker-get").apply { isDaemon = true } }
        try {
            return executor.submit<String> { request(path) }.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw DockerException("Docker API $path timed out after $timeout")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun request(path: String): String {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            channel.write(ByteBuffer.wrap("GET $path HTTP/1.0\r\nHost: docker\r\n\r\n".toByteArray()))
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(64 * 1024)
            while (channel.read(buf) >= 0) {
                buf.flip(); out.write(buf.array(), 0, buf.limit()); buf.clear()
            }
            return HttpResponse.body(out.toString(Charsets.UTF_8), path)
        }
    }
}

internal object HttpResponse {
    fun body(raw: String, path: String): String {
        val split = raw.indexOf("\r\n\r\n")
        if (split < 0) throw DockerException("Malformed response for $path")
        val code = raw.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull()
        if (code == null || code !in 200..299) throw DockerException("Docker API $path returned $code")
        return raw.substring(split + 4)
    }
}
