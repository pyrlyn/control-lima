package controllima.providers.process

import kotlin.time.Duration

/**
 * Fake [CommandRunner] for tests: answers by the first rule whose key is contained in the joined
 * command line, records every call, and never starts a process.
 */
class ScriptedRunner(
    private val rules: List<Pair<String, CommandResult>> = emptyList(),
    private val fallback: CommandResult = CommandResult(0, "", "", false),
) : CommandRunner {
    val calls = mutableListOf<List<String>>()
    val stdins = mutableListOf<String?>()
    val streams = mutableListOf<List<String>>()
    var streamLines: List<String> = emptyList()
    val interactives = mutableListOf<Pair<List<String>, Map<String, String>>>()
    /** What the fake terminal prints first; afterwards it echoes every write back, like a pty with echo on. */
    var banner: String = ""

    override fun run(command: List<String>, timeout: Duration, env: Map<String, String>, stdin: String?, onLine: ((String) -> Unit)?): CommandResult {
        synchronized(this) { calls += command; stdins += stdin }
        val line = command.joinToString(" ")
        val result = rules.firstOrNull { line.contains(it.first) }?.second ?: fallback
        onLine?.let { cb -> result.stdout.lines().filter { it.isNotEmpty() }.forEach(cb) }
        return result
    }

    override fun stream(command: List<String>, env: Map<String, String>, onLine: (String) -> Unit, onExit: (Int?) -> Unit): StreamHandle {
        synchronized(this) { streams += command }
        streamLines.forEach(onLine)
        onExit(0)
        return object : StreamHandle {
            override val alive = false
            override fun close() {}
        }
    }

    override fun interactive(command: List<String>, env: Map<String, String>, onOutput: (String) -> Unit, onExit: (Int?) -> Unit): InteractiveProcess {
        synchronized(this) { interactives += command to env }
        if (banner.isNotEmpty()) onOutput(banner)
        return object : InteractiveProcess {
            @Volatile override var alive = true
            override fun write(text: String): Boolean {
                if (!alive) return false
                onOutput(text)
                return true
            }
            override fun close() {
                if (alive) { alive = false; onExit(null) }
            }
        }
    }

    companion object {
        fun ok(stdout: String = "") = CommandResult(0, stdout, "", false)
        fun fail(stderr: String = "boom", code: Int = 1) = CommandResult(code, "", stderr, false)
        val timeout = CommandResult(null, "", "", true)
    }
}
