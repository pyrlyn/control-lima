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

/** A long-lived process (e.g. an SSH session streaming guest stats). Closing it kills the process tree. */
interface StreamHandle : AutoCloseable {
    val alive: Boolean
}

/** Our interface for running a CLI. Providers depend on this, tests pass a fake. */
interface CommandRunner {
    /**
     * Runs [command] (an argument list, never a shell string) and waits at most [timeout].
     * [onLine] receives stdout and stderr lines as they arrive, for progress of long actions.
     */
    fun run(
        command: List<String>,
        timeout: Duration = 10.seconds,
        env: Map<String, String> = emptyMap(),
        stdin: String? = null,
        onLine: ((String) -> Unit)? = null,
    ): CommandResult

    /** Starts [command] and delivers stdout lines to [onLine] until it exits or the handle is closed. */
    fun stream(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
        onExit: (Int?) -> Unit = {},
    ): StreamHandle = throw UnsupportedOperationException("streaming not supported by ${this::class.simpleName}")
}

/** [CommandRunner] on top of [ProcessBuilder]. Kills the process tree when the timeout passes. */
class ProcessCommandRunner : CommandRunner {
    override fun run(
        command: List<String>,
        timeout: Duration,
        env: Map<String, String>,
        stdin: String?,
        onLine: ((String) -> Unit)?,
    ): CommandResult {
        val process = start(command, env)
        try {
            process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        } catch (_: java.io.IOException) {
            // The process may exit before reading stdin; its exit code tells the story.
        }
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val outThread = reader(process.inputStream, stdout, onLine)
        val errThread = reader(process.errorStream, stderr, onLine)
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) killTree(process)
        outThread.join(1_000)
        errThread.join(1_000)
        return CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = !finished,
        )
    }

    override fun stream(command: List<String>, env: Map<String, String>, onLine: (String) -> Unit, onExit: (Int?) -> Unit): StreamHandle {
        val process = start(command, env)
        process.outputStream.close()
        // stderr is drained so a chatty process never blocks on a full pipe.
        Thread({ process.errorStream.bufferedReader().use { r -> r.lineSequence().forEach { } } }, "stream-err").apply { isDaemon = true; start() }
        Thread({
            runCatching { process.inputStream.bufferedReader().use { r -> r.lineSequence().forEach(onLine) } }
            val code = runCatching { process.waitFor() }.getOrNull()
            onExit(code)
        }, "stream-out").apply { isDaemon = true; start() }
        return object : StreamHandle {
            override val alive: Boolean get() = process.isAlive
            override fun close() = killTree(process)
        }
    }

    private fun start(command: List<String>, env: Map<String, String>): Process {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(env)
        return builder.start()
    }

    private fun reader(stream: java.io.InputStream, sink: StringBuffer, onLine: ((String) -> Unit)?): Thread =
        Thread {
            stream.bufferedReader().use { r ->
                r.lineSequence().forEach { line ->
                    sink.append(line).append('\n')
                    onLine?.let { cb -> runCatching { cb(line) } }
                }
            }
        }.apply { isDaemon = true; start() }

    private fun killTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
