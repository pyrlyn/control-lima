package controllima.providers.docker

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DockerResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/** An open streaming response (logs, pull progress). Closing it closes the socket. */
interface DockerStream : Closeable {
    val status: Int
    val input: InputStream
}

/** Raw HTTP transport to the Engine API, so the provider can be tested with fixtures. */
interface DockerTransport {
    /** Sends a request and reads the whole body; throws [DockerException] on timeout or socket errors. */
    fun request(method: String, path: String, body: String? = null, timeout: Duration = 5.seconds): DockerResponse

    /** Opens a streaming response. The caller closes it. */
    fun stream(method: String, path: String, body: String? = null): DockerStream =
        throw DockerException("streaming not supported by ${this::class.simpleName}")

    fun get(path: String): String {
        val response = request("GET", path)
        if (!response.ok) throw DockerException(HttpResponse.message(response, path))
        return response.body
    }
}

/**
 * Minimal HTTP/1.0 over a unix socket (JDK 16+). HTTP/1.0 avoids chunked responses: the daemon
 * writes the body raw and closes the connection, which also works for streams.
 */
class UnixSocketTransport(
    private val socketPath: String,
    private val defaultTimeout: Duration = 5.seconds,
) : DockerTransport {
    val available: Boolean get() = Files.exists(Path.of(socketPath))

    override fun request(method: String, path: String, body: String?, timeout: Duration): DockerResponse {
        requireSocket()
        val limit = if (timeout > Duration.ZERO) timeout else defaultTimeout
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "docker-api").apply { isDaemon = true } }
        try {
            return executor.submit<DockerResponse> {
                open(method, path, body).use { channel ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteBuffer.allocate(64 * 1024)
                    while (channel.read(buf) >= 0) {
                        buf.flip(); out.write(buf.array(), 0, buf.limit()); buf.clear()
                    }
                    HttpResponse.parse(out.toByteArray(), path)
                }
            }.get(limit.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw DockerException("Docker API $method $path timed out after $limit")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw DockerException("Docker API $method $path failed: ${e.cause?.message}")
        } finally {
            executor.shutdownNow()
        }
    }

    override fun stream(method: String, path: String, body: String?): DockerStream {
        requireSocket()
        val channel = open(method, path, body)
        val input = Channels.newInputStream(channel)
        val status = HttpResponse.readHead(input, path)
        return object : DockerStream {
            override val status = status
            override val input = input
            override fun close() = channel.close()
        }
    }

    private fun requireSocket() {
        if (!available) throw DockerException("Docker socket not found: $socketPath")
    }

    private fun open(method: String, path: String, body: String?): SocketChannel {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.0\r\nHost: docker\r\n")
                if (body != null) append("Content-Type: application/json\r\n")
                append("Content-Length: ${bytes.size}\r\n\r\n")
            }
            channel.write(ByteBuffer.wrap(head.toByteArray() + bytes))
            return channel
        } catch (e: Exception) {
            channel.close()
            throw DockerException("Cannot connect to Docker at $socketPath: ${e.message}")
        }
    }
}

internal object HttpResponse {
    fun parse(raw: ByteArray, path: String): DockerResponse {
        val text = String(raw, Charsets.UTF_8)
        val split = text.indexOf("\r\n\r\n")
        if (split < 0) throw DockerException("Malformed response for $path")
        val code = text.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: throw DockerException("Malformed status for $path")
        return DockerResponse(code, text.substring(split + 4))
    }

    fun body(raw: String, path: String): String {
        val response = parse(raw.toByteArray(), path)
        if (!response.ok) throw DockerException(message(response, path))
        return response.body
    }

    /** Reads the status line and headers of a streaming response, leaving the body in [input]. */
    fun readHead(input: InputStream, path: String): Int {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw DockerException("Connection closed while reading headers of $path")
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.size() > 64 * 1024) throw DockerException("Headers too large for $path")
        }
        return head.toString(Charsets.UTF_8).substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw DockerException("Malformed status for $path")
    }

    fun message(response: DockerResponse, path: String): String {
        val detail = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(response.body) as? kotlinx.serialization.json.JsonObject)
                ?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        return "Docker API $path returned ${response.status}" + (detail?.let { ": $it" } ?: "")
    }
}
