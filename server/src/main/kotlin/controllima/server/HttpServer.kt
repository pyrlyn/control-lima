package controllima.server

/** Our HTTP server abstraction. The rest of the app never imports Ktor. */
interface HttpServer {
    val port: Int
    fun start(wait: Boolean = false)
    fun stop()
}

data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8787,
    /** Random per launch; required on every /api call (header or ?token= on first load). */
    val token: String,
    val version: String = "0.1.0-SNAPSHOT",
) {
    init {
        require(host == "127.0.0.1" || host == "localhost" || host == "::1") {
            "ControlLima listens on localhost only (got $host); see docs/plan/security.md"
        }
    }

    val allowedOrigins: Set<String> get() = setOf("http://127.0.0.1:$port", "http://localhost:$port")
}
