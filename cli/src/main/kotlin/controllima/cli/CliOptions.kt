package controllima.cli

/**
 * Parsed command line. Everything after `--` is passed as-is to limactl and colima,
 * e.g. `control-lima --port 9000 -- --debug`.
 */
data class CliOptions(
    val port: Int = 8787,
    val limaHome: String? = null,
    val colimaProfile: String = "default",
    val open: Boolean = false,
    val help: Boolean = false,
    val passthrough: List<String> = emptyList(),
) {
    companion object {
        val usage = """
            Usage: control-lima [options] [-- <args passed to limactl/colima>]
              --port <n>              HTTP port on 127.0.0.1 (default 8787)
              --lima-home <dir>       LIMA_HOME for limactl (default: limactl's own)
              --colima-profile <name> Colima profile for the Docker socket (default: default)
              --open                  open the browser after start
              -h, --help              show this help
        """.trimIndent()

        fun parse(args: List<String>): CliOptions {
            var options = CliOptions()
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i) ?: throw IllegalArgumentException("$flag needs a value")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--" -> return options.copy(passthrough = args.drop(i + 1))
                    "--port" -> options = options.copy(port = value(arg).toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw IllegalArgumentException("--port must be 1..65535"))
                    "--lima-home" -> options = options.copy(limaHome = value(arg))
                    "--colima-profile" -> options = options.copy(colimaProfile = value(arg))
                    "--open" -> options = options.copy(open = true)
                    "-h", "--help" -> options = options.copy(help = true)
                    else -> throw IllegalArgumentException("Unknown option $arg (pass limactl/colima flags after --)")
                }
                i++
            }
            return options
        }
    }
}
