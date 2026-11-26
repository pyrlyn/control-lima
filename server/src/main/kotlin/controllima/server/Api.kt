package controllima.server

import controllima.providers.kube.KubeApiException
import controllima.providers.kube.KubeException
import controllima.providers.colima.ColimaException
import controllima.providers.docker.DockerException
import controllima.providers.docker.DockerProvider
import controllima.providers.lima.ProviderException
import controllima.server.alerts.AlertEngine
import controllima.server.alerts.ChannelStore
import controllima.server.alerts.Notifier
import controllima.server.backup.BackupService
import controllima.server.http.HttpApp
import controllima.server.http.HttpError
import controllima.server.http.Request
import controllima.server.http.Response
import controllima.server.http.Router
import controllima.server.http.Upgrade
import controllima.server.http.badRequest
import controllima.server.http.conflict
import controllima.server.http.forbidden
import controllima.server.http.notFound
import controllima.server.metrics.MetricsStore
import controllima.server.security.AccessControl
import controllima.server.security.AuditLog
import controllima.server.security.Principal
import controllima.server.security.Redactor
import controllima.shared.ActionAccepted
import controllima.shared.AlertRule
import controllima.shared.ApiError
import controllima.shared.RemoteHostStatus
import controllima.shared.ApiJson
import controllima.shared.ApiRoutes
import controllima.shared.CleanupRequest
import controllima.shared.CloneRequest
import controllima.shared.ConfirmRequest
import controllima.shared.CreateBackupRequest
import controllima.shared.CreateTokenRequest
import controllima.shared.CreateUserRequest
import controllima.shared.CreateVmRequest
import controllima.shared.Health
import controllima.shared.ImageList
import controllima.shared.LogBatch
import controllima.shared.LoginRequest
import controllima.shared.Names
import controllima.shared.NotificationChannel
import controllima.shared.ProtectedNames
import controllima.shared.PullRequest
import controllima.shared.RenameRequest
import controllima.shared.RestoreRequest
import controllima.shared.RevealRequest
import controllima.shared.RevealedValue
import controllima.shared.Role
import controllima.shared.SaveTemplateRequest
import controllima.shared.ServerEvent
import controllima.shared.Session
import controllima.shared.Settings
import controllima.shared.SettingsResponse
import controllima.shared.SilenceRequest
import controllima.shared.StatsBatch
import controllima.shared.StatsSubscription
import controllima.shared.Targets
import controllima.shared.TestNotificationRequest
import controllima.shared.Vm
import controllima.shared.VmDetail
import controllima.shared.VmRef
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Everything the API needs; built once by [ControlLimaServer]. */
class Services(
    val config: ServerConfig,
    val paths: DataPaths,
    val settings: SettingsStore,
    val backends: Backends,
    val access: AccessControl,
    val audit: AuditLog,
    val events: EventBus,
    val actions: ActionManager,
    val inventory: Inventory,
    val containers: ContainerInventory,
    val stats: StatsCollector,
    val ports: PortsService,
    val metrics: MetricsStore,
    val alerts: AlertEngine,
    val channels: ChannelStore,
    val notifier: Notifier,
    val logs: LogService,
    val backups: BackupService,
    val templates: TemplateService,
    val disk: DiskService,
    val about: AboutService,
    val vmEdit: VmEditService,
    val kube: KubernetesService,
    val terminal: TerminalService,
    val remoteHosts: RemoteHostsService,
    val forwards: PortForwardService,
)

/**
 * The HTTP API of docs/plan/api.md behind our [HttpApp] interface. Order of checks on every request:
 * Host/Origin → token → role → CSRF on writes → handler → audit of writes.
 */
class Api(internal val s: Services, private val static: StaticFiles = StaticFiles()) : HttpApp {
    internal class Ctx(val request: Request, val principal: Principal) {
        val actor get() = principal.actor
    }

    internal class Route(val role: Role, val handler: suspend Ctx.() -> Response)

    private val router = Router<Route>()

    internal fun get(path: String, role: Role = Role.VIEWER, h: suspend Ctx.() -> Response) = router.add("GET", path, Route(role, h))
    internal fun post(path: String, role: Role, h: suspend Ctx.() -> Response) = router.add("POST", path, Route(role, h))
    internal fun put(path: String, role: Role, h: suspend Ctx.() -> Response) = router.add("PUT", path, Route(role, h))
    internal fun delete(path: String, role: Role, h: suspend Ctx.() -> Response) = router.add("DELETE", path, Route(role, h))

    internal val protectedNames: Set<String> get() = s.settings.current.protectedNames.toSet() + ProtectedNames.defaults

    init {
        routes()
    }

    override fun isWebSocketPath(path: String) = path.startsWith("/ws/")

    // ---------- request pipeline ----------

    override suspend fun handle(request: Request): Response {
        val response = try {
            dispatch(request)
        } catch (e: Throwable) {
            errorResponse(e)
        }
        return response.withHeaders(securityHeaders(request.path))
    }

