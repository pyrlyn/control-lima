package controllima.shared

import kotlinx.serialization.json.Json

/** JSON settings shared by server and client: tolerant to new fields, explicit defaults. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

data class HttpResult(val status: Int, val body: String)

/** Our transport interface; the web app implements it with the browser, tests with a fake. */
interface HttpTransport {
    suspend fun request(method: String, path: String, body: String?, headers: Map<String, String>): HttpResult
}

class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

/** Typed client for docs/plan/api.md, used by the web UI. */
class ApiClient(private val transport: HttpTransport, var token: String) {
    private var csrfToken: String? = null

    suspend fun health(): Health = get(ApiRoutes.HEALTH)

    suspend fun session(): Session = get<Session>(ApiRoutes.SESSION).also { csrfToken = it.csrfToken }

    suspend fun login(username: String, password: String): Session =
        send<Session>("POST", ApiRoutes.LOGIN, ApiJson.encodeToString(LoginRequest.serializer(), LoginRequest(username, password)), csrf = false)
            .also { csrfToken = it.csrfToken }

    suspend fun logout() = sendUnit("POST", ApiRoutes.LOGOUT, null)

    // VMs
    suspend fun vms(): VmList = get(ApiRoutes.VMS)
    suspend fun vm(ref: VmRef): VmDetail = get(ApiRoutes.vm(ref))
    suspend fun startVm(ref: VmRef): ActionAccepted = send("POST", ApiRoutes.vmAction(ref, "start"), null)
    suspend fun stopVm(ref: VmRef, force: Boolean = false): ActionAccepted =
        send("POST", ApiRoutes.vmAction(ref, "stop") + forceQuery(ref, force), null)
    suspend fun deleteVm(ref: VmRef, confirmName: String): ActionAccepted =
        send("DELETE", ApiRoutes.vm(ref), encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))
    suspend fun renameVm(ref: VmRef, newName: String, confirmName: String): Vm =
        send("POST", ApiRoutes.vmAction(ref, "rename"), encode(RenameRequest.serializer(), RenameRequest(newName, confirmName)))
    suspend fun cloneVm(ref: VmRef, newName: String): ActionAccepted =
        send("POST", ApiRoutes.vmAction(ref, "clone"), encode(CloneRequest.serializer(), CloneRequest(newName)))

    // Snapshots and config editing (v2)
    suspend fun snapshots(ref: VmRef): SnapshotList = get(VmEditRoutes.snapshots(ref))
    suspend fun createSnapshot(ref: VmRef, tag: String): ActionAccepted =
        send("POST", VmEditRoutes.snapshots(ref), encode(SnapshotRequest.serializer(), SnapshotRequest(tag)))
    suspend fun applySnapshot(ref: VmRef, tag: String, confirmName: String): ActionAccepted =
        send("POST", VmEditRoutes.snapshot(ref, tag, "apply"), encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))
    suspend fun deleteSnapshot(ref: VmRef, tag: String, confirmName: String): ActionAccepted =
        send("DELETE", VmEditRoutes.snapshot(ref, tag), encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))
    suspend fun vmConfig(ref: VmRef): VmConfig = get(VmEditRoutes.config(ref))
    suspend fun saveVmConfig(ref: VmRef, yaml: String, confirmName: String): SaveConfigResult =
        send("PUT", VmEditRoutes.config(ref), encode(SaveConfigRequest.serializer(), SaveConfigRequest(yaml, confirmName)))
    suspend fun validateLimaYaml(yaml: String): ValidationResult =
        send("POST", VmEditRoutes.LIMA_VALIDATE, encode(ValidateRequest.serializer(), ValidateRequest(yaml)))

    // Containers
    suspend fun containers(profile: String? = null): ContainerList = get(ApiRoutes.CONTAINERS + profileQuery(profile))
    suspend fun container(id: String, profile: String): ContainerDetail = get(ApiRoutes.container(id, profile))
    suspend fun containerAction(id: String, profile: String, action: String) =
        sendUnit("POST", ApiRoutes.containerAction(id, profile, action), null)
    suspend fun renameContainer(id: String, profile: String, newName: String) =
        sendUnit("POST", ApiRoutes.containerAction(id, profile, "rename"), encode(RenameRequest.serializer(), RenameRequest(newName)))
    suspend fun deleteContainer(id: String, profile: String, confirmName: String) =
        sendUnit("DELETE", ApiRoutes.container(id, profile), encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))
    suspend fun revealEnv(id: String, profile: String, key: String): RevealedValue =
        send("POST", ApiRoutes.containerAction(id, profile, "reveal"), encode(RevealRequest.serializer(), RevealRequest(key)))

    // Images
    suspend fun images(profile: String? = null): ImageList = get(ApiRoutes.IMAGES + profileQuery(profile))
    suspend fun image(id: String, profile: String): ImageDetail = get("${ApiRoutes.IMAGES}/${ApiRoutes.encode(id)}?profile=${ApiRoutes.encode(profile)}")
    suspend fun deleteImage(id: String, profile: String, confirmName: String) =
        sendUnit("DELETE", "${ApiRoutes.IMAGES}/${ApiRoutes.encode(id)}?profile=${ApiRoutes.encode(profile)}", encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))
    suspend fun pruneImages(profile: String, dryRun: Boolean, confirmName: String = ""): PruneResult =
        send("POST", "${ApiRoutes.IMAGES}/prune?profile=${ApiRoutes.encode(profile)}&dryRun=$dryRun", encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))
    suspend fun pullImage(profile: String, ref: String): ActionAccepted =
        send("POST", "${ApiRoutes.IMAGES}/pull?profile=${ApiRoutes.encode(profile)}", encode(PullRequest.serializer(), PullRequest(ref)))

    // Ports, actions
    suspend fun ports(): PortsReport = get(ApiRoutes.PORTS)
    suspend fun networks(): List<NetworkInfo> = get(ApiRoutes.NETWORKS)
    suspend fun action(id: String): ActionStatus = get("${ApiRoutes.ACTIONS}/${ApiRoutes.encode(id)}")

    // Metrics
    suspend fun metrics(target: String, metric: String, fromMillis: Long, toMillis: Long, stepMillis: Long? = null): MetricSeries =
        get("${ApiRoutes.METRICS}?target=${ApiRoutes.encode(target)}&metric=$metric&from=$fromMillis&to=$toMillis" + (stepMillis?.let { "&step=$it" } ?: ""))
    suspend fun trends(target: String? = null): TrendsReport = get(ApiRoutes.METRICS_TRENDS + (target?.let { "?target=${ApiRoutes.encode(it)}" } ?: ""))
    suspend fun forgetHistory(target: String) = sendUnit("POST", "${ApiRoutes.METRICS_FORGET}?target=${ApiRoutes.encode(target)}", null)

    // Alerts
    suspend fun alertRules(): List<AlertRule> = get(ApiRoutes.ALERT_RULES)
    suspend fun saveAlertRule(rule: AlertRule): AlertRule =
        if (rule.id.isEmpty()) send("POST", ApiRoutes.ALERT_RULES, encode(AlertRule.serializer(), rule))
        else send("PUT", "${ApiRoutes.ALERT_RULES}/${ApiRoutes.encode(rule.id)}", encode(AlertRule.serializer(), rule))
    suspend fun deleteAlertRule(id: String) = sendUnit("DELETE", "${ApiRoutes.ALERT_RULES}/${ApiRoutes.encode(id)}", null)
    suspend fun alertEvents(): List<AlertEvent> = get(ApiRoutes.ALERT_EVENTS)
    suspend fun ackAlert(id: String) = sendUnit("POST", "${ApiRoutes.ALERTS}/${ApiRoutes.encode(id)}/ack", null)
    suspend fun silenceRule(ruleId: String, minutes: Long?, target: String? = null) =
        sendUnit("POST", "${ApiRoutes.ALERT_RULES}/${ApiRoutes.encode(ruleId)}/silence", encode(SilenceRequest.serializer(), SilenceRequest(minutes, target)))
    suspend fun channels(): List<NotificationChannel> = get(ApiRoutes.CHANNELS)
    suspend fun saveChannels(channels: List<NotificationChannel>): List<NotificationChannel> =
        send("PUT", ApiRoutes.CHANNELS, ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(NotificationChannel.serializer()), channels))
    suspend fun testChannel(id: String) = sendUnit("POST", ApiRoutes.NOTIFY_TEST, encode(TestNotificationRequest.serializer(), TestNotificationRequest(id)))

    // Logs
    suspend fun logSources(target: String? = null): List<LogSource> = get(ApiRoutes.LOG_SOURCES + (target?.let { "?target=${ApiRoutes.encode(it)}" } ?: ""))
    suspend fun logs(source: String, tail: Int = 500): List<LogLine> = get("${ApiRoutes.LOGS}?source=${ApiRoutes.encode(source)}&tail=$tail")

    // Backups
    suspend fun backups(): List<BackupInfo> = get(ApiRoutes.BACKUPS)
    suspend fun scanSecrets(targets: List<String>): List<SecretScan> =
        send("POST", "${ApiRoutes.BACKUPS}/scan", encode(CreateBackupRequest.serializer(), CreateBackupRequest(targets)))
    suspend fun createBackup(request: CreateBackupRequest): BackupInfo = send("POST", ApiRoutes.BACKUPS, encode(CreateBackupRequest.serializer(), request))
    suspend fun backupDiff(id: String, target: String): BackupDiff = get("${ApiRoutes.BACKUPS}/${ApiRoutes.encode(id)}/diff?target=${ApiRoutes.encode(target)}")
    suspend fun restore(id: String, request: RestoreRequest): RestoreResult =
        send("POST", "${ApiRoutes.BACKUPS}/${ApiRoutes.encode(id)}/restore", encode(RestoreRequest.serializer(), request))
    suspend fun deleteBackup(id: String, confirmName: String) =
        sendUnit("DELETE", "${ApiRoutes.BACKUPS}/${ApiRoutes.encode(id)}", encode(ConfirmRequest.serializer(), ConfirmRequest(confirmName)))

    // Templates
    suspend fun templates(): List<VmTemplate> = get(ApiRoutes.TEMPLATES)
    suspend fun previewCreate(id: String, request: CreateVmRequest): CreateVmPreview =
        send("POST", "${ApiRoutes.TEMPLATES}/${ApiRoutes.encode(id)}/create-vm?dryRun=true", encode(CreateVmRequest.serializer(), request))
    suspend fun createFromTemplate(id: String, request: CreateVmRequest): ActionAccepted =
        send("POST", "${ApiRoutes.TEMPLATES}/${ApiRoutes.encode(id)}/create-vm?dryRun=false", encode(CreateVmRequest.serializer(), request))
    suspend fun saveTemplate(request: SaveTemplateRequest): VmTemplate = send("POST", ApiRoutes.TEMPLATES, encode(SaveTemplateRequest.serializer(), request))
    suspend fun deleteTemplate(id: String) = sendUnit("DELETE", "${ApiRoutes.TEMPLATES}/${ApiRoutes.encode(id)}", null)

    // Disk, settings, access
    suspend fun disk(): DiskReport = get(ApiRoutes.DISK)
    suspend fun cleanup(request: CleanupRequest, dryRun: Boolean): CleanupResult =
        send("POST", "${ApiRoutes.CLEANUP}?dryRun=$dryRun", encode(CleanupRequest.serializer(), request))
    suspend fun settings(): SettingsResponse = get(ApiRoutes.SETTINGS)
    suspend fun saveSettings(settings: Settings): SettingsResponse = send("PUT", ApiRoutes.SETTINGS, encode(Settings.serializer(), settings))
    suspend fun tokens(): List<TokenInfo> = get(ApiRoutes.TOKENS)
    suspend fun createToken(request: CreateTokenRequest): CreatedToken = send("POST", ApiRoutes.TOKENS, encode(CreateTokenRequest.serializer(), request))
    suspend fun revokeToken(id: String) = sendUnit("DELETE", "${ApiRoutes.TOKENS}/${ApiRoutes.encode(id)}", null)
    suspend fun users(): List<UserInfo> = get(ApiRoutes.USERS)
    suspend fun createUser(request: CreateUserRequest): UserInfo = send("POST", ApiRoutes.USERS, encode(CreateUserRequest.serializer(), request))
    suspend fun deleteUser(username: String) = sendUnit("DELETE", "${ApiRoutes.USERS}/${ApiRoutes.encode(username)}", null)
    suspend fun audit(fromMillis: Long? = null, toMillis: Long? = null, actor: String? = null): List<AuditEntry> {
        val query = listOfNotNull(fromMillis?.let { "from=$it" }, toMillis?.let { "to=$it" }, actor?.let { "actor=${ApiRoutes.encode(it)}" })
        return get(ApiRoutes.AUDIT + if (query.isEmpty()) "" else "?" + query.joinToString("&"))
    }

    // ---------- plumbing ----------

    private fun profileQuery(profile: String?) = profile?.let { "?profile=${ApiRoutes.encode(it)}" } ?: ""
    private fun forceQuery(ref: VmRef, force: Boolean) = if (!force) "" else if (ref.home == null) "?force=true" else "&force=true"
    private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T) = ApiJson.encodeToString(serializer, value)

    /** Headers for a write the client cannot make itself (browser file upload); loads the CSRF token first. */
    suspend fun writeHeaders(): Map<String, String> {
        if (csrfToken == null) session()
        return headers(write = true) - "Content-Type"
    }

    private fun headers(write: Boolean, csrf: Boolean = true): Map<String, String> = buildMap {
        if (token.isNotEmpty()) put(ApiRoutes.TOKEN_HEADER, token)
        if (write && csrf) csrfToken?.let { put(ApiRoutes.CSRF_HEADER, it) }
        put("Content-Type", "application/json")
    }

    private suspend fun raw(method: String, path: String, body: String?, csrf: Boolean = true): String {
        val write = method != "GET"
        if (write && csrf && csrfToken == null) session()
        val result = transport.request(method, path, body, headers(write, csrf))
        if (result.status !in 200..299) {
            val error = runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body) }.getOrNull()
            throw ApiException(result.status, error?.code ?: "http_${result.status}", error?.message ?: "HTTP ${result.status}")
        }
        return result.body
    }

    private suspend inline fun <reified T> get(path: String): T = ApiJson.decodeFromString(raw("GET", path, null))

    private suspend inline fun <reified T> send(method: String, path: String, body: String?, csrf: Boolean = true): T =
        ApiJson.decodeFromString(raw(method, path, body, csrf))

    private suspend fun sendUnit(method: String, path: String, body: String?) {
        raw(method, path, body)
    }
}
