package controllima.cli

import controllima.server.ControlLimaServer
import controllima.server.DataPaths
import controllima.server.Overrides
import controllima.server.ServerConfig
import controllima.server.security.Secrets
import kotlin.system.exitProcess

/** Exit codes from docs/plan/cli-tui.md. */
object Exit {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
    const val UNAVAILABLE = 3
}

class UsageError(message: String) : RuntimeException(message)
class UnavailableError(message: String) : RuntimeException(message)

fun main(args: Array<String>) {
    val options = try {
        CliOptions.parse(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(CliOptions.usage)
        exitProcess(Exit.USAGE)
    }
    if (options.help || options.command == "help") {
        println(CliOptions.usage)
        return
    }
    val code = try {
        when (options.command) {
            null, "serve" -> serve(options)
            else -> Commands(options).run()
        }
    } catch (e: UsageError) {
        System.err.println(e.message)
        Exit.USAGE
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        Exit.USAGE
    } catch (e: UnavailableError) {
        System.err.println(e.message)
        Exit.UNAVAILABLE
    } catch (e: Exception) {
        System.err.println("Error: ${e.message ?: e::class.simpleName}")
        Exit.ERROR
    }
    exitProcess(code)
}

fun paths(options: CliOptions) = DataPaths.default(dataDirOverride = options.dataDir)

fun overrides(options: CliOptions) = Overrides(options.limaHome, options.colimaProfile, options.passthrough)

private fun serve(options: CliOptions): Int {
    // 192-bit launch token (docs/plan/auth.md); it lives only in this process and the printed URL.
    val token = Secrets.randomHex(24)
    val config = ServerConfig(port = options.port, token = token)
    val server = ControlLimaServer(config, paths(options), overrides(options))
    val url = "http://127.0.0.1:${options.port}/?token=$token"
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    try {
        server.start(wait = false)
    } catch (e: Exception) {
        server.close()
        throw IllegalStateException("Cannot listen on 127.0.0.1:${options.port}: ${e.message}")
    }
    println("ControlLima ${config.version} on $url")
    if (options.open) server.services.backends.host.openBrowser(url)
    Thread.currentThread().join()
    return Exit.OK
}
