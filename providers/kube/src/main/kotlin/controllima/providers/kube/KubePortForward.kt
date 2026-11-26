package controllima.providers.kube

/** One TCP connection to a pod port, carried through the API server. */
interface KubePortStream {
    /** The next bytes from the pod, or null once the pod side has closed. Throws [KubeException] with the kubelet's error. */
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

/**
 * The `v4.channel.k8s.io` port-forward protocol for a single port. Every WebSocket message starts with a
 * channel byte, 0 for data and 1 for errors; the kubelet first sends the port number (two bytes,
 * little-endian) on each channel, and that prefix is not part of the stream.
 */
class PortForwardFrames {
    sealed interface Event {
        class Data(val bytes: ByteArray) : Event
        class Error(val message: String) : Event
    }

    // The prefix may arrive on its own or split across messages, so it is counted rather than assumed.
    private val prefixLeft = intArrayOf(PORT_PREFIX, PORT_PREFIX)

    /** The data or error in one message from the API server; null for a message that carries neither. */
    fun decode(message: ByteArray): Event? {
        if (message.isEmpty()) return null
        val channel = message[0].toInt()
        if (channel != DATA && channel != ERROR) return null
        val skip = minOf(prefixLeft[channel], message.size - 1)
        prefixLeft[channel] -= skip
        val payload = message.copyOfRange(1 + skip, message.size)
        if (payload.isEmpty()) return null
        return if (channel == DATA) Event.Data(payload) else Event.Error(payload.decodeToString().trim())
    }

    fun encode(bytes: ByteArray): ByteArray = ByteArray(bytes.size + 1).also {
        it[0] = DATA.toByte()
        bytes.copyInto(it, 1)
    }

    companion object {
        const val PROTOCOL = "v4.channel.k8s.io"
        private const val DATA = 0
        private const val ERROR = 1
        private const val PORT_PREFIX = 2
    }
}