    private suspend fun dispatch(request: Request): Response {
        if (request.path == ApiRoutes.HEALTH && request.method == "GET") {
            return json(Health("ok", s.config.version), Health.serializer())
        }
        s.access.checkHostAndOrigin(request)?.let { return Response.error(403, "forbidden", it) }
        if (!request.path.startsWith("/api/")) {
            if (request.method != "GET" && request.method != "HEAD") return Response.error(405, "method_not_allowed", "Method not allowed")
            return static.serve(request.path) ?: Response.error(404, "not_found", "Not found")
        }
        if (request.path == ApiRoutes.LOGIN && request.method == "POST") return login(request)

        val principal = s.access.authenticate(request)
        if (principal == null) {
            s.audit.failedAuth(request.path, if (request.header(ApiRoutes.TOKEN_HEADER) == null) "missing token" else "bad token")
            return Response.error(401, "unauthorized", "A valid token is required")
        }
        s.audit.noteClient(principal, request.header("User-Agent"))
        val match = router.match(request.method, request.path) ?: return Response.error(404, "not_found", "No such endpoint")
        val route = match.value ?: return Response.error(405, "method_not_allowed", "Method not allowed")
        request.params = match.params
        if (!principal.role.allows(route.role)) {
            return Response.error(403, "forbidden", "This needs the ${route.role.name.lowercase()} role")
        }
        if (request.isWrite && !s.access.checkCsrf(request, principal)) {
            return Response.error(403, "csrf", "Missing or invalid CSRF token")
        }
        val started = System.currentTimeMillis()
        val response = try {
            withContext(Dispatchers.IO) { route.handler(Ctx(request, principal)) }
        } catch (e: Throwable) {
            errorResponse(e)
        }
        if (request.isWrite) {
            val params = request.query.mapValues { it.value.joinToString(",") } + request.params
            val target = request.params["name"] ?: request.params["id"] ?: request.param("target")
            s.audit.record(principal.actor, principal.role, "${request.method} ${request.path}", target, params,
                if (response.status < 400) "ok (${response.status})" else "error (${response.status})", System.currentTimeMillis() - started)
        }
        return response
    }

    private fun errorResponse(e: Throwable): Response = when (e) {
        is HttpError -> Response.error(e.status, e.code, e.message)
        is ActionManager.Busy -> Response.error(409, "busy", e.message ?: "busy")
        is AccessControl.LockedOut -> Response.error(429, "locked_out", e.message ?: "locked out")
        is SerializationException -> Response.error(400, "bad_request", "Malformed request body")
        is IllegalArgumentException -> Response.error(400, "bad_request", e.message ?: "Bad request")
        is TimeoutCancellationException -> Response.error(504, "timeout", "The backend did not answer in time")
        is ProviderException, is ColimaException, is DockerException, is KubeException, is KubeApiException,
        is IllegalStateException, is UnsupportedOperationException ->
            if (e.message?.contains("timed out", ignoreCase = true) == true) Response.error(504, "timeout", e.message!!)
            else Response.error(502, "backend_error", e.message ?: "Backend error")
        else -> Response.error(500, "internal", "Internal error")
    }

