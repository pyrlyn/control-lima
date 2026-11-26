package controllima.web

import controllima.shared.ApiRoutes
import controllima.shared.HttpResult
import controllima.shared.HttpTransport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.get
import org.w3c.xhr.BLOB
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.coroutines.resume

/** Browser implementation of the shared [HttpTransport]; XHR avoids Promise interop in Wasm. */
class XhrTransport : HttpTransport {
    override suspend fun request(method: String, path: String, body: String?, headers: Map<String, String>): HttpResult =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            xhr.open(method, path)
            headers.forEach { (k, v) -> xhr.setRequestHeader(k, v) }
            // Slightly above the server's own provider timeouts, so the server's 504 wins.
            xhr.timeout = 90_000
            xhr.onload = {
                if (cont.isActive) cont.resume(HttpResult(xhr.status.toInt(), xhr.responseText))
                null
            }
            xhr.onerror = {
                if (cont.isActive) cont.resume(HttpResult(0, NETWORK_ERROR))
                null
            }
            xhr.ontimeout = {
                if (cont.isActive) cont.resume(HttpResult(0, """{"code":"timeout","message":"The server did not answer in time"}"""))
                null
            }
            cont.invokeOnCancellation { xhr.abort() }
            if (body != null) xhr.send(body) else xhr.send()
        }

    companion object {
        const val NETWORK_ERROR = """{"code":"network","message":"Cannot reach ControlLima. Is control-lima still running?"}"""
    }
}

/** Downloads [path] with the auth header (a plain link cannot carry it) and saves it as [fileName]. */
fun downloadAuthorized(path: String, token: String, fileName: String, onError: (String) -> Unit) {
    val xhr = XMLHttpRequest()
    xhr.open("GET", path)
    if (token.isNotEmpty()) xhr.setRequestHeader(ApiRoutes.TOKEN_HEADER, token)
    xhr.responseType = XMLHttpRequestResponseType.BLOB
    xhr.onload = {
        if (xhr.status.toInt() == 200) saveBlob(xhr.response as Blob, fileName) else onError("Download failed: HTTP ${xhr.status}")
        null
    }
    xhr.onerror = {
        onError("Download failed: network error")
        null
    }
    xhr.send()
}

/** Asks the user for a file and POSTs its raw bytes to [path]. */
fun uploadFile(path: String, headers: Map<String, String>, accept: String, onDone: (Int, String) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = accept
    input.onchange = {
        val file = input.files?.get(0)
        if (file != null) {
            val xhr = XMLHttpRequest()
            xhr.open("POST", path)
            headers.forEach { (k, v) -> xhr.setRequestHeader(k, v) }
            xhr.setRequestHeader("Content-Type", "application/gzip")
            xhr.onload = {
                onDone(xhr.status.toInt(), xhr.responseText)
                null
            }
            xhr.onerror = {
                onDone(0, NETWORK_ERROR_TEXT)
                null
            }
            xhr.send(file)
        }
        null
    }
    input.click()
}

private const val NETWORK_ERROR_TEXT = XhrTransport.NETWORK_ERROR

private fun saveBlob(blob: Blob, fileName: String) {
    val url = URL.createObjectURL(blob)
    clickLink(url, fileName)
    window.setTimeout({ URL.revokeObjectURL(url); null }, 10_000)
}

/** Saves text produced in the page (CSV export, log range) as a file. */
fun saveText(text: String, fileName: String, mime: String = "text/plain") {
    clickLink("data:$mime;charset=utf-8," + encodeUriComponent(text), fileName)
}

private fun clickLink(href: String, fileName: String) {
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = href
    a.download = fileName
    document.body?.appendChild(a)
    a.click()
    a.remove()
}

@JsFun("(s) => encodeURIComponent(s)")
external fun encodeUriComponent(s: String): String

@JsFun("(s) => { try { navigator.clipboard.writeText(s); return true } catch (e) { return false } }")
external fun copyToClipboard(s: String): Boolean

/** Opens a forwarded port in a new tab, never in this one, and without an opener reference. */
fun openExternal(url: String) {
    window.open(url, "_blank", "noopener,noreferrer")
}

/**
 * A WebSocket that reconnects with backoff. The launch token goes in the query because browsers
 * cannot set headers on WebSockets; the server accepts `?token=` only on `/ws/` (docs/plan/auth.md).
 */
class LiveSocket(
    private val path: String,
    private val token: () -> String,
    private val onMessage: (String) -> Unit,
    private val onOpen: (LiveSocket) -> Unit = {},
    private val onState: (Boolean) -> Unit = {},
) {
    private var ws: WebSocket? = null
    private var closed = false
    private var retryMillis = 1_000

    fun open(): LiveSocket {
        closed = false
        connect()
        return this
    }

    private fun connect() {
        val scheme = if (window.location.protocol == "https:") "wss" else "ws"
        val sep = if ('?' in path) '&' else '?'
        val t = token()
        val url = "$scheme://${window.location.host}$path" + if (t.isEmpty()) "" else "${sep}token=${encodeUriComponent(t)}"
        val socket = WebSocket(url)
        ws = socket
        socket.onopen = {
            retryMillis = 1_000
            onState(true)
            onOpen(this)
            null
        }
        socket.onmessage = { e ->
            ((e as MessageEvent).data as? JsString)?.toString()?.let(onMessage)
            null
        }
        socket.onclose = {
            onState(false)
            if (!closed) {
                window.setTimeout({ if (!closed) connect(); null }, retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(15_000)
            }
            null
        }
    }

    fun send(text: String) {
        val socket = ws ?: return
        if (socket.readyState == WebSocket.OPEN) socket.send(text)
    }

    fun close() {
        closed = true
        ws?.close()
        ws = null
    }
}
