package controllima.web

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import controllima.shared.ApiClient
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
            AppModel(api, scope).also { m ->
                window.addEventListener("hashchange") { m.onHashChange() }
                m.start()
            }
        }
        App(model)
    }
}