    private fun securityHeaders(path: String): Map<String, String> = buildMap {
        put("X-Content-Type-Options", "nosniff")
        put("X-Frame-Options", "DENY")
        put("Referrer-Policy", "no-referrer")
        put("Content-Security-Policy", "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
        if (path.startsWith("/api/") || path == ApiRoutes.HEALTH) put("Cache-Control", "no-store")
    }

    private suspend fun login(request: Request): Response {
        if (!s.settings.current.multiUser) return Response.error(404, "not_found", "Multi-user mode is off")
        val body = decode(request, LoginRequest.serializer())
        val (sid, user) = s.access.login(body.username, body.password) ?: run {
            s.audit.failedAuth(request.path, "bad password for ${body.username.take(64)}")
            return Response.error(401, "unauthorized", "Wrong username or password")
        }
        s.audit.record(user.username, user.role, "login", null, emptyMap(), "ok", 0)
        // Same key as AccessControl.authenticate derives from the cookie, so the CSRF token matches.
        val principal = Principal(user.username, user.role, "session:${controllima.server.security.Secrets.sha256(sid)}", Principal.Via.SESSION)
        return json(session(principal), Session.serializer()).withHeaders(mapOf("Set-Cookie" to s.access.sessionCookie(sid)))
    }

    private fun session(p: Principal) = Session(p.actor, p.role, s.access.csrfToken(p), protectedNames.sorted(), s.config.version, s.settings.current.multiUser)

    // ---------- helpers ----------

    internal fun <T> json(value: T, serializer: KSerializer<T>, status: Int = 200) = Response.json(value, serializer, status)
    internal fun <T> list(value: List<T>, serializer: KSerializer<T>) = Response.json(value, ListSerializer(serializer))
    internal fun accepted(id: String) = json(ActionAccepted(id), ActionAccepted.serializer(), 202)
    internal fun ok() = Response.noContent()

    internal fun <T> decode(request: Request, serializer: KSerializer<T>): T {
        if (request.bytes.size > MAX_BODY) badRequest("Request body too large")
        return ApiJson.decodeFromString(serializer, request.body.ifEmpty { "{}" })
    }

    internal fun Ctx.vmRef(): VmRef {
        val source = VmSource.fromSlug(request.path("source")) ?: notFound("Unknown source")
        val name = request.path("name")
        if (!Names.isValidVmName(name)) badRequest("Invalid VM name")
        val home = request.param("home")
        if (home != null && (source != VmSource.LIMA || s.backends.lima(home) == null)) notFound("Unknown Lima home")
        return VmRef(source, name, home)
    }

    internal suspend fun Ctx.vm(): Vm {
        val ref = vmRef()
        return s.inventory.vms().vms.firstOrNull { it.ref == ref } ?: notFound("No VM ${ref.name}")
    }

    internal fun Ctx.profile(): String {
        val p = request.param("profile") ?: s.backends.defaultProfile()
        if (!Names.isValidContainerProfile(p)) badRequest("Invalid profile")
        return p
    }

    private fun docker(profile: String): DockerProvider =
        s.containers.docker(profile) ?: throw IllegalStateException("Docker is not available for profile $profile (is Colima running?)")

    private fun Ctx.containerId(): String = request.path("id").also { if (!Names.isValidContainerRef(it)) badRequest("Invalid container id") }

    internal fun requireConfirm(typed: String, expected: String) {
        if (typed != expected || expected.isEmpty()) badRequest("Type \"$expected\" to confirm")
    }

    internal fun lima(ref: VmRef) = s.backends.lima(ref.home) ?: throw IllegalStateException("Lima home is not configured")

    internal fun isUp(vm: Vm) = vm.status == VmStatus.RUNNING || vm.status == VmStatus.UNRESPONSIVE

    // ---------- routes ----------

    private fun routes() {
        get(ApiRoutes.SESSION) { json(session(principal), Session.serializer()) }
        post(ApiRoutes.LOGOUT, Role.VIEWER) {
            s.access.logout(request)
            ok().withHeaders(mapOf("Set-Cookie" to s.access.sessionCookie(null)))
        }
        vmRoutes()
        containerRoutes()
        imageRoutes()
        get(ApiRoutes.PORTS) { json(s.ports.report(), controllima.shared.PortsReport.serializer()) }
        get(ApiRoutes.NETWORKS) { list(s.ports.networks(), controllima.shared.NetworkInfo.serializer()) }
        get("${ApiRoutes.ACTIONS}/{id}") {
            json(s.actions.get(request.path("id")) ?: notFound("No such action"), controllima.shared.ActionStatus.serializer())
        }
        metricRoutes()
        alertRoutes()
        logRoutes()
        backupRoutes()
        templateRoutes()
        get(ApiRoutes.DISK) { json(s.disk.report(), controllima.shared.DiskReport.serializer()) }
        post(ApiRoutes.CLEANUP, Role.ADMIN) {
            val body = decode(request, CleanupRequest.serializer())
            json(s.disk.cleanup(body, request.param("dryRun") != "false"), controllima.shared.CleanupResult.serializer())
        }
        settingsRoutes()
        accessRoutes()
        vmEditRoutes()
        kubeRoutes()
    }

    private fun vmRoutes() {
        get(ApiRoutes.VMS) { json(s.inventory.vms(), controllima.shared.VmList.serializer()) }
        get("${ApiRoutes.VMS}/{source}/{name}") {
            val vm = vm()
            json(detail(vm), VmDetail.serializer())
        }
        post("${ApiRoutes.VMS}/{source}/{name}/start", Role.OPERATOR) {
            val vm = vm()
            if (vm.status == VmStatus.RUNNING) conflict("${vm.name} is already running")
            accepted(s.actions.launch("vm.start", vm.ref.targetId, principal) { progress ->
                val result = if (vm.source == VmSource.LIMA) lima(vm.ref).start(vm.name, progress) else s.backends.colima().start(vm.name, progress)
                check(result.ok) { failure("start", result) }
                s.inventory.refresh()
                "${vm.name} started"
            })
        }
        post("${ApiRoutes.VMS}/{source}/{name}/stop", Role.OPERATOR) {
            val vm = vm()
            val force = request.param("force") == "true"
            if (force && !principal.role.allows(Role.ADMIN)) forbidden("Force stop needs the admin role")
            if (!isUp(vm)) conflict("${vm.name} is not running")
            accepted(s.actions.launch(if (force) "vm.force_stop" else "vm.stop", vm.ref.targetId, principal, mapOf("force" to "$force")) { progress ->
                val result = if (vm.source == VmSource.LIMA) lima(vm.ref).stop(vm.name, force, progress) else s.backends.colima().stop(vm.name, force, progress)
                check(result.ok) { failure("stop", result) }
                s.inventory.refresh()
                "${vm.name} stopped"
            })
        }
        delete("${ApiRoutes.VMS}/{source}/{name}", Role.ADMIN) {
            val vm = vm()
            if (vm.name in protectedNames) conflict("${vm.name} is protected and can never be deleted")
            requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, vm.name)
            if (isUp(vm)) conflict("Stop ${vm.name} before deleting it")
            accepted(s.actions.launch("vm.delete", vm.ref.targetId, principal) { _ ->
                val result = if (vm.source == VmSource.LIMA) lima(vm.ref).delete(vm.name) else s.backends.colima().delete(vm.name)
                check(result.ok) { failure("delete", result) }
                s.inventory.refresh()
                "${vm.name} deleted"
            })
        }
        post("${ApiRoutes.VMS}/{source}/{name}/rename", Role.ADMIN) {
            val vm = vm()
            val body = decode(request, RenameRequest.serializer())
            if (vm.source != VmSource.LIMA) conflict("Only Lima VMs can be renamed")
            if (vm.name in protectedNames) conflict("${vm.name} is protected and cannot be renamed")
            requireConfirm(body.confirmName.orEmpty(), vm.name)
            if (!Names.isValidVmName(body.newName)) badRequest("Invalid new name")
            if (vm.status != VmStatus.STOPPED) conflict("Stop ${vm.name} before renaming it")
            if (s.inventory.find(vm.ref.copy(name = body.newName)) != null) conflict("A VM named ${body.newName} already exists")
            if (s.actions.isBusy(vm.ref.targetId)) conflict("Another action is running on ${vm.name}")
            val result = lima(vm.ref).rename(vm.name, body.newName)
            check(result.ok) { failure("rename", result) }
            s.actions.markUserAction(vm.ref.targetId)
            val renamed = s.inventory.refresh().vms.firstOrNull { it.ref == vm.ref.copy(name = body.newName) }
                ?: throw IllegalStateException("Renamed VM not found")
            json(renamed, Vm.serializer())
        }
        post("${ApiRoutes.VMS}/{source}/{name}/clone", Role.ADMIN) {
            val vm = vm()
            val body = decode(request, CloneRequest.serializer())
            if (vm.source != VmSource.LIMA) conflict("Only Lima VMs can be cloned")
            if (!Names.isValidVmName(body.newName)) badRequest("Invalid new name")
            if (vm.status != VmStatus.STOPPED) conflict("Stop ${vm.name} before cloning it")
            if (s.inventory.find(vm.ref.copy(name = body.newName)) != null) conflict("A VM named ${body.newName} already exists")
            accepted(s.actions.launch("vm.clone", vm.ref.copy(name = body.newName).targetId, principal, mapOf("from" to vm.name)) { progress ->
                val result = lima(vm.ref).clone(vm.name, body.newName, progress)
                check(result.ok) { failure("clone", result) }
                s.inventory.refresh()
                "Cloned ${vm.name} to ${body.newName} (stopped)"
            })
        }
    }

