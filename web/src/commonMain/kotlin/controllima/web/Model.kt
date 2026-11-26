package controllima.web

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import controllima.shared.Names
import controllima.shared.ActionState
import controllima.shared.AlertEvent
import controllima.shared.AlertState
import controllima.shared.ApiClient
import controllima.shared.ApiException
import controllima.shared.ApiJson
import controllima.shared.ApiRoutes
import controllima.shared.BuildInfo
import controllima.shared.ContainerList
import controllima.shared.EventType
import controllima.shared.Role
import controllima.shared.SampleWindow
import controllima.shared.ServerEvent
import controllima.shared.Session
import controllima.shared.Settings
import controllima.shared.SizeUnits
import controllima.shared.Targets
import controllima.shared.Theme
import controllima.shared.StatsBatch
import controllima.shared.StatsSample
import controllima.shared.StatsSubscription
import controllima.shared.VmList
import controllima.shared.VmRef
import controllima.shared.VmSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the main area shows. The hash in the address bar mirrors it, so Back works. */
sealed class Screen {
    data class Main(val page: Page) : Screen()
    data class VmDetail(val ref: VmRef) : Screen()
    data class ContainerDetail(val profile: String, val id: String) : Screen()
    data class Logs(val source: String?) : Screen()

    fun hash(): String = when (this) {
        is Main -> "#/${page.doc}"
        is VmDetail -> "#/vm/${ref.source.slug}/${ApiRoutes.encode(ref.name)}" + (ref.home?.let { "?home=${ApiRoutes.encode(it)}" } ?: "")
        is ContainerDetail -> "#/container/${ApiRoutes.encode(profile)}/${ApiRoutes.encode(id)}"
        is Logs -> "#/logs" + (source?.let { "?source=${ApiRoutes.encode(it)}" } ?: "")
    }

    companion object {
        fun parse(hash: String): Screen {
            val raw = hash.removePrefix("#").removePrefix("/")
            val path = raw.substringBefore('?')
            val query = raw.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { ApiRoutes.decode(it.substringBefore('=')) to ApiRoutes.decode(it.substringAfter('=')) }
            val parts = path.split('/').map(ApiRoutes::decode)
            return when {
                parts.size == 3 && parts[0] == "vm" -> VmSource.fromSlug(parts[1])?.let { VmDetail(VmRef(it, parts[2], query["home"])) }
                parts.size == 3 && parts[0] == "container" -> ContainerDetail(parts[1], parts[2])
                parts[0] == "logs" -> Logs(query["source"])
                else -> Page.entries.firstOrNull { it.doc == parts[0] }?.let { Main(it) }
            } ?: Main(Page.Overview)
        }
    }
}

/** App-wide state: session, inventory, live stats and events. Pages read it and call [api]. */
/** [onSignOut] lets the browser drop the launch token it kept for reloads. */
class AppModel(val api: ApiClient, val scope: CoroutineScope, private val onSignOut: () -> Unit = {}) {
    val snackbar = SnackbarHostState()

    /** Set by the page that has a search field, for the `/` shortcut. */
    var searchFocus: androidx.compose.ui.focus.FocusRequester? = null

    var session by mutableStateOf<Session?>(null)
    var authProblem by mutableStateOf<AuthProblem?>(null)
    var screen by mutableStateOf(Screen.parse(currentHash()))
        private set

    var vms by mutableStateOf<VmList?>(null)
    var vmsError by mutableStateOf<String?>(null)
    var vmsAt by mutableStateOf(0L)
    var containerLists by mutableStateOf<Map<String, ContainerList>>(emptyMap())
    /** From the server: Colima profiles plus `lima:<vm>` for Lima VMs that have nerdctl. */
    var containerProfiles by mutableStateOf<List<String>>(emptyList())
    var firing by mutableStateOf<List<AlertEvent>>(emptyList())
    var units by mutableStateOf(SizeUnits.BINARY)
    var theme by mutableStateOf(Theme.SYSTEM)
    var webTerminal by mutableStateOf(false)
    var serverVersion by mutableStateOf<String?>(null)
    var connected by mutableStateOf(true)

    val events = mutableStateListOf<ServerEvent>()

    /** Bumped on every stats batch; composables read it to redraw from [windows]. */
    var statsTick by mutableStateOf(0)
    private val windows = mutableMapOf<String, SampleWindow>()
    private val watchCounts = mutableMapOf<String, Int>()

