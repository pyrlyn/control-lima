package controllima.server

import controllima.providers.kube.KubePortStream
import controllima.server.http.conflict
import controllima.server.http.notFound
import controllima.shared.KubePortForward
import controllima.shared.PortForwardRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where forwarded connections go; [KubernetesService] in production, a fake in tests. */
interface PortForwardBackend {
    /** Throws when the target does not exist or cannot take connections now, so a bad forward is refused up front. */
    suspend fun check(clusterId: String, request: PortForwardRequest)
    /** One stream per local connection; a service is resolved to a pod again each time, as its pods come and go. */
    suspend fun open(clusterId: String, request: PortForwardRequest): KubePortStream
}

/**
 * Kubernetes port forwards (docs/pages/kubernetes.md, Port forwards): a listener on 127.0.0.1 whose
 * connections are carried to a pod through the API server. Forwards live in memory only, so nothing is
 * forwarded again after a restart without someone asking.
 */
class PortForwardService(
    private val backend: PortForwardBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val forwards = ConcurrentHashMap<String, Forward>()
    private val nextId = AtomicInteger(1)

    private class Forward(
        val id: String,
        val clusterId: String,
        val request: PortForwardRequest,
        val server: ServerSocket,
        val startedBy: String,
        val startedAt: Long,
    ) {
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        val active = AtomicInteger()
        val total = AtomicLong()
        val bytesOut = AtomicLong()
        val bytesIn = AtomicLong()
        @Volatile var lastError: String? = null
        var job: Job? = null

        fun snapshot() = KubePortForward(
            id, clusterId, request.kind, request.namespace, request.name, request.port, server.localPort, startedBy, startedAt,
            active.get(), total.get(), bytesOut.get(), bytesIn.get(), lastError,
        )
    }

    fun list(): List<KubePortForward> = forwards.values.map { it.snapshot() }.sortedBy { it.localPort }

    suspend fun start(clusterId: String, request: PortForwardRequest, actor: String): KubePortForward {
        request.problem()?.let { throw IllegalArgumentException(it) }
        if (forwards.size >= MAX_FORWARDS) conflict("At most $MAX_FORWARDS port forwards can run at once")
        backend.check(clusterId, request)
        val server = ServerSocket()
        try {
            // Without this, macOS lets a 127.0.0.1 bind succeed under another process's wildcard listener and
            // silently steal its local connections.
            server.reuseAddress = false
            server.bind(InetSocketAddress(LOOPBACK, request.localPort), BACKLOG)
        } catch (e: IOException) {
            server.close()
            if (e is BindException) conflict("127.0.0.1:${request.localPort} is already in use")
            throw e
        }
        val forward = Forward(nextId.getAndIncrement().toString(), clusterId, request, server, actor, clock())
        forwards[forward.id] = forward
        forward.job = scope.launch { accept(forward) }
        return forward.snapshot()
    }

    fun stop(id: String) {
        val forward = forwards.remove(id) ?: notFound("No port forward $id")
        shut(forward)
    }

    override fun close() {
        forwards.keys.toList().forEach { id -> forwards.remove(id)?.let(::shut) }
        scope.cancel()
    }

    private fun shut(forward: Forward) {
        // Closing the sockets unblocks the threads reading them; each connection then closes its stream.
        runCatching { forward.server.close() }
        forward.sockets.forEach { runCatching { it.close() } }
        forward.job?.cancel()
    }

    private suspend fun accept(forward: Forward) = coroutineScope {
        while (true) {
            val socket = try {
                forward.server.accept()
            } catch (_: IOException) {
                break
            }
            if (forward.active.get() >= MAX_CONNECTIONS) {
                forward.lastError = "Refused a connection: $MAX_CONNECTIONS are already open"
                runCatching { socket.close() }
                continue
            }
            launch { connect(forward, socket) }
        }
    }

    private suspend fun connect(forward: Forward, socket: Socket) {
        forward.sockets += socket
        forward.active.incrementAndGet()
        forward.total.incrementAndGet()
        var stream: KubePortStream? = null
        try {
            stream = try {
                withTimeout(OPEN_TIMEOUT_MILLIS) { backend.open(forward.clusterId, forward.request) }
            } catch (e: TimeoutCancellationException) {
                throw IOException("The cluster did not open the connection in time")
            }
            pipe(forward, socket, stream)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            forward.lastError = e.message ?: e::class.simpleName
        } finally {
            runCatching { socket.close() }
            // A stopped forward cancels this coroutine, and the stream must still be closed.
            stream?.let { withContext(NonCancellable) { runCatching { withTimeout(CLOSE_TIMEOUT_MILLIS) { it.close() } } } }
            forward.sockets -= socket
            forward.active.decrementAndGet()
        }
    }

    /**
     * Copies both ways until either side ends. The port-forward protocol has no half-close, so the end of
     * one direction ends the connection, as it does with kubectl.
     */
    private suspend fun pipe(forward: Forward, socket: Socket, stream: KubePortStream) = coroutineScope {
        val done = CompletableDeferred<Throwable?>()
        val toPod = launch {
            val input = socket.getInputStream()
            val buffer = ByteArray(BUFFER)
            val result = runCatching {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    stream.write(buffer.copyOf(n))
                    forward.bytesOut.addAndGet(n.toLong())
                }
            }
            done.complete(result.exceptionOrNull()?.takeUnless { socket.isClosed })
        }
        val fromPod = launch {
            val output = socket.getOutputStream()
            val result = runCatching {
                while (true) {
                    val bytes = stream.read() ?: break
                    output.write(bytes)
                    output.flush()
                    forward.bytesIn.addAndGet(bytes.size.toLong())
                }
            }
            done.complete(result.exceptionOrNull())
        }
        val failure = done.await()
        // The blocked socket read only returns once the socket is closed.
        runCatching { socket.close() }
        toPod.cancel()
        fromPod.cancel()
        if (failure != null && failure !is CancellationException) throw failure
    }

    companion object {
        const val MAX_FORWARDS = 32
        const val MAX_CONNECTIONS = 64
        private const val BACKLOG = 50
        private const val BUFFER = 16 * 1024
        private const val OPEN_TIMEOUT_MILLIS = 15_000L
        private const val CLOSE_TIMEOUT_MILLIS = 3_000L
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
