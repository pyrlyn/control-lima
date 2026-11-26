package controllima.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalScreenTest {
    @Test
    fun printsAndWrapsAtTheLastColumn() {
        val t = TerminalScreen(3, 5)
        t.feed("hello")
        assertEquals(0 to 4, t.cursorRow to t.cursorCol)
        t.feed("!")
        assertEquals("hello\n!", t.text())
    }

    @Test
    fun scrollsWhenTheBottomIsReached() {
        val t = TerminalScreen(2, 10)
        t.feed("one\r\ntwo\r\nthree")
        assertEquals("two\nthree", t.text())
    }

    @Test
    fun cursorMovesAndErases() {
        val t = TerminalScreen(3, 10)
        t.feed("abcdef\u001b[1;3H\u001b[K")
        assertEquals("ab", t.text())
        t.feed("\u001b[2J\u001b[2;2Hx")
        assertEquals("\n x", t.text())
    }

    @Test
    fun colorsAndUnknownSequencesNeverPrint() {
        val t = TerminalScreen(2, 20)
        t.feed("\u001b]0;title\u0007\u001b[1;31mred\u001b[0m \u001b[38;5;200mx\u001b[?2004h")
        assertEquals("red x", t.text())
        val red = t.row(0)[0]
        assertEquals(1, red.fg)
        assertTrue(red.bold)
        assertEquals(-1, t.row(0)[3].fg)
    }

    @Test
    fun alternateScreenRestoresTheShell() {
        val t = TerminalScreen(3, 10)
        t.feed("$ vi\r\n")
        t.feed("\u001b[?1049h\u001b[?25lEDITOR")
        // xterm keeps the cursor where it was when it switches screens; the app moves it itself.
        assertEquals("\nEDITOR", t.text())
        assertFalse(t.cursorVisible)
        t.feed("\u001b[?1049l\u001b[?25h")
        assertEquals("$ vi", t.text())
        assertEquals(1 to 0, t.cursorRow to t.cursorCol)
    }

    @Test
    fun scrollRegionKeepsTheStatusLine() {
        val t = TerminalScreen(3, 10)
        t.feed("\u001b[3;1Hstatus\u001b[1;2r\u001b[1;1Ha\r\nb\r\nc")
        assertEquals("b\nc\nstatus", t.text())
    }
}
