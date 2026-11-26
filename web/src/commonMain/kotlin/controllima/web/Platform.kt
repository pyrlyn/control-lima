package controllima.web

// What the UI needs from where it runs: the browser (wasmJs) or the desktop app (jvm).

/** The current route, `#/…`; the browser keeps it in the address bar so Back works. */
expect fun currentHash(): String

expect fun pushHash(hash: String)

/** Reloads the app after a server upgrade. */
expect fun reloadApp()

/** Where the server listens (`host:port`), shown under the app name. */
expect fun serverHost(): String

/** Saves text produced in the app (CSV export, log range) as a file. */
expect fun saveText(text: String, fileName: String, mime: String = "text/plain")

/** Downloads [path] with the auth header (a plain link cannot carry it) and saves it as [fileName]. */
expect fun downloadAuthorized(path: String, token: String, fileName: String, onError: (String) -> Unit)

/** Asks the user for a file and POSTs its raw bytes to [path]. */
expect fun uploadFile(path: String, headers: Map<String, String>, accept: String, onDone: (Int, String) -> Unit)

expect fun copyToClipboard(s: String): Boolean

/** Opens a forwarded port outside the app, never in place of it. */
expect fun openExternal(url: String)

/** A server push channel (`/ws/…`). Callbacks run on the UI thread. */
interface LiveSocket {
    fun open(): LiveSocket
    fun send(text: String)
    fun close()
}

/**
 * Live views reconnect with backoff; a terminal passes [reconnect] = false, or a closed shell would
 * silently reopen.
 */
expect fun LiveSocket(
    path: String,
    token: () -> String,
    onMessage: (String) -> Unit,
    onOpen: (LiveSocket) -> Unit = {},
    onState: (Boolean) -> Unit = {},
    reconnect: Boolean = true,
): LiveSocket

/** Network failure in the shape of an API error, so the UI shows it like any other. */
const val NETWORK_ERROR = """{"code":"network","message":"Cannot reach ControlLima. Is control-lima still running?"}"""
const val TIMEOUT_ERROR = """{"code":"timeout","message":"The server did not answer in time"}"""
