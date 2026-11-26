package controllima.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import controllima.shared.ApiRoutes
import controllima.shared.TermCell
import controllima.shared.TerminalScreen
import controllima.shared.Vm
import controllima.shared.VmStatus

private val SIZES = listOf(24 to 80, 32 to 120, 48 to 160)
private val BACKGROUND = Color(0xFF1B1D21)
private val FOREGROUND = Color(0xFFD8DEE4)
private val ANSI = listOf(
    0xFF1B1D21, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD8DEE4,
    0xFF5C6370, 0xFFFF7B86, 0xFFB5E890, 0xFFFFD68A, 0xFF7EC3FF, 0xFFDD9BF0, 0xFF7FD4DF, 0xFFFFFFFF,
).map { Color(it) }
private val BASE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = FOREGROUND)

/**
 * VM detail → Terminal (docs/pages/vm-detail.md): a login shell over Lima's ssh config. It only connects
 * when asked, never reconnects on its own, and a size change opens a new session.
 */
@Composable
fun VmTerminalTab(m: AppModel, vm: Vm) {
    if (vm.status != VmStatus.RUNNING) {
        EmptyState("${vm.name} is not running", "Start the VM to open a terminal. ControlLima never starts it for you.")
        return
    }
    var size by remember(vm.ref) { mutableStateOf(SIZES.first()) }
    var session by remember(vm.ref) { mutableStateOf(0) }
    Section("Terminal", actions = {
        SIZES.forEach { s -> SmallAction("${s.second}×${s.first}", s != size || session == 0) { size = s; session++ } }
    }) {
        Text(
            "A login shell in ${vm.name} as its Lima user. Everything you type runs in the guest, and the session is recorded in the audit log.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (session == 0) {
            SmallAction("Connect") { session++ }
        } else {
            // Keyed by session, so a new size or a reconnect starts from a clean screen.
            key(session) { TerminalView(m, vm, size.first, size.second) { session++ } }
        }
    }
}

@Composable
private fun TerminalView(m: AppModel, vm: Vm, rows: Int, cols: Int, onReconnect: () -> Unit) {
    val screen = remember { TerminalScreen(rows, cols) }
    var version by remember { mutableStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    // While captured, every key goes to the guest, page shortcuts included; release it to use the page.
    var captured by remember { mutableStateOf(true) }
    val socket = remember {
        val path = "${ApiRoutes.TERMINAL_WS}?target=${ApiRoutes.encode(vm.ref.targetId)}&rows=$rows&cols=$cols"
        LiveSocket(path, { m.api.token }, onMessage = { screen.feed(it); version++ }, reconnect = false, onState = { up ->
            connected = up
            if (!up) ended = true
        })
    }
    DisposableEffect(socket) {
        socket.open()
        onDispose { socket.close() }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                ended -> "Session ended"
                !connected -> "Connecting…"
                captured -> "Connected · ${cols}×$rows · typing goes to ${vm.name}"
                else -> "Connected · ${cols}×$rows · keyboard released"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (ended) SmallAction("Reconnect", onClick = onReconnect)
        else SmallAction(if (captured) "Release keyboard" else "Type into terminal") { captured = !captured }
    }
    val measurer = rememberTextMeasurer(cacheSize = 512)
    // The browser has no monospace font for Compose, so characters are placed on a fixed grid instead:
    // each starts at its column whatever its own width. Digits are the most uniform width to size it by.
    val cell = remember { measurer.measure("0", BASE).size.let { it.width to it.height } }
    val density = LocalDensity.current
    Box(Modifier.terminalKeys({ captured && !ended }, socket::send).fillMaxWidth().background(BACKGROUND).horizontalScroll(rememberScrollState()).clickable { captured = true }.padding(8.dp)) {
        Canvas(Modifier.size(with(density) { (cell.first * cols).toDp() }, with(density) { (cell.second * rows).toDp() })) {
            // Reading version here redraws the canvas on new output.
            if (version < 0) return@Canvas
            val (cw, ch) = cell.first.toFloat() to cell.second.toFloat()
            for (r in 0 until screen.rows) {
                val cursorCol = if (screen.cursorVisible && captured && screen.cursorRow == r) screen.cursorCol else -1
                screen.row(r).forEachIndexed { c, tc ->
                    val (fg, bg) = colors(tc, inverse = tc.inverse != (c == cursorCol))
                    val at = Offset(c * cw, r * ch)
                    if (bg != null) drawRect(bg, at, Size(cw, ch))
                    if (tc.ch != ' ') drawText(measurer, tc.ch.toString(), at, BASE.copy(color = fg, fontWeight = if (tc.bold) FontWeight.Bold else null))
                }
            }
        }
    }
}

/** Foreground and background of a cell; a null background is the terminal's own. */
private fun colors(c: TermCell, inverse: Boolean): Pair<Color, Color?> {
    // Bold brightens the eight base colors, as most terminals do.
    val fgIndex = if (c.bold && c.fg in 0..7) c.fg + 8 else c.fg
    val fg = ANSI.getOrNull(fgIndex) ?: FOREGROUND
    val bg = ANSI.getOrNull(c.bg)
    return if (inverse) (bg ?: BACKGROUND) to fg else fg to bg
}

/**
 * Sends keystrokes and pastes, as xterm bytes, to [send] while [active]. Each platform reads keys its own
 * way; both translate them with [xtermBytes].
 */
@Composable
expect fun Modifier.terminalKeys(active: () -> Boolean, send: (String) -> Unit): Modifier

/** A paste is typed as-is, with line ends as Enter. */
fun pasteBytes(text: String): String = text.replace("\r\n", "\r").replace('\n', '\r')

/**
 * What an xterm sends for [key], named as in DOM `KeyboardEvent.key` ("Enter", "ArrowUp", "a"), or null
 * to leave the key to the app. Callers leave Cmd shortcuts alone, so copy and paste keep working.
 */
fun xtermBytes(key: String, ctrl: Boolean, alt: Boolean): String? {
    if (ctrl && key.length == 1) {
        val c = key.lowercase()[0]
        return when (c) {
            in 'a'..'z' -> (c - 'a' + 1).toChar().toString()
            '[' -> "\u001b"
            '\\' -> "\u001c"
            ']' -> "\u001d"
            ' ', '@' -> "\u0000"
            else -> null
        }
    }
    val named = when (key) {
        "Enter" -> "\r"
        "Backspace" -> "\u007f"
        "Tab" -> "\t"
        "Escape" -> "\u001b"
        "ArrowUp" -> "\u001b[A"
        "ArrowDown" -> "\u001b[B"
        "ArrowRight" -> "\u001b[C"
        "ArrowLeft" -> "\u001b[D"
        "Home" -> "\u001b[H"
        "End" -> "\u001b[F"
        "PageUp" -> "\u001b[5~"
        "PageDown" -> "\u001b[6~"
        "Delete" -> "\u001b[3~"
        "Insert" -> "\u001b[2~"
        else -> null
    }
    if (named != null) return if (alt) "\u001b$named" else named
    // A printable key names itself in one character (two for a surrogate pair); "Shift", "F5", "Dead" do not.
    if (key.isEmpty() || key.length > 2 || (key.length == 2 && !key[0].isHighSurrogate())) return null
    return if (alt) "\u001b$key" else key
}
