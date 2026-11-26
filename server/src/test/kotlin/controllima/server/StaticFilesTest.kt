package controllima.server

import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StaticFilesTest {
    private val files = run {
        val root = Files.createTempDirectory("static")
        val web = root.resolve("web").createDirectories()
        web.resolve("index.html").writeText("<html></html>")
        web.resolve("controlLima.js").writeText("js")
        web.resolve("0123abcd.wasm").writeText("wasm")
        StaticFiles(URLClassLoader(arrayOf(root.toUri().toURL()), null))
    }

    @Test
    fun appRoutesGetTheIndex() {
        val r = files.serve("/vms")!!
        assertEquals("<html></html>", r.bytes.decodeToString())
        assertEquals("text/html; charset=utf-8", r.contentType)
    }

    @Test
    fun onlyHashedWasmIsCachedLong() {
        // The JS loader keeps its name across releases, so a cached copy would load the old UI.
        assertEquals("no-cache", files.serve("/controlLima.js")!!.headers["Cache-Control"])
        assertEquals("no-cache", files.serve("/")!!.headers["Cache-Control"])
        assertEquals("max-age=3600", files.serve("/0123abcd.wasm")!!.headers["Cache-Control"])
    }

    @Test
    fun traversalAndHiddenFilesAreRefused() {
        assertNull(files.serve("/../secret.txt"))
        assertNull(files.serve("/.env"))
        assertNull(files.serve("/a\\b.js"))
        assertNull(files.serve("/missing.js"))
    }
}
