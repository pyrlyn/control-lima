package controllima.web

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

private val NAMED = mapOf(
    Key.Enter to "Enter", Key.NumPadEnter to "Enter", Key.Backspace to "Backspace", Key.Tab to "Tab",
    Key.Escape to "Escape", Key.DirectionUp to "ArrowUp", Key.DirectionDown to "ArrowDown",
    Key.DirectionRight to "ArrowRight", Key.DirectionLeft to "ArrowLeft", Key.MoveHome to "Home",
    Key.MoveEnd to "End", Key.PageUp to "PageUp", Key.PageDown to "PageDown", Key.Delete to "Delete",
    Key.Insert to "Insert",
)

/** The terminal takes focus when shown or clicked and reads Compose key events while it has it. */
@Composable
actual fun Modifier.terminalKeys(active: () -> Boolean, send: (String) -> Unit): Modifier {
    val isActive by rememberUpdatedState(active)
    val sendTo by rememberUpdatedState(send)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return pointerInput(Unit) {
        // The initial pass sees the press before the terminal's own click handler consumes it.
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) runCatching { focus.requestFocus() }
            }
        }
    }.focusRequester(focus).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || !isActive()) return@onPreviewKeyEvent false
        val bytes = bytes(e) ?: return@onPreviewKeyEvent false
        sendTo(bytes)
        true
    }.focusable()
}

private fun bytes(e: KeyEvent): String? {
    // Cmd shortcuts stay with the app, except paste, which a terminal must type.
    if (e.isMetaPressed) return if (e.key == Key.V) clipboardText()?.let(::pasteBytes) else null
    NAMED[e.key]?.let { return xtermBytes(it, e.isCtrlPressed, e.isAltPressed) }
    val cp = e.utf16CodePoint
    // AWT already turns Ctrl+letter into its control character.
    if (e.isCtrlPressed && cp in 0..31) return cp.toChar().toString()
    if (cp < 32 || cp == 127 || cp == 0xFFFF || Character.isISOControl(cp)) return null
    return xtermBytes(String(Character.toChars(cp)), e.isCtrlPressed, e.isAltPressed)
}
