package controllima.cli

/**
 * Parsed command line. Global flags work before or after the command; everything after `--` is
 * passed as-is to limactl and colima, e.g. `control-lima --port 9000 -- --debug`.
 */
data class CliOptions(
    val command: String? = null,
    val commandArgs: List<String> = emptyList(),
    val port: Int = 8787,
    val limaHome: String? = null,
    val colimaProfile: String? = null,
    val dataDir: String? = null,
    val open: Boolean = false,
    val help: Boolean = false,
    val passthrough: List<String> = emptyList(),
) {
    companion object {
        val COMMANDS = setOf("serve", "status", "token", "backup", "restore", "top", "help")

        val usage = """
            Usage: control-lima [command] [options] [-- <args passed to limactl/colima>]

            Commands:
              serve (default)                       start the server on 127.0.0.1 and print the URL
              status [--json]                       VMs and containers
              token create --name <n> --role viewer|operator|admin [--expires <days>d]
              token list | token revoke <id|name>
              backup [vm...]                        back up VM configs and ControlLima settings
              restore <backup-id> --target <vm> [--target <vm>...] --confirm <name> [--new-name <n>]
              top                                   live load in the terminal (Ctrl-C to quit)

            Options:
              --port <n>              HTTP port on 127.0.0.1 (default 8787)
              --lima-home <dir>       LIMA_HOME for limactl (default: limactl's own)
              --colima-profile <name> default Colima profile (default: from settings)
              --data-dir <dir>        data directory (default: ~/.local/share/control-lima)
              --open                  open the browser after start
              -h, --help              show this help

            Exit codes: 0 ok, 1 error, 2 usage, 3 Lima/Colima unavailable.
        """.trimIndent()

        fun parse(args: List<String>): CliOptions {
            var options = CliOptions()
            val rest = mutableListOf<String>()
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i)?.takeIf { it != "--" } ?: throw IllegalArgumentException("$flag needs a value")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--" -> {
                        options = options.copy(passthrough = args.drop(i + 1))
                        break
                    }
                    "--port" -> options = options.copy(port = value(arg).toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw IllegalArgumentException("--port must be 1..65535"))
                    "--lima-home" -> options = options.copy(limaHome = value(arg).also { require(it.startsWith("/")) { "--lima-home must be an absolute path" } })
                    "--colima-profile" -> options = options.copy(colimaProfile = value(arg))
                    "--data-dir" -> options = options.copy(dataDir = value(arg).also { require(it.startsWith("/")) { "--data-dir must be an absolute path" } })
                    "--open" -> options = options.copy(open = true)
                    "-h", "--help" -> options = options.copy(help = true)
                    else -> when {
                        options.command == null && !arg.startsWith("-") -> {
                            require(arg in COMMANDS) { "Unknown command $arg" }
                            options = options.copy(command = arg)
                        }
                        options.command != null -> rest += arg
                        else -> throw IllegalArgumentException("Unknown option $arg (pass limactl/colima flags after --)")
                    }
                }
                i++
            }
            return options.copy(commandArgs = rest)
        }
    }
}

/** Flags of one command: `--name value`, `--json`, and positionals. */
class CommandArgs(args: List<String>, valueFlags: Set<String>, boolFlags: Set<String> = emptySet()) {
    val values = mutableMapOf<String, MutableList<String>>()
    val flags = mutableSetOf<String>()
    val positionals = mutableListOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in valueFlags -> values.getOrPut(a) { mutableListOf() } += args.getOrNull(++i) ?: throw IllegalArgumentException("$a needs a value")
                a in boolFlags -> flags += a
                a.startsWith("-") -> throw IllegalArgumentException("Unknown option $a")
                else -> positionals += a
            }
            i++
        }
    }

    fun value(name: String): String? = values[name]?.lastOrNull()
    fun all(name: String): List<String> = values[name].orEmpty()
}
