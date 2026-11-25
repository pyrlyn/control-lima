package controllima.providers.process

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
