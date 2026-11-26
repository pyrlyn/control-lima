package controllima.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.browser.window
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Keys are read from the DOM in the capture phase rather than through a Compose text field: the field's
 * hidden input keeps its own buffer, and Compose would also act on its page shortcuts.
 */
@Composable
actual fun Modifier.terminalKeys(active: () -> Boolean, send: (String) -> Unit): Modifier {
    val isActive by rememberUpdatedState(active)
    val sendTo by rememberUpdatedState(send)
    DisposableEffect(Unit) {
        val onKey: (Event) -> Unit = { e ->
            val k = e as KeyboardEvent
            // Cmd stays with the browser, so Cmd+C copies and Cmd+V arrives as a paste event.
            val bytes = if (k.metaKey || k.isComposing) null else xtermBytes(k.key, k.ctrlKey, k.altKey)
            if (isActive() && bytes != null) {
                sendTo(bytes)
                k.preventDefault()
                k.stopPropagation()
            }
        }
        val onPaste: (Event) -> Unit = { e ->
            val text = (e as ClipboardEvent).clipboardData?.getData("text/plain")
            if (isActive() && !text.isNullOrEmpty()) {
                sendTo(pasteBytes(text))
                e.preventDefault()
                e.stopPropagation()
            }
        }
        window.addEventListener("keydown", onKey, true)
        window.addEventListener("paste", onPaste, true)
        onDispose {
            window.removeEventListener("keydown", onKey, true)
            window.removeEventListener("paste", onPaste, true)
        }
    }
    return this
}