    private var statsSocket: LiveSocket? = null
    private var eventsSocket: LiveSocket? = null
    private var loops: Job? = null

    enum class AuthProblem { MISSING_TOKEN, LOGIN_REQUIRED }

    val role: Role get() = session?.role ?: Role.VIEWER
    fun can(required: Role) = role.allows(required)
    val protectedNames: Set<String> get() = session?.protectedNames?.toSet().orEmpty()

    fun navigate(to: Screen) {
        screen = to
        val hash = to.hash()
        if (currentHash() != hash) pushHash(hash)
    }

    fun onHashChange() {
        screen = Screen.parse(currentHash())
    }

    // ---------- startup ----------

    /** Like [Targets.label], but a container shows its name once its profile's list is loaded. */
    fun label(target: String): String {
        val (profile, _) = Targets.parseContainer(target) ?: return Targets.label(target)
        val name = containerLists[profile]?.containers?.firstOrNull { it.targetId == target }?.name ?: return Targets.label(target)
        return "$name ($profile)"
    }

    /** Re-reads the session after a change it carries, such as the protected names. */
    suspend fun refreshSession() {
        session = api.session()
    }

    fun start() {
        scope.launch {
            try {
                session = api.session()
                authProblem = null
                afterLogin()
            } catch (e: ApiException) {
                authProblem = when {
                    e.status == 401 && api.token.isEmpty() -> AuthProblem.LOGIN_REQUIRED
                    e.status == 401 -> AuthProblem.MISSING_TOKEN
                    else -> null
                }
                if (authProblem == null) toast(e.message ?: "Cannot load the session")
            }
        }
    }

    suspend fun login(username: String, password: String) {
        session = api.login(username, password)
        authProblem = null
        afterLogin()
    }

    fun logout() {
        scope.launch {
            runCatching { api.logout() }
            onSignOut()
            api.token = ""
            stopLive()
            session = null
            authProblem = AuthProblem.LOGIN_REQUIRED
        }
    }

    /** Display preferences take effect at once, without a reload. */
    fun applySettings(settings: Settings) {
        units = settings.units
        theme = settings.theme
        webTerminal = settings.webTerminal
    }

    private fun afterLogin() {
        scope.launch { runCatching { api.settings() }.onSuccess { applySettings(it.settings) } }
        startLive()
    }

    private fun startLive() {
        stopLive()
        statsSocket = LiveSocket(ApiRoutes.STATS_WS, { api.token }, ::onStats, onOpen = { socket ->
            // A reconnect starts a fresh server session: subscribe again to everything watched.
            if (watchCounts.isNotEmpty()) socket.send(subscription(StatsSubscription(subscribe = watchCounts.keys.toList())))
        }).open()
        eventsSocket = LiveSocket(ApiRoutes.EVENTS_WS, { api.token }, ::onEvent, onState = { connected = it }).open()
        loops = scope.launch {
            launch {
                while (isActive) {
                    refreshVms()
                    delay(5_000)
                }
            }
            launch {
                while (isActive) {
                    refreshAlerts()
                    checkVersion()
                    delay(30_000)
                }
            }
        }
    }

    private fun stopLive() {
        statsSocket?.close()
        eventsSocket?.close()
        loops?.cancel()
    }

    // ---------- data ----------

    suspend fun refreshVms() {
        try {
            vms = api.vms()
            vmsError = null
            vmsAt = Clock.now()
            runCatching { api.containerProfiles() }.onSuccess { containerProfiles = it }
        } catch (e: ApiException) {
            vmsError = e.message
            if (e.status == 401) authProblem = AuthProblem.MISSING_TOKEN
        }
    }

    suspend fun refreshAlerts() {
        runCatching { api.alertEvents() }.onSuccess { list ->
            firing = list.filter { it.state == AlertState.FIRING || it.state == AlertState.ACKNOWLEDGED }
        }
    }

    /** Container profiles: Colima ones first (each Colima VM is a profile), then nerdctl `lima:<vm>` ones. */
    fun profiles(): List<String> {
        val fromVms = vms?.vms.orEmpty().filter { it.source == VmSource.COLIMA }.map { it.name }
        val all = (fromVms + containerProfiles + containerLists.keys).distinct()
        val (lima, colima) = all.partition { it.startsWith(Names.NERDCTL_PREFIX) }
        return (colima.ifEmpty { listOf("default") }) + lima
    }

