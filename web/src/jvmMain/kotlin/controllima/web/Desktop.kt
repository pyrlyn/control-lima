package controllima.web

import controllima.shared.ApiRoutes
import controllima.shared.HttpResult
import controllima.shared.HttpTransport
import kotlinx.coroutines.future.await
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.swing.SwingUtilities
import javax.swing.Timer

/** The desktop app's in-process server; set by :desktop before the UI starts. */
object DesktopHost {
    @Volatile var baseUrl: String = "http://127.0.0.1:8080"
    /** Parent of file dialogs. */
    @Volatile var frame: Frame? = null

    // Cookies carry the multi-user session, as they do in the browser.
    internal val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).cookieHandler(CookieManager()).build()
    }

    internal fun request(path: String, headers: Map<String, String>): HttpRequest.Builder =
        HttpRequest.newBuilder(URI(baseUrl + path)).timeout(Duration.ofSeconds(90)).apply { headers.forEach { (k, v) -> header(k, v) } }
}

/** [HttpTransport] over the JDK client. */
class JvmTransport : HttpTransport {
    override suspend fun request(method: String, path: String, body: String?, headers: Map<String, String>): HttpResult = try {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val response = DesktopHost.client.sendAsync(DesktopHost.request(path, headers).method(method, publisher).build(), HttpResponse.BodyHandlers.ofString()).await()
        HttpResult(response.statusCode(), response.body())
    } catch (_: HttpTimeoutException) {
        HttpResult(0, TIMEOUT_ERROR)
    } catch (_: IOException) {
        HttpResult(0, NETWORK_ERROR)
    }
}

private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

// A single window has nothing like an address bar; the route only lives in memory.
private var route = "#/overview"

actual fun currentHash(): String = route

actual fun pushHash(hash: String) {
    route = hash
}

/** The server runs in this process, so it can never be a different version than the UI. */
actual fun reloadApp() {}

private fun chooseFile(title: String, mode: Int, fileName: String? = null): File? {
    val dialog = FileDialog(DesktopHost.frame, title, mode)
    fileName?.let { dialog.file = it }
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

actual fun saveText(text: String, fileName: String, mime: String) {
    val file = chooseFile("Save", FileDialog.SAVE, fileName) ?: return
    runCatching { file.writeText(text) }
}

actual fun downloadAuthorized(path: String, token: String, fileName: String, onError: (String) -> Unit) {
    val file = chooseFile("Save", FileDialog.SAVE, fileName) ?: return
    val headers = if (token.isEmpty()) emptyMap() else mapOf(ApiRoutes.TOKEN_HEADER to token)
    DesktopHost.client.sendAsync(DesktopHost.request(path, headers).GET().build(), HttpResponse.BodyHandlers.ofFile(file.toPath()))
        .whenComplete { r, e ->
            val error = when {
                e != null -> "Download failed: network error"
                r.statusCode() != 200 -> "Download failed: HTTP ${r.statusCode()}"
                else -> null
            }
            if (error != null) {
                file.delete()
                ui { onError(error) }
            }
        }
}

actual fun uploadFile(path: String, headers: Map<String, String>, accept: String, onDone: (Int, String) -> Unit) {
    val file = chooseFile("Open", FileDialog.LOAD) ?: return
    val request = DesktopHost.request(path, headers + ("Content-Type" to "application/gzip"))
        .POST(HttpRequest.BodyPublishers.ofFile(file.toPath())).build()
    DesktopHost.client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .whenComplete { r, e -> ui { if (e != null) onDone(0, NETWORK_ERROR) else onDone(r.statusCode(), r.body()) } }
}

actual fun copyToClipboard(s: String): Boolean =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s), null) }.isSuccess

internal fun clipboardText(): String? =
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()

actual fun openExternal(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

actual fun LiveSocket(
    path: String,
    token: () -> String,
    onMessage: (String) -> Unit,
    onOpen: (LiveSocket) -> Unit,
    onState: (Boolean) -> Unit,
    reconnect: Boolean,
): LiveSocket = JvmSocket(path, token, onMessage, onOpen, onState, reconnect)

/** Same protocol as the browser's: token in the query (`/ws/` only), backoff from 1 s to 15 s. */
private class JvmSocket(
    private val path: String,
    private val token: () -> String,
    private val onMessage: (String) -> Unit,
    private val onOpen: (LiveSocket) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val reconnect: Boolean,
) : LiveSocket {
    private var ws: WebSocket? = null
    private var closed = false
    private var retryMillis = 1_000
    // The JDK refuses a send while the previous one is in flight, so sends are chained.
    private var lastSend: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    override fun open(): LiveSocket {
        closed = false
        connect()
        return this
    }

    private fun connect() {
        val sep = if ('?' in path) '&' else '?'
        val t = token()
        val url = DesktopHost.baseUrl.replaceFirst("http", "ws") + path + if (t.isEmpty()) "" else "${sep}token=${ApiRoutes.encode(t)}"
        DesktopHost.client.newWebSocketBuilder().buildAsync(URI(url), Listener())
            .whenComplete { _, e -> if (e != null) ui { ended() } }
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
            ui {
                if (closed) {
                    webSocket.abort()
                    return@ui
                }
                ws = webSocket
                retryMillis = 1_000
                onState(true)
                onOpen(this@JvmSocket)
            }
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                ui { if (!closed) onMessage(text) }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            ui { ended() }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            ui { ended() }
        }
    }

    private fun ended() {
        ws = null
        onState(false)
        if (!closed && reconnect) {
            Timer(retryMillis) { if (!closed) connect() }.apply { isRepeats = false }.start()
            retryMillis = (retryMillis * 2).coerceAtMost(15_000)
        }
    }

    override fun send(text: String) {
        val socket = ws ?: return
        lastSend = lastSend.handle { _, _ -> }.thenCompose { socket.sendText(text, true) }
    }

    override fun close() {
        closed = true
        ws?.sendClose(WebSocket.NORMAL_CLOSURE, "")
        ws = null
    }
}
