package controllima.shared

/** Paths of the HTTP API, used by the server and the web client. See docs/plan/api.md. */
object ApiRoutes {
    const val HEALTH = "/health"
    const val VMS = "/api/vms"
    const val CONTAINERS = "/api/containers"
    const val IMAGES = "/api/images"
    const val PORTS = "/api/ports"
    const val STATS_WS = "/ws/stats"

    /** Header carrying the access token; `?token=` works for the first page load only. */
    const val TOKEN_HEADER = "X-ControlLima-Token"

    fun vm(name: String) = "$VMS/$name"
}

/** Names that can never be deleted, whatever the role. Extended in settings. */
object ProtectedNames {
    val defaults: Set<String> = setOf("infra-test")
}
