package controllima.shared

/** One character cell. Colors are ANSI indexes 0–15, or -1 for the default. */
data class TermCell(val ch: Char = ' ', val fg: Int = -1, val bg: Int = -1, val bold: Boolean = false, val inverse: Boolean = false)

/**
 * A small VT100/xterm screen for the web terminal (docs/pages/vm-detail.md, Terminal). It covers what a
 * login shell, `less`, `top` and `vi` use: cursor movement, erase, scroll regions, insert/delete, SGR
 * colors and the alternate screen. Anything else is consumed and ignored so it never prints as garbage.
 */
class TerminalScreen(val rows: Int, val cols: Int) {
    private var main = blank(rows)
    private var alt: Array<Array<TermCell>>? = null
    private val grid get() = alt ?: main

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    private var pen = TermCell()
    private var top = 0
    private var bottom = rows - 1
    private var saved = 0 to 0
    /** Set after writing the last column; the next printable character wraps first (xterm's deferred wrap). */
    private var wrapPending = false

    private enum class State { TEXT, ESC, CSI, OSC, CHARSET }
    private var state = State.TEXT
    private val params = StringBuilder()

    fun row(index: Int): List<TermCell> = grid[index].toList()

    fun text(): String = (0 until rows).joinToString("\n") { r -> grid[r].joinToString("") { it.ch.toString() }.trimEnd() }.trimEnd('\n')

    fun feed(data: String) {
        for (c in data) when (state) {
            State.TEXT -> text(c)
            State.ESC -> escape(c)
            State.CSI -> csi(c)
            State.OSC -> if (c == '\u0007' || c == '\u009c') state = State.TEXT else if (c == '\u001b') state = State.ESC
            State.CHARSET -> state = State.TEXT
        }
    }

    private fun text(c: Char) {
        when (c) {
            '\u001b' -> state = State.ESC
            '\r' -> { cursorCol = 0; wrapPending = false }
            '\n', '\u000b', '\u000c' -> lineFeed()
            '\b' -> { if (cursorCol > 0) cursorCol--; wrapPending = false }
            '\t' -> { cursorCol = minOf(cols - 1, (cursorCol / 8 + 1) * 8); wrapPending = false }
            '\u0007', '\u0000', '\u000e', '\u000f' -> {}
            else -> if (c >= ' ') put(c)
        }
    }