    internal fun failure(what: String, result: controllima.providers.process.CommandResult): String =
        if (result.timedOut) "$what timed out" else result.stderr.lines().lastOrNull { it.isNotBlank() }?.take(300) ?: "$what failed (exit ${result.exitCode})"

    private suspend fun detail(vm: Vm): VmDetail {
        val redactor = Redactor(s.settings.current.redactionPatterns)
        val config = when (vm.source) {
            VmSource.LIMA -> vm.dir?.let { File(it, "lima.yaml") }
            VmSource.COLIMA -> s.backends.colima().configFile(vm.name)?.let(::File)
        }?.takeIf { it.isFile && it.length() < 512 * 1024 }?.readText()?.let(redactor::redact)
        val containers = if (vm.source == VmSource.COLIMA && vm.status == VmStatus.RUNNING) s.containers.list(vm.name).containers else emptyList()
        val ssh = when (vm.source) {
            VmSource.LIMA -> (vm.home?.let { "LIMA_HOME=$it " } ?: "") + "limactl shell ${vm.name}"
            VmSource.COLIMA -> "colima ssh --profile ${vm.name}"
        }
        val ips = if (vm.status == VmStatus.RUNNING) runCatching { s.ports.guestIps(vm) }.getOrDefault(emptyList()) else emptyList()
        val snapshots = if (vm.source == VmSource.LIMA) runCatching { lima(vm.ref).snapshots(vm.name) }.getOrDefault(emptyList()) else emptyList()
        return VmDetail(vm, containers, config, ssh, ips, snapshots, s.about.limaVersion())
    }

