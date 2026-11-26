package controllima.providers.host

import controllima.providers.process.ScriptedRunner
import controllima.shared.HostListener
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostProviderTest {
    private val lsof = javaClass.getResource("/lsof.txt")!!.readText()

    @Test
    fun parsesLsofFields() {
        assertEquals(
            listOf(
                HostListener(717, "ControlCenter", "*", 7000),
                HostListener(4242, "limactl", "127.0.0.1", 8443),
                HostListener(4242, "limactl", "::1", 8443),
                HostListener(99, "ssh", "127.0.0.1", 60022),
            ),
            Lsof.parse(lsof),
        )
    }

    @Test
    fun listenersUseFieldOutputAndTolerateNoMatches() {
        val runner = ScriptedRunner(fallback = ScriptedRunner.ok(lsof))
        assertEquals(4, LocalHostProvider(runner, "Mac OS X").listeners().size)
        assertEquals(listOf("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pcn"), runner.calls.single())
        assertEquals(emptyList(), LocalHostProvider(ScriptedRunner(fallback = ScriptedRunner.fail("", 1)), "Linux").listeners())
        assertFailsWith<HostException> { LocalHostProvider(ScriptedRunner(fallback = ScriptedRunner.timeout), "Linux").listeners() }
    }

    @Test
    fun notificationTextIsPassedAsArguments() {
        val runner = ScriptedRunner()
        assertTrue(LocalHostProvider(runner, "Mac OS X").notify("VM down", "\" & do shell script \"rm -rf ~"))
        val cmd = runner.calls.single()
        assertEquals("osascript", cmd[0])
        assertEquals("\" & do shell script \"rm -rf ~", cmd.last())
        assertTrue(cmd.none { it.contains("rm -rf") && it.contains("display notification") })
        LocalHostProvider(runner, "Linux").notify("t", "m")
        assertEquals(listOf("notify-send", "--", "t", "m"), runner.calls.last())
    }

    @Test
    fun processStatsParsesTwoNumbers() {
        val host = LocalHostProvider(ScriptedRunner(fallback = ScriptedRunner.ok(" 12,5  2048\n")), "Mac OS X")
        assertEquals(ProcessStats(12.5, 2048 * 1024), host.processStats(42))
        assertNull(host.processStats(0))
    }

    @Test
    fun fileSizeUsesAllocatedBlocks() {
        val f = File.createTempFile("disk", ".img").apply { writeBytes(ByteArray(10_000)) }
        val runner = ScriptedRunner(fallback = ScriptedRunner.ok("8\n"))
        assertEquals(FileSize(4096, 10_000), LocalHostProvider(runner, "Linux").fileSize(f.path))
        assertEquals(listOf("stat", "-c", "%b", f.path), runner.calls.single())
        assertNull(LocalHostProvider(runner, "Linux").fileSize("/nonexistent"))
        f.delete()
    }

    @Test
    fun diskSpaceWalksUpToAnExistingDir() {
        val space = LocalHostProvider(ScriptedRunner()).diskSpace("/nonexistent/deeper")
        assertTrue(space.totalBytes > 0)
    }

    @Test
    fun keychainKeepsSecretsOffTheCommandLine() {
        val runner = ScriptedRunner(listOf("find-generic-password" to ScriptedRunner.ok("tok123\n")))
        val store = KeychainSecretStore(runner)
        store.put("telegram", "tok123")
        assertEquals(listOf("security", "-i"), runner.calls[0])
        assertTrue(runner.stdins[0]!!.contains("tok123"))
        assertEquals("tok123", store.get("telegram"))
        assertFailsWith<IllegalArgumentException> { store.put("x", "a\"b") }
        assertFailsWith<IllegalArgumentException> { store.get("bad key") }
        assertFalse(runner.calls.any { c -> c.any { it.contains("tok123") } })
    }

    @Test
    fun fileStoreIsPrivate() {
        val dir = Files.createTempDirectory("secrets").toFile()
        val store = FileSecretStore(File(dir, "secrets.properties"))
        store.put("slack", "https://hooks.example/x")
        assertEquals("https://hooks.example/x", store.get("slack"))
        val perms = Files.getPosixFilePermissions(File(dir, "secrets.properties").toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
        store.delete("slack")
        assertNull(store.get("slack"))
        dir.deleteRecursively()
    }
}
