package controllima.server

import controllima.providers.host.FileSecretStore
import controllima.providers.host.KeychainSecretStore
import controllima.providers.host.LocalHostProvider
import controllima.providers.host.SecretStore
import controllima.providers.process.CommandRunner
import controllima.providers.process.ProcessCommandRunner
import controllima.server.alerts.AlertEngine
import controllima.server.alerts.AlertInputs
import controllima.server.alerts.ChannelNotifier
import controllima.server.alerts.ChannelStore
import controllima.server.alerts.KtorHttpPoster
import controllima.server.backup.BackupService
import controllima.server.http.HttpServer
import controllima.server.http.KtorHttpServer
import controllima.server.metrics.FileMetricsStore
import controllima.server.metrics.MetricsStore
import controllima.server.metrics.SqliteMetricsStore
import controllima.server.metrics.Retention
import controllima.server.security.AccessControl
import controllima.server.security.AuditLog
import controllima.server.security.TokenStore
import controllima.server.security.UserStore
import controllima.shared.Container
import controllima.shared.VmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds every service, starts the background loops and the HTTP server. The CLI owns flags and the
 * launch token; this class owns lifetimes.
 */
class ControlLimaServer(
    val config: ServerConfig,
    val paths: DataPaths,
    overrides: Overrides = Overrides(),
    runner: CommandRunner = ProcessCommandRunner(),
    homeDir: String = System.getProperty("user.home"),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = KtorHttpPoster()
    val services: Services
    private val server: HttpServer

    private val restarts = ConcurrentHashMap<String, MutableList<Pair<Long, Int>>>()
    private val exited = ConcurrentHashMap<String, Int>()
    private val down = ConcurrentHashMap.newKeySet<String>()

    init {
        paths.ensure()
        val settings = SettingsStore(paths.configFile)
        val host = LocalHostProvider(runner)
        val secrets: SecretStore = if (host.isMac) KeychainSecretStore(runner) else FileSecretStore(paths.secrets)
        val backends = LocalBackends(runner, host, { settings.current }, overrides, homeDir)
        val audit = AuditLog(paths.auditLog)
        val access = AccessControl(config, TokenStore(paths.tokens), UserStore(paths.users), { settings.current.multiUser })
        val events = EventBus()
        val actions = ActionManager(scope, events, audit)
        val inventory = Inventory(backends, { settings.current }, events)
        val containers = ContainerInventory(backends, events, nerdctl = NerdctlProfiles(backends, { inventory.snapshot.value.vms }))
        val stats = StatsCollector(backends, inventory, containers, { settings.current })
        val kube = KubernetesService(File(homeDir).toPath(), { inventory.snapshot.value.vms })
        val ports = PortsService(backends, inventory, containers, kube = kube)
        val retention = { settings.current.let { Retention(it.retentionRawHours, it.retention1mDays, it.retention15mDays, it.retention1hDays) } }
        // The file store keeps history working when the SQLite native library cannot load on this machine.
        val metrics: MetricsStore = runCatching { SqliteMetricsStore(paths.metricsDb, retention) }.getOrElse { e ->
            System.err.println("control-lima: SQLite metrics store unavailable (${e.message}); using the file store")
            FileMetricsStore(paths.metrics, retention)
        }
        val channels = ChannelStore(File(paths.alerts, "channels.json"), secrets)
        val notifier = ChannelNotifier(host, http, secrets)
        val alerts = AlertEngine(paths.alerts, channels, notifier, events)
        val logs = LogService(paths, backends, inventory, containers, kube) { settings.current }
        val backups = BackupService(paths, backends, inventory, actions, { settings.current })
        val templates = TemplateService(paths, backends, inventory, actions, { settings.current })
        val disk = DiskService(backends, inventory, stats, metrics, backups)
        val about = AboutService(config, paths, backends, { settings.current }, http)
        services = Services(
            config, paths, settings, backends, access, audit, events, actions, inventory, containers, stats, ports,
            metrics, alerts, channels, notifier, logs, backups, templates, disk, about, VmEditService(paths, backends, backups), kube,
        )
        server = KtorHttpServer(config.host, config.port, Api(services))
    }

    fun start(wait: Boolean = false) {
        val s = services
        s.inventory.onChange { old, new ->
            if (old == null || new == null) return@onChange
            val target = old.ref.targetId
            // Gaps in history are explicit, so charts do not draw a line across an unresponsive period.
            if (old.status == VmStatus.UNRESPONSIVE && new.status != VmStatus.UNRESPONSIVE) {
                s.metrics.markGap(target, old.unresponsiveSinceMillis ?: System.currentTimeMillis(), System.currentTimeMillis())
            }
            val wentDown = old.status == VmStatus.RUNNING && new.status != VmStatus.RUNNING
            if (wentDown && !s.actions.recentUserAction(target)) down += target
            if (new.status == VmStatus.RUNNING) down -= target
        }
        s.containers.onChange { old, new -> containerChanged(old, new) }
        s.inventory.start(scope)
        s.stats.start(scope)
        scope.launch { s.stats.samples.collect { runCatching { s.metrics.append(it) } } }
        every(60_000) { s.metrics.maintain() }
        every(30_000) { s.containers.all(maxAgeMillis = 10_000) }
        every(60_000) { pollRestarts() }
        every(3_600_000) { s.backups.runSchedule() }
        every(3_600_000) { s.about.checkForUpdate() }
        s.alerts.start(scope) { alertInputs() }
        server.start(wait)
    }

    private fun every(millis: Long, block: suspend () -> Unit) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            runCatching { block() }
            delay(millis)
        }
    }

    private fun containerChanged(old: Container?, new: Container?) {
        val c = new ?: return
        if (old?.running == true && !c.running && c.state == "exited" && !services.actions.recentUserAction(c.targetId)) {
            // The list only says "exited"; the exit code comes from inspect.
            scope.launch(Dispatchers.IO) {
                val code = runCatching { services.containers.docker(c.profile)?.inspect(c.id)?.exitCode }.getOrNull()
                if (code != null && code != 0) exited[c.targetId] = code
            }
        }
        if (c.running) exited.remove(c.targetId)
    }

    private fun pollRestarts() {
        val now = System.currentTimeMillis()
        services.containers.cached().filter { it.running }.take(50).forEach { c ->
            val count = runCatching { services.containers.docker(c.profile)?.inspect(c.id)?.restartCount }.getOrNull() ?: return@forEach
            val history = restarts.getOrPut(c.targetId) { mutableListOf() }
            synchronized(history) {
                history += now to count
                history.removeAll { now - it.first > 15 * 60_000 }
            }
        }
    }

    private fun alertInputs(): AlertInputs {
        val s = services
        val homes = s.backends.limaProviders().map { it.home ?: System.getenv("LIMA_HOME") ?: "${System.getProperty("user.home")}/.lima" }.distinct()
        val free = homes.mapNotNull { h -> runCatching { s.backends.host.diskSpace(h) }.getOrNull()?.let { "host:$h" to it.freeBytes } }.toMap()
        return AlertInputs(
            vms = s.inventory.snapshot.value.vms,
            containers = s.containers.cached(),
            samples = s.stats.latestAll(),
            hostFree = free,
            trends = runCatching { s.metrics.trends().trends }.getOrDefault(emptyList()),
            restarts = restarts.mapValues { (_, v) -> synchronized(v) { v.toList() } },
            exited = exited.toMap(),
            down = down.toSet(),
        )
    }

    override fun close() {
        runCatching { server.stop() }
        services.stats.stop()
        scope.cancel()
        services.metrics.close()
        services.kube.close()
        http.close()
    }
}