    /** Profiles with a Docker daemon; images, pulls and pruning exist only there. */
    fun dockerProfiles(): List<String> = profiles().filterNot { it.startsWith(Names.NERDCTL_PREFIX) }

    suspend fun refreshContainers(profile: String): ContainerList {
        val list = api.containers(profile)
        containerLists = containerLists + (profile to list)
        return list
    }

    private suspend fun checkVersion() {
        runCatching { api.health() }.onSuccess { serverVersion = it.version }
    }

    val versionMismatch: Boolean get() = serverVersion != null && serverVersion != BuildInfo.VERSION

    // ---------- live stats ----------

    fun window(target: String): SampleWindow? = windows[target]
    fun latest(target: String): StatsSample? = windows[target]?.latest()

    /** Ref-counted: pages call this in a DisposableEffect and close the handle on leave. */
    fun watch(targets: Collection<String>): () -> Unit {
        val added = targets.distinct().filter { t ->
            val n = watchCounts[t] ?: 0
            watchCounts[t] = n + 1
            n == 0
        }
        if (added.isNotEmpty()) statsSocket?.send(subscription(StatsSubscription(subscribe = added)))
        return {
            val removed = targets.distinct().filter { t ->
                val n = (watchCounts[t] ?: 1) - 1
                if (n <= 0) watchCounts.remove(t) else watchCounts[t] = n
                n <= 0
            }
            if (removed.isNotEmpty()) statsSocket?.send(subscription(StatsSubscription(unsubscribe = removed)))
        }
    }

    private fun subscription(s: StatsSubscription) = ApiJson.encodeToString(StatsSubscription.serializer(), s)

    private fun onStats(text: String) {
        val batch = runCatching { ApiJson.decodeFromString(StatsBatch.serializer(), text) }.getOrNull() ?: return
        // 10 minutes at 2 s: enough for overview sparklines and the live range of detail charts.
        batch.samples.forEach { windows.getOrPut(it.targetId) { SampleWindow(300) }.add(it) }
        statsTick++
    }

    // ---------- events ----------

    private fun onEvent(text: String) {
        val event = runCatching { ApiJson.decodeFromString(ServerEvent.serializer(), text) }.getOrNull() ?: return
        events.add(0, event)
        while (events.size > 200) events.removeAt(events.lastIndex)
        when (event.type) {
            EventType.VM_STATE -> scope.launch { refreshVms() }
            EventType.ALERT -> scope.launch { refreshAlerts() }
            EventType.ACTION_DONE -> {
                actionWaiters.remove(event.actionId)?.invoke(event)
                scope.launch { refreshVms() }
            }
            else -> Unit
        }
    }

    private val actionWaiters = mutableMapOf<String?, (ServerEvent) -> Unit>()

    /**
     * Runs a request that returns an action id and reports its end. Events usually say when it is
     * done; polling `/api/actions/{id}` covers a dropped WebSocket.
     */
    fun runAction(label: String, start: suspend () -> String, onDone: () -> Unit = {}) {
        scope.launch {
            val id = try {
                start()
            } catch (e: ApiException) {
                toast("$label: ${e.message}")
                return@launch
            }
            track(label, id, onDone)
        }
    }

    /** Follows an action that was already accepted (for example from a confirm dialog). */
    fun track(label: String, id: String, onDone: () -> Unit = {}) {
        scope.launch {
            toast("$label started")
            while (isActive) {
                delay(1_500)
                val status = runCatching { api.action(id) }.getOrNull() ?: continue
                if (status.state == ActionState.RUNNING) continue
                toast(if (status.state == ActionState.SUCCEEDED) "$label: done" else "$label failed: ${status.error ?: "unknown error"}")
                refreshVms()
                onDone()
                break
            }
        }
    }

    /** Runs a plain request with error reporting; returns null on failure. */
    fun <T> launchCall(label: String?, block: suspend () -> T, onSuccess: (T) -> Unit = {}) {
        scope.launch {
            try {
                val value = block()
                if (label != null) toast(label)
                onSuccess(value)
            } catch (e: ApiException) {
                toast(e.message ?: "Request failed")
            }
        }
    }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
}

object Clock {
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
