package controllima.shared

/** Paths of the HTTP API, used by the server and the web client. See docs/plan/api.md. */
object ApiRoutes {
    const val HEALTH = "/health"
    const val SESSION = "/api/session"
    const val LOGIN = "/api/login"
    const val LOGOUT = "/api/logout"
    const val VMS = "/api/vms"
    const val CONTAINERS = "/api/containers"
    const val IMAGES = "/api/images"
    const val PORTS = "/api/ports"
    const val NETWORKS = "/api/networks"
    const val ACTIONS = "/api/actions"
    const val METRICS = "/api/metrics"
    const val METRICS_TRENDS = "/api/metrics/trends"
    const val METRICS_FORGET = "/api/metrics/forget"
    const val ALERT_RULES = "/api/alerts/rules"
    const val ALERT_EVENTS = "/api/alerts/events"
    const val ALERTS = "/api/alerts"
    const val CHANNELS = "/api/notifications/channels"
    const val NOTIFY_TEST = "/api/notifications/test"
    const val LOG_SOURCES = "/api/logs/sources"
    const val LOGS = "/api/logs"
    const val BACKUPS = "/api/backups"
    const val TEMPLATES = "/api/templates"
    const val DISK = "/api/disk"
    const val CLEANUP = "/api/disk/cleanup"
    const val SETTINGS = "/api/settings"
    const val TOKENS = "/api/tokens"
    const val USERS = "/api/users"
    const val AUDIT = "/api/audit"
    const val STATS_WS = "/ws/stats"
    const val EVENTS_WS = "/ws/events"
    const val LOGS_WS = "/ws/logs"

    /** Header carrying the access token; `?token=` works for the first page load and WebSockets only. */
    const val TOKEN_HEADER = "X-ControlLima-Token"
    const val CSRF_HEADER = "X-CSRF-Token"
    const val SESSION_COOKIE = "controllima_session"

    fun vm(ref: VmRef): String = "$VMS/${ref.source.slug}/${encode(ref.name)}" + homeQuery(ref.home)
    fun vmAction(ref: VmRef, action: String): String = "$VMS/${ref.source.slug}/${encode(ref.name)}/$action" + homeQuery(ref.home)
    fun container(id: String, profile: String) = "$CONTAINERS/${encode(id)}?profile=${encode(profile)}"
    fun containerAction(id: String, profile: String, action: String) = "$CONTAINERS/${encode(id)}/$action?profile=${encode(profile)}"

    private fun homeQuery(home: String?) = if (home == null) "" else "?home=${encode(home)}"

    /** Percent-encoding for path segments and query values (RFC 3986 unreserved set kept). */
    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0 || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else append('%').append(((byte.toInt() and 0xff) or 0x100).toString(16).substring(1).uppercase())
        }
    }
}

/** Names that can never be deleted, whatever the role. Extended in settings. */
object ProtectedNames {
    val defaults: Set<String> = setOf("infra-test")
}

/** Version shared by server and web; the web app refuses to run against a different server version. */
object BuildInfo {
    const val VERSION = "0.1.0-SNAPSHOT"
}

/**
 * Target ids used by stats, metrics, alerts and logs:
 * `vm:lima/dev`, `vm:lima/dev@/path/to/home`, `vm:colima/default`, `container:default/<id>`.
 */
object Targets {
    fun vm(ref: VmRef): String = "vm:${ref.source.slug}/${ref.name}" + (ref.home?.let { "@$it" } ?: "")
    fun container(profile: String, id: String): String = "container:$profile/$id"

    fun parseVm(target: String): VmRef? {
        if (!target.startsWith("vm:")) return null
        val rest = target.removePrefix("vm:")
        val at = rest.indexOf('@')
        val main = if (at >= 0) rest.substring(0, at) else rest
        val home = if (at >= 0) rest.substring(at + 1) else null
        val source = VmSource.fromSlug(main.substringBefore('/')) ?: return null
        val name = main.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
        return VmRef(source, name, home)
    }

    /** (profile, id) of a container target. */
    fun parseContainer(target: String): Pair<String, String>? {
        if (!target.startsWith("container:")) return null
        val rest = target.removePrefix("container:")
        val profile = rest.substringBefore('/', "")
        val id = rest.substringAfter('/', "")
        return if (profile.isEmpty() || id.isEmpty()) null else profile to id
    }

    fun label(target: String): String =
        parseVm(target)?.let { "${it.name} (${it.source.slug})" }
            ?: parseContainer(target)?.let { "container ${it.second.take(12)}" }
            ?: target
}

/** Lima's instance name rule; names are validated before they reach any command line. */
object Names {
    private val limaName = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")
    private val containerName = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")
    private val containerId = Regex("^[a-f0-9]{6,64}$")

    fun isValidVmName(name: String): Boolean = name.length <= 64 && limaName.matches(name)
    fun isValidContainerName(name: String): Boolean = name.length <= 128 && containerName.matches(name)
    fun isValidContainerRef(ref: String): Boolean = containerId.matches(ref) || isValidContainerName(ref)
}
