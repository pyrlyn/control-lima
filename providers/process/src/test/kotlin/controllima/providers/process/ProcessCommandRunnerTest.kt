package controllima.providers.process

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessCommandRunnerTest {
    private val runner = ProcessCommandRunner()

    @Test
    fun capturesStdout() {
        val result = runner.run(listOf("sh", "-c", "echo hello"), 5.seconds)
        assertTrue(result.ok)
        assertEquals("hello", result.stdout.trim())
    }

    @Test
    fun killsOnTimeout() {
        val result = runner.run(listOf("sh", "-c", "sleep 5"), 200.milliseconds)
        assertTrue(result.timedOut)
        assertEquals(null, result.exitCode)
    }

    @Test
    fun passesStdinAndReportsLines() {
        val lines = mutableListOf<String>()
        val result = runner.run(listOf("sh", "-c", "cat; echo done >&2"), 5.seconds, stdin = "a\nb\n", onLine = { synchronized(lines) { lines += it } })
        assertTrue(result.ok)
        assertEquals("a\nb\n", result.stdout)
        assertEquals(setOf("a", "b", "done"), synchronized(lines) { lines.toSet() })
    }

    @Test
    fun argumentsAreNotInterpretedByAShell() {
        val result = runner.run(listOf("echo", "a;", "echo", "b"), 5.seconds)
        assertEquals("a; echo b", result.stdout.trim())
    }

    @Test
    fun streamDeliversLinesAndCanBeClosed() {
        val first = CountDownLatch(1)
        val handle = runner.stream(listOf("sh", "-c", "while :; do echo tick; sleep 0.1; done"), onLine = { first.countDown() })
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertTrue(handle.alive)
        handle.close()
        Thread.sleep(200)
        assertFalse(handle.alive)
    }
}