    private fun put(c: Char) {
        if (wrapPending) {
            cursorCol = 0
            lineFeed()
        }
        grid[cursorRow][cursorCol] = pen.copy(ch = c)
        if (cursorCol == cols - 1) wrapPending = true else cursorCol++
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == bottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun escape(c: Char) {
        state = State.TEXT
        when (c) {
            '[' -> { params.clear(); state = State.CSI }
            ']' -> state = State.OSC
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> saved = cursorRow to cursorCol
            '8' -> { cursorRow = saved.first; cursorCol = saved.second; wrapPending = false }
            'D' -> lineFeed()
            'E' -> { cursorCol = 0; lineFeed() }
            'M' -> if (cursorRow == top) scrollDown(1) else if (cursorRow > 0) cursorRow--
            'c' -> reset()
        }
    }

    private fun csi(c: Char) {
        if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == '=' || c == ' ') {
            if (params.length < 64) params.append(c)
            return
        }
        state = State.TEXT
        val private = params.startsWith("?")
        val args = params.removePrefix("?").removePrefix(">").removePrefix("=").split(';').map { it.trim().toIntOrNull() }
        fun arg(i: Int, default: Int = 1) = args.getOrNull(i)?.takeIf { it != 0 } ?: default
        wrapPending = false
        when (c) {
            'A' -> cursorRow = maxOf(if (cursorRow >= top) top else 0, cursorRow - arg(0))
            'B' -> cursorRow = minOf(if (cursorRow <= bottom) bottom else rows - 1, cursorRow + arg(0))
            'C' -> cursorCol = minOf(cols - 1, cursorCol + arg(0))
            'D' -> cursorCol = maxOf(0, cursorCol - arg(0))
            'E' -> { cursorRow = minOf(rows - 1, cursorRow + arg(0)); cursorCol = 0 }
            'F' -> { cursorRow = maxOf(0, cursorRow - arg(0)); cursorCol = 0 }
            'G', '`' -> cursorCol = (arg(0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (arg(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { cursorRow = (arg(0) - 1).coerceIn(0, rows - 1); cursorCol = (arg(1) - 1).coerceIn(0, cols - 1) }
            'J' -> eraseDisplay(args.getOrNull(0) ?: 0)
            'K' -> eraseLine(args.getOrNull(0) ?: 0)
            'L' -> if (cursorRow in top..bottom) shift(cursorRow, bottom, -arg(0))
            'M' -> if (cursorRow in top..bottom) shift(cursorRow, bottom, arg(0))
            'S' -> scrollUp(arg(0))
            'T' -> scrollDown(arg(0))
            '@' -> insertChars(arg(0))
            'P' -> deleteChars(arg(0))
            'X' -> for (i in cursorCol until minOf(cols, cursorCol + arg(0))) grid[cursorRow][i] = blankCell()
            'm' -> sgr(args)
            'r' -> {
                val t = (arg(0) - 1).coerceIn(0, rows - 1)
                val b = (arg(1, rows) - 1).coerceIn(0, rows - 1)
                if (t < b) { top = t; bottom = b; cursorRow = 0; cursorCol = 0 }
            }
            's' -> saved = cursorRow to cursorCol
            'u' -> { cursorRow = saved.first; cursorCol = saved.second }
            'h', 'l' -> if (private) mode(args, c == 'h')
        }
    }

    private fun mode(args: List<Int?>, on: Boolean) {
        for (a in args) when (a) {
            25 -> cursorVisible = on
            47, 1047, 1049 -> {
                if (on && alt == null) {
                    if (a == 1049) saved = cursorRow to cursorCol
                    alt = blank(rows)
                } else if (!on && alt != null) {
                    alt = null
                    if (a == 1049) { cursorRow = saved.first; cursorCol = saved.second }
                }
            }
        }
    }

    private fun sgr(args: List<Int?>) {
        var i = 0
        val list = args.map { it ?: 0 }
        while (i < list.size) {
            when (val a = list[i]) {
                0 -> pen = TermCell()
                1 -> pen = pen.copy(bold = true)
                22 -> pen = pen.copy(bold = false)
                7 -> pen = pen.copy(inverse = true)
                27 -> pen = pen.copy(inverse = false)
                in 30..37 -> pen = pen.copy(fg = a - 30)
                in 90..97 -> pen = pen.copy(fg = a - 90 + 8)
                39 -> pen = pen.copy(fg = -1)
                in 40..47 -> pen = pen.copy(bg = a - 40)
                in 100..107 -> pen = pen.copy(bg = a - 100 + 8)
                49 -> pen = pen.copy(bg = -1)
                // 256-color and truecolor: skip their arguments; the palette is 16 colors.
                38, 48 -> i += if (list.getOrNull(i + 1) == 5) 2 else if (list.getOrNull(i + 1) == 2) 4 else 0
            }
            i++
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cursorRow + 1 until rows) clearRow(r) }
            1 -> { eraseLine(1); for (r in 0 until cursorRow) clearRow(r) }
            2, 3 -> for (r in 0 until rows) clearRow(r)
        }
    }

    private fun eraseLine(mode: Int) {
        val range = when (mode) {
            0 -> cursorCol until cols
            1 -> 0..cursorCol
            else -> 0 until cols
        }
        for (i in range) grid[cursorRow][i] = blankCell()
    }

    private fun insertChars(n: Int) {
        val line = grid[cursorRow]
        for (i in cols - 1 downTo cursorCol) line[i] = if (i - n >= cursorCol) line[i - n] else blankCell()
    }

    private fun deleteChars(n: Int) {
        val line = grid[cursorRow]
        for (i in cursorCol until cols) line[i] = if (i + n < cols) line[i + n] else blankCell()
    }

    private fun scrollUp(n: Int) = shift(top, bottom, n)
    private fun scrollDown(n: Int) = shift(top, bottom, -n)

    /** Moves rows [from]..[to] up by [n] (down when negative), filling with blank rows. */
    private fun shift(from: Int, to: Int, n: Int) {
        val g = grid
        val count = minOf(kotlin.math.abs(n), to - from + 1)
        if (n > 0) {
            for (r in from..to) g[r] = if (r + count <= to) g[r + count] else Array(cols) { blankCell() }
        } else {
            for (r in to downTo from) g[r] = if (r - count >= from) g[r - count] else Array(cols) { blankCell() }
        }
    }

    private fun clearRow(r: Int) {
        grid[r] = Array(cols) { blankCell() }
    }

    /** Erased cells keep the current background, as xterm does. */
    private fun blankCell() = TermCell(bg = pen.bg)

    private fun reset() {
        main = blank(rows)
        alt = null
        pen = TermCell()
        top = 0
        bottom = rows - 1
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
    }

    private fun blank(n: Int) = Array(n) { Array(cols) { TermCell() } }

    companion object {
        const val MIN_ROWS = 5
        const val MAX_ROWS = 200
        const val MIN_COLS = 20
        const val MAX_COLS = 400
    }
}
