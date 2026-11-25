package controllima.providers.process

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = !timedOut && exitCode == 0
}

/** Our interface for running a CLI. Providers depend on this, tests pass a fake. */
interface CommandRunner {
    fun run(command: List<String>, timeout: Duration = 10.seconds, env: Map<String, String> = emptyMap()): CommandResult
}

/** [CommandRunner] on top of [ProcessBuilder]. Kills the process tree when the timeout passes. */
class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, timeout: Duration, env: Map<String, String>): CommandResult {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { stdout.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
        val errThread = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        outThread.join(1_000)
        errThread.join(1_000)
        return CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = !finished,
        )
    }
}