    private fun containerRoutes() {
        get(ApiRoutes.CONTAINERS) { json(s.containers.list(profile()), controllima.shared.ContainerList.serializer()) }
        get(ApiRoutes.REMOTE_HOSTS) { json(s.remoteHosts.list(), ListSerializer(RemoteHostStatus.serializer())) }
        get(ApiRoutes.CONTAINER_PROFILES) { json(s.containers.profiles(), ListSerializer(String.serializer())) }
        get("${ApiRoutes.CONTAINERS}/{id}") {
            val detail = docker(profile()).inspect(containerId())
            // Values can hold credentials; viewers see keys only, admins reveal one value at a time (audited).
            val masked = detail.env.map { e -> e.substringBefore('=') + if ('=' in e) "=$MASK" else "" }
            json(detail.copy(env = masked), controllima.shared.ContainerDetail.serializer())
        }
        for (action in listOf("start", "stop", "restart")) {
            post("${ApiRoutes.CONTAINERS}/{id}/$action", Role.OPERATOR) {
                val profile = profile()
                val id = containerId()
                val docker = docker(profile)
                val target = Targets.container(profile, id)
                accepted(s.actions.launch("container.$action", target, principal) { _ ->
                    when (action) {
                        "start" -> docker.start(id)
                        "stop" -> docker.stop(id)
                        else -> docker.restart(id)
                    }
                    s.containers.invalidate(profile)
                    "Container ${id.take(12)} ${action}ed".replace("stoped", "stopped")
                })
            }
        }
        post("${ApiRoutes.CONTAINERS}/{id}/rename", Role.OPERATOR) {
            val profile = profile()
            val docker = docker(profile)
            val current = docker.inspect(containerId()).container
            val body = decode(request, RenameRequest.serializer())
            if (!Names.isValidContainerName(body.newName)) badRequest("Invalid container name")
            if (current.name in protectedNames) conflict("${current.name} is protected and cannot be renamed")
            docker.rename(current.id, body.newName)
            s.containers.invalidate(profile)
            s.actions.markUserAction(current.targetId)
            ok()
        }
        delete("${ApiRoutes.CONTAINERS}/{id}", Role.ADMIN) {
            val profile = profile()
            val docker = docker(profile)
            val current = docker.inspect(containerId()).container
            if (current.name in protectedNames) conflict("${current.name} is protected and can never be deleted")
            requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, current.name)
            if (current.running) conflict("Stop ${current.name} before deleting it")
            s.actions.markUserAction(current.targetId)
            docker.remove(current.id)
            s.containers.invalidate(profile)
            ok()
        }
        post("${ApiRoutes.CONTAINERS}/{id}/reveal", Role.ADMIN) {
            val key = decode(request, RevealRequest.serializer()).key
            val env = docker(profile()).inspect(containerId()).env
            val value = env.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "") ?: notFound("No variable $key")
            json(RevealedValue(key, value), RevealedValue.serializer())
        }
    }

    private fun imageRoutes() {
        get(ApiRoutes.IMAGES) {
            val profile = profile()
            val provider = s.containers.docker(profile)
                ?: return@get json(ImageList(profile, emptyList(), error = "Docker is not available for profile $profile"), ImageList.serializer())
            val images = provider.images()
            val reclaimable = images.filter { it.usedBy.isEmpty() }.sumOf { it.sizeBytes }
            json(ImageList(profile, images, images.sumOf { it.sizeBytes }, reclaimable), ImageList.serializer())
        }
        get("${ApiRoutes.IMAGES}/{id}") { json(docker(profile()).image(imageId()), controllima.shared.ImageDetail.serializer()) }
        delete("${ApiRoutes.IMAGES}/{id}", Role.ADMIN) {
            val docker = docker(profile())
            val image = docker.images().firstOrNull { it.id == imageId() } ?: notFound("No such image")
            if (image.usedBy.isNotEmpty()) conflict("Image is used by ${image.usedBy.joinToString()}")
            val expected = image.tags.firstOrNull() ?: image.id.removePrefix("sha256:").take(12)
            requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, expected)
            docker.deleteImage(image.id)
            ok()
        }
        post("${ApiRoutes.IMAGES}/prune", Role.ADMIN) {
            val dryRun = request.param("dryRun") != "false"
            if (!dryRun) requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, DiskService.PRUNE)
            json(docker(profile()).pruneImages(dryRun), controllima.shared.PruneResult.serializer())
        }
        post("${ApiRoutes.IMAGES}/pull", Role.OPERATOR) {
            val profile = profile()
            val ref = decode(request, PullRequest.serializer()).ref.trim()
            if (!controllima.shared.Names.isValidImageRef(ref)) badRequest("Invalid image reference")
            val docker = docker(profile)
            accepted(s.actions.launch("image.pull", "images:$profile/$ref", principal, mapOf("ref" to ref)) { progress ->
                docker.pull(ref, progress)
                "Pulled $ref"
            })
        }
    }

    private fun Ctx.imageId(): String = request.path("id").also { if (!IMAGE_ID.matches(it)) badRequest("Invalid image id") }

    private fun metricRoutes() {
        get(ApiRoutes.METRICS) {
            val target = request.param("target") ?: badRequest("target is required")
            val metric = request.param("metric")?.takeIf { it in controllima.shared.Metric.all } ?: badRequest("Unknown metric")
            val now = System.currentTimeMillis()
            val to = request.param("to")?.toLongOrNull() ?: now
            val from = request.param("from")?.toLongOrNull() ?: (to - 3_600_000)
            if (from >= to) badRequest("from must be before to")
            val step = request.param("step")?.toLongOrNull()?.also { if (it < 1000) badRequest("step must be at least 1000 ms") }
            json(s.metrics.series(target, metric, from, to, step), controllima.shared.MetricSeries.serializer())
        }
        get(ApiRoutes.METRICS_TRENDS) { json(s.metrics.trends(request.param("target")), controllima.shared.TrendsReport.serializer()) }
        post(ApiRoutes.METRICS_FORGET, Role.ADMIN) {
            val target = request.param("target") ?: badRequest("target is required")
            s.metrics.forget(target)
            s.stats.forget(target)
            ok()
        }
    }

    private fun alertRoutes() {
        get(ApiRoutes.ALERT_RULES) { list(s.alerts.rules(), AlertRule.serializer()) }
        post(ApiRoutes.ALERT_RULES, Role.ADMIN) { json(s.alerts.saveRule(decode(request, AlertRule.serializer()), isNew = true), AlertRule.serializer()) }
        put("${ApiRoutes.ALERT_RULES}/{id}", Role.ADMIN) {
            val rule = decode(request, AlertRule.serializer())
            if (rule.id != request.path("id")) badRequest("Rule id does not match the path")
            json(s.alerts.saveRule(rule, isNew = false), AlertRule.serializer())
        }
        delete("${ApiRoutes.ALERT_RULES}/{id}", Role.ADMIN) {
            if (!s.alerts.deleteRule(request.path("id"))) notFound("No such rule")
            ok()
        }
        post("${ApiRoutes.ALERT_RULES}/{id}/silence", Role.OPERATOR) {
            val body = decode(request, SilenceRequest.serializer())
            s.alerts.silence(request.path("id"), body.minutes, body.target)
            ok()
        }
        get(ApiRoutes.ALERT_EVENTS) { list(s.alerts.events(), controllima.shared.AlertEvent.serializer()) }
        post("${ApiRoutes.ALERTS}/{id}/ack", Role.OPERATOR) {
            json(s.alerts.acknowledge(request.path("id"), actor), controllima.shared.AlertEvent.serializer())
        }
        get(ApiRoutes.CHANNELS, Role.ADMIN) { list(s.channels.list(), NotificationChannel.serializer()) }
        put(ApiRoutes.CHANNELS, Role.ADMIN) {
            list(s.channels.replace(decode(request, ListSerializer(NotificationChannel.serializer()))), NotificationChannel.serializer())
        }
        post(ApiRoutes.NOTIFY_TEST, Role.ADMIN) {
            val id = decode(request, TestNotificationRequest.serializer()).channelId
            val channel = s.channels.get(id) ?: notFound("No channel $id")
            val result = s.notifier.send(channel, "ControlLima test", "Test notification from ControlLima")
            s.channels.recordResult(id, result.fold({ "ok" }, { "error: ${it.message}" }))
            result.getOrElse { throw IllegalStateException("Sending failed: ${it.message}") }
            ok()
        }
    }

    private fun logRoutes() {
        get(ApiRoutes.LOG_SOURCES) { list(s.logs.sources(request.param("target"), principal.role), controllima.shared.LogSource.serializer()) }
        get(ApiRoutes.LOGS) {
            val source = request.param("source") ?: badRequest("source is required")
            val tail = request.param("tail")?.toIntOrNull() ?: 500
            list(s.logs.tail(source, tail, principal.role), controllima.shared.LogLine.serializer())
        }
    }

    private fun backupRoutes() {
        get(ApiRoutes.BACKUPS) { list(s.backups.list(), controllima.shared.BackupInfo.serializer()) }
        post("${ApiRoutes.BACKUPS}/scan", Role.OPERATOR) {
            list(s.backups.scan(decode(request, CreateBackupRequest.serializer())), controllima.shared.SecretScan.serializer())
        }
        post(ApiRoutes.BACKUPS, Role.OPERATOR) {
            json(s.backups.create(decode(request, CreateBackupRequest.serializer())), controllima.shared.BackupInfo.serializer(), 201)
        }
        post("${ApiRoutes.BACKUPS}/import", Role.ADMIN) {
            json(s.backups.import(request.bytes), controllima.shared.BackupInfo.serializer(), 201)
        }
        get("${ApiRoutes.BACKUPS}/{id}/diff") {
            val target = request.param("target") ?: badRequest("target is required")
            json(s.backups.diff(request.path("id"), target), controllima.shared.BackupDiff.serializer())
        }
        get("${ApiRoutes.BACKUPS}/{id}/download", Role.ADMIN) {
            val file = s.backups.file(request.path("id"))
            s.audit.record(actor, principal.role, "backup.download", file.name, emptyMap(), "ok", 0)
            Response(200, file.readBytes(), "application/gzip", mapOf("Content-Disposition" to "attachment; filename=\"${file.name}\""))
        }
        post("${ApiRoutes.BACKUPS}/{id}/restore", Role.ADMIN) {
            val body = decode(request, RestoreRequest.serializer())
            body.targets.mapNotNull(Targets::parseVm).forEach { ref ->
                val existing = s.inventory.find(ref)
                if (existing != null && body.newName == null && ref.name in protectedNames) conflict("${ref.name} is protected; restore it under a new name")
            }
            json(s.backups.restore(request.path("id"), body, principal), controllima.shared.RestoreResult.serializer())
        }
        delete("${ApiRoutes.BACKUPS}/{id}", Role.ADMIN) {
            val id = request.path("id")
            requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, id)
            s.backups.delete(id)
            ok()
        }
    }

    private fun templateRoutes() {
        get(ApiRoutes.TEMPLATES) { list(s.templates.list(), controllima.shared.VmTemplate.serializer()) }
        post(ApiRoutes.TEMPLATES, Role.ADMIN) {
            val (template, findings) = s.templates.save(decode(request, SaveTemplateRequest.serializer()))
            json(template, controllima.shared.VmTemplate.serializer(), 201)
                .withHeaders(if (findings.isEmpty()) emptyMap() else mapOf("X-ControlLima-Secrets" to findings.size.toString()))
        }
        delete("${ApiRoutes.TEMPLATES}/{id}", Role.ADMIN) {
            s.templates.delete(request.path("id"))
            ok()
        }
        post("${ApiRoutes.TEMPLATES}/{id}/create-vm", Role.OPERATOR) {
            val body = decode(request, CreateVmRequest.serializer())
            if (request.param("dryRun") == "false") accepted(s.templates.create(request.path("id"), body, principal))
            else json(s.templates.preview(request.path("id"), body), controllima.shared.CreateVmPreview.serializer())
        }
    }

    private fun settingsRoutes() {
        get(ApiRoutes.SETTINGS) { json(SettingsResponse(s.settings.current, s.about.info()), SettingsResponse.serializer()) }
        put(ApiRoutes.SETTINGS, Role.ADMIN) {
            val incoming = decode(request, Settings.serializer())
            val old = s.settings.current
            s.settings.update(incoming)
            val removed = old.protectedNames - incoming.protectedNames.toSet()
            if (removed.isNotEmpty()) {
                s.audit.record(actor, principal.role, "settings.protected_removed", null, mapOf("names" to removed.joinToString()), "ok", 0)
            }
            json(SettingsResponse(s.settings.current, s.about.info()), SettingsResponse.serializer())
        }
    }

    private fun accessRoutes() {
        get(ApiRoutes.TOKENS, Role.ADMIN) { list(s.access.tokens.list(), controllima.shared.TokenInfo.serializer()) }
        post(ApiRoutes.TOKENS, Role.ADMIN) {
            val body = decode(request, CreateTokenRequest.serializer())
            json(s.access.tokens.create(body.name, body.role, body.expiresInDays), controllima.shared.CreatedToken.serializer(), 201)
        }
        delete("${ApiRoutes.TOKENS}/{id}", Role.ADMIN) {
            if (!s.access.tokens.revoke(request.path("id"))) notFound("No such token")
            ok()
        }
        get(ApiRoutes.USERS, Role.ADMIN) { list(s.access.users.list(), controllima.shared.UserInfo.serializer()) }
        post(ApiRoutes.USERS, Role.ADMIN) {
            val body = decode(request, CreateUserRequest.serializer())
            json(s.access.users.create(body.username, body.password, body.role), controllima.shared.UserInfo.serializer(), 201)
        }
        delete("${ApiRoutes.USERS}/{username}", Role.ADMIN) {
            val username = request.path("username")
            if (username == actor) conflict("You cannot delete yourself")
            if (!s.access.users.delete(username)) notFound("No such user")
            ok()
        }
        get(ApiRoutes.AUDIT, Role.ADMIN) {
            list(
                s.audit.query(request.param("from")?.toLongOrNull(), request.param("to")?.toLongOrNull(), request.param("actor")),
                controllima.shared.AuditEntry.serializer(),
            )
        }
    }

    // ---------- WebSockets ----------

    override suspend fun upgrade(request: Request): Upgrade {
        s.access.checkHostAndOrigin(request)?.let { return Upgrade.Refused(Response.error(403, "forbidden", it)) }
        val principal = s.access.authenticate(request) ?: run {
            s.audit.failedAuth(request.path, "websocket without a valid token")
            return Upgrade.Refused(Response.error(401, "unauthorized", "A valid token is required"))
        }
        return when (request.path) {
            ApiRoutes.STATS_WS -> Upgrade.Accepted { ch -> statsSession(ch, request.param("targets")) }
            ApiRoutes.EVENTS_WS -> Upgrade.Accepted { ch -> eventsSession(ch) }
            ApiRoutes.LOGS_WS -> {
                val source = request.param("source") ?: return Upgrade.Refused(Response.error(400, "bad_request", "source is required"))
                val tail = request.param("tail")?.toIntOrNull() ?: 500
                Upgrade.Accepted { ch -> logsSession(ch, source, tail, principal) }
            }
            ApiRoutes.TERMINAL_WS -> try {
                val req = s.terminal.authorize(principal, request.param("target"), request.param("rows"), request.param("cols"))
                Upgrade.Accepted { ch -> s.terminal.session(ch, principal, req) }
            } catch (e: HttpError) {
                Upgrade.Refused(Response.error(e.status, e.code, e.message))
            }
            else -> Upgrade.Refused(Response.error(404, "not_found", "No such WebSocket"))
        }
    }

    private suspend fun statsSession(ch: controllima.server.http.WsChannel, initial: String?) = coroutineScope {
        val watched = ConcurrentHashMap.newKeySet<String>()
        var handle = s.stats.watch(emptyList())
        suspend fun subscribe(add: List<String>, remove: List<String>) {
            val fresh = add.filter { it.length <= 512 && it !in watched }
            watched -= remove.toSet()
            fresh.take((MAX_WS_TARGETS - watched.size).coerceAtLeast(0)).forEach { watched += it }
            handle.close()
            handle = s.stats.watch(watched.toList())
            val history = fresh.filter { it in watched }.flatMap { s.stats.history(it) }
            if (history.isNotEmpty()) ch.send(ApiJson.encodeToString(StatsBatch.serializer(), StatsBatch(history, history = true)))
        }
        try {
            subscribe(initial?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(), emptyList())
            val pump = launch {
                s.stats.samples.collect { sample ->
                    if (sample.targetId in watched) ch.send(ApiJson.encodeToString(StatsBatch.serializer(), StatsBatch(listOf(sample))))
                }
            }
            while (true) {
                val text = ch.receive() ?: break
                val msg = runCatching { ApiJson.decodeFromString(StatsSubscription.serializer(), text) }.getOrNull() ?: continue
                subscribe(msg.subscribe, msg.unsubscribe)
            }
            pump.cancel()
        } finally {
            handle.close()
        }
    }

    private suspend fun eventsSession(ch: controllima.server.http.WsChannel) = coroutineScope {
        val pump = launch {
            s.events.events.collect { ch.send(ApiJson.encodeToString(ServerEvent.serializer(), it)) }
        }
        while (ch.receive() != null) Unit
        pump.cancel()
    }

    private suspend fun logsSession(ch: controllima.server.http.WsChannel, source: String, tail: Int, principal: Principal) = coroutineScope {
        val batch = Channel<controllima.shared.LogLine>(capacity = 10_000)
        val reader = launch(Dispatchers.IO) {
            try {
                s.logs.follow(source, tail, principal.role) { batch.send(it) }
                ch.send(ApiJson.encodeToString(LogBatch.serializer(), LogBatch(error = "The log stream ended")))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ch.send(ApiJson.encodeToString(LogBatch.serializer(), LogBatch(error = (e as? HttpError)?.message ?: e.message ?: "Log stream failed")))
            } finally {
                batch.close()
            }
        }
        val writer = launch {
            val pending = mutableListOf<controllima.shared.LogLine>()
            for (line in batch) {
                pending += line
                // Batches keep a chatty log from producing one frame per line.
                while (pending.size < 500) pending += batch.tryReceive().getOrNull() ?: break
                ch.send(ApiJson.encodeToString(LogBatch.serializer(), LogBatch(pending.toList())))
                pending.clear()
                kotlinx.coroutines.delay(100)
            }
        }
        while (ch.receive() != null) Unit
        reader.cancel()
        writer.cancel()
    }

    companion object {
        const val MASK = "•••"
        private const val MAX_BODY = 1 shl 20
        private const val MAX_WS_TARGETS = 200
        private val IMAGE_ID = Regex("^(sha256:)?[a-f0-9]{6,64}$")
    }
}

