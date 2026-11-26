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

/** A process with a writable stdin, such as an `ssh -tt` login shell. Closing it kills the process tree. */
interface InteractiveProcess : StreamHandle {
    /** Writes UTF-8 text to stdin; false once the process is gone. */
    fun write(text: String): Boolean
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

    /**
     * Starts [command] with stdin open. Output (stdout and stderr merged) goes to [onOutput] in chunks as it
     * arrives, not in lines, because a terminal prints prompts and escape sequences without newlines.
     */
    fun interactive(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit,
        onExit: (Int?) -> Unit = {},
    ): InteractiveProcess = throw UnsupportedOperationException("interactive processes not supported by ${this::class.simpleName}")
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

    override fun interactive(command: List<String>, env: Map<String, String>, onOutput: (String) -> Unit, onExit: (Int?) -> Unit): InteractiveProcess {
        val process = start(command, env, mergeStderr = true)
        val stdin = process.outputStream
        Thread({
            // The reader decodes UTF-8 across chunk boundaries, so a split multi-byte character is never mangled.
            runCatching {
                process.inputStream.reader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        onOutput(String(buf, 0, n))
                    }
                }
            }
            val code = runCatching { process.waitFor() }.getOrNull()
            onExit(code)
        }, "interactive-out").apply { isDaemon = true; start() }
        return object : InteractiveProcess {
            override val alive: Boolean get() = process.isAlive
            override fun write(text: String): Boolean = runCatching {
                synchronized(stdin) {
                    stdin.write(text.toByteArray(Charsets.UTF_8))
                    stdin.flush()
                }
            }.isSuccess
            override fun close() = killTree(process)
        }
    }

    private fun start(command: List<String>, env: Map<String, String>, mergeStderr: Boolean = false): Process {
        val builder = ProcessBuilder(command).redirectErrorStream(mergeStderr)
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
