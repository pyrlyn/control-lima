package controllima.cli

import controllima.providers.lima.LimactlProvider
import controllima.providers.process.ProcessCommandRunner
import controllima.server.Api
import controllima.server.KtorHttpServer
import controllima.server.ServerConfig
import java.security.SecureRandom
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        CliOptions.parse(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(CliOptions.usage)
        exitProcess(2)
    }
    if (options.help) {
        println(CliOptions.usage)
        return
    }
    val token = newToken()
    val config = ServerConfig(port = options.port, token = token)
    val lima = LimactlProvider(ProcessCommandRunner(), limaHome = options.limaHome, extraArgs = options.passthrough)
    val server = KtorHttpServer(config, Api(config, lima))
    val url = "http://127.0.0.1:${options.port}/?token=$token"
    println("ControlLima on $url")
    if (options.open) openBrowser(url)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start(wait = true)
}

private fun newToken(): String {
    val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun openBrowser(url: String) {
    val command = if (System.getProperty("os.name").startsWith("Mac")) "open" else "xdg-open"
    runCatching { ProcessBuilder(command, url).start() }
}