/** Web app files from the classpath (`web/`), packaged by the build. */
class StaticFiles(private val loader: ClassLoader = StaticFiles::class.java.classLoader) {
    fun serve(path: String): Response? {
        val clean = path.removePrefix("/").ifEmpty { "index.html" }
        if (clean.split('/').any { it == ".." || it.startsWith(".") } || '\\' in clean) return null
        // Paths without an extension are app routes and get the app itself.
        val file = if ('.' in clean.substringAfterLast('/')) clean else "index.html"
        val bytes = loader.getResourceAsStream("web/$file")?.use { it.readBytes() } ?: return null
        val type = TYPES[file.substringAfterLast('.')] ?: "application/octet-stream"
        // Only the wasm files have content hashes in their names; anything else may change on upgrade.
        return Response(200, bytes, type, mapOf("Cache-Control" to if (file.endsWith(".wasm")) "max-age=3600" else "no-cache"))
    }

    companion object {
        private val TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8",
            "wasm" to "application/wasm", "css" to "text/css; charset=utf-8", "json" to "application/json", "svg" to "image/svg+xml",
            "png" to "image/png", "ico" to "image/x-icon", "map" to "application/json", "txt" to "text/plain; charset=utf-8",
        )
    }
}

/** Version info for Settings → About and the opt-in update check (docs/plan/packaging-updates.md). */
class AboutService(
    private val config: ServerConfig,
    private val paths: DataPaths,
    private val backends: Backends,
    private val settings: () -> Settings,
    private val http: controllima.server.alerts.HttpPoster,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var lima: Pair<Long, String?>? = null
    @Volatile private var latest: Pair<Long, String?>? = null

    fun limaVersion(): String? {
        lima?.takeIf { clock() - it.first < 600_000 }?.let { return it.second }
        val v = runCatching { backends.limaProviders().first().info().version }.getOrNull()
        lima = clock() to v
        return v
    }

    fun info(): controllima.shared.AboutInfo {
        val warnings = buildList {
            if (limaVersion() == null) add("limactl was not found or did not answer; Lima VMs are not shown")
        }
        return controllima.shared.AboutInfo(config.version, paths.dataDir.path, paths.configFile.path, limaVersion(), null, warnings, latest?.second)
    }

    /** Once a day, only when the user turned it on; sends nothing but the request itself. */
    suspend fun checkForUpdate() {
        if (!settings().updateCheck) return
        if (latest?.let { clock() - it.first < 86_400_000 } == true) return
        val (status, body) = runCatching { http.getText(RELEASES, mapOf("Accept" to "application/vnd.github+json")) }.getOrNull() ?: return
        val tag = if (status == 200) runCatching {
            (ApiJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["tag_name"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.removePrefix("v") }
        }.getOrNull() else null
        latest = clock() to tag
    }

    companion object {
        const val RELEASES = "https://api.github.com/repos/pyrlyn/control-lima/releases/latest"
    }
}
