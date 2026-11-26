package controllima.web

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import controllima.shared.ApiClient
import controllima.shared.ApiRoutes
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Read before anything renders, so the token leaves the address bar at once.
    val token = Auth.takeToken()
    val api = ApiClient(XhrTransport(), token)
    ComposeViewport(document.body!!) {
        val scope = rememberCoroutineScope()
        val model = remember {
            AppModel(api, scope, onSignOut = Auth::forget).also { m ->
                window.addEventListener("hashchange") { m.onHashChange() }
                m.start()
            }
        }
        App(model)
    }
}

actual fun currentHash(): String = window.location.hash

actual fun pushHash(hash: String) = window.history.pushState(null, "", hash)

actual fun reloadApp() = window.location.reload()

/**
 * The launch token moves from the URL to memory, and to sessionStorage so a reload of this tab keeps
 * working (docs/plan/auth.md allows it); it is removed from the address bar and history at once.
 */
object Auth {
    private const val KEY = "controllima.token"

    fun takeToken(): String {
        val params = window.location.search.removePrefix("?").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to ApiRoutes.decode(it.substringAfter('=')) }
        val fromUrl = params["token"]
        if (fromUrl != null) {
            runCatching { window.sessionStorage.setItem(KEY, fromUrl) }
            window.history.replaceState(null, "", window.location.pathname + window.location.hash)
            return fromUrl
        }
        return runCatching { window.sessionStorage.getItem(KEY) }.getOrNull().orEmpty()
    }

    fun forget() {
        runCatching { window.sessionStorage.removeItem(KEY) }
    }
}
