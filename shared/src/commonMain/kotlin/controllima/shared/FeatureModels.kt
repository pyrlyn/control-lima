package controllima.shared

import kotlinx.serialization.Serializable

// ---------- Long-running actions and events ----------

@Serializable
enum class ActionState { RUNNING, SUCCEEDED, FAILED }

/** `202` body for start, stop, pull, restore and create-from-template. */
@Serializable
data class ActionAccepted(val actionId: String)

@Serializable
data class ActionStatus(
    val id: String,
    val kind: String,
    val target: String,
    val state: ActionState,
    val startedMillis: Long,
    val finishedMillis: Long? = null,
    val progress: List<String> = emptyList(),
    val error: String? = null,
)

/** Pushed on `/ws/events`. */
@Serializable
data class ServerEvent(
    val type: EventType,
    val timeMillis: Long,
    val target: String? = null,
    val message: String,
    val actionId: String? = null,
    val alert: AlertEvent? = null,
)

@Serializable
enum class EventType { VM_STATE, CONTAINER_STATE, ACTION_PROGRESS, ACTION_DONE, ALERT, INFO }

// ---------- Ports and network ----------

@Serializable
enum class PortOwnerKind { VM, CONTAINER, PROCESS, SERVICE }

@Serializable
data class PortEntry(
    val hostIp: String,
    val hostPort: Int,
    val proto: String,
    /** `guest:443`, `container:80` or the process name. */
    val targetPort: Int? = null,
    val ownerKind: PortOwnerKind,
    val owner: String,
    val ownerTarget: String? = null,
    val state: String = "",
    val conflict: String? = null,
)

@Serializable
data class HostListener(val pid: Int, val command: String, val hostIp: String, val port: Int)

@Serializable
data class IpEntry(val owner: String, val ownerTarget: String, val addresses: List<String>)

@Serializable
data class PortsReport(
    val ports: List<PortEntry>,
    val ips: List<IpEntry> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class NetworkInfo(val name: String, val kind: String, val driver: String? = null, val subnet: String? = null, val details: String? = null)

// ---------- Metrics ----------

/** Metric keys used in `/api/metrics?metric=` and alert rules. */
object Metric {
    const val CPU = "cpu"
    const val MEMORY = "memory"
    const val MEMORY_PERCENT = "memory_percent"
    const val NET_RX = "net_rx"
    const val NET_TX = "net_tx"
    const val DISK_READ = "disk_read"
    const val DISK_WRITE = "disk_write"
    const val DISK_USED = "disk_used"
    const val DISK_PERCENT = "disk_percent"
    const val DISK_TOTAL = "disk_total"
    const val LOAD = "load"
    const val PROBE_LATENCY = "probe_latency"
    const val HOST_CPU = "host_cpu"
    const val HOST_RSS = "host_rss"

    /** Order is persisted by the metrics store: append new keys at the end. */
    val all = listOf(CPU, MEMORY, MEMORY_PERCENT, NET_RX, NET_TX, DISK_READ, DISK_WRITE, DISK_USED, DISK_PERCENT, LOAD, PROBE_LATENCY, HOST_CPU, HOST_RSS, DISK_TOTAL)

    /** Values of [sample] keyed by metric. Missing values are left out. */
    fun valuesOf(sample: StatsSample): Map<String, Double> = buildMap {
        sample.cpuPercent?.let { put(CPU, it) }
        sample.memoryUsedBytes?.let { put(MEMORY, it.toDouble()) }
        sample.memoryPercent?.let { put(MEMORY_PERCENT, it) }
        sample.netRxBytesPerSec?.let { put(NET_RX, it) }
        sample.netTxBytesPerSec?.let { put(NET_TX, it) }
        sample.diskReadBytesPerSec?.let { put(DISK_READ, it) }
        sample.diskWriteBytesPerSec?.let { put(DISK_WRITE, it) }
        sample.diskUsedBytes?.let { put(DISK_USED, it.toDouble()) }
        sample.diskPercent?.let { put(DISK_PERCENT, it) }
        sample.diskTotalBytes?.let { put(DISK_TOTAL, it.toDouble()) }
        sample.load1?.let { put(LOAD, it) }
        sample.probeLatencyMs?.let { put(PROBE_LATENCY, it) }
        sample.hostCpuPercent?.let { put(HOST_CPU, it) }
        sample.hostRssBytes?.let { put(HOST_RSS, it.toDouble()) }
    }

    fun unit(metric: String): Unit = when (metric) {
        CPU, MEMORY_PERCENT, DISK_PERCENT, HOST_CPU -> Unit.PERCENT
        MEMORY, DISK_USED, DISK_TOTAL, HOST_RSS -> Unit.BYTES
        NET_RX, NET_TX, DISK_READ, DISK_WRITE -> Unit.BYTES_PER_SEC
        PROBE_LATENCY -> Unit.MILLIS
        else -> Unit.NONE
    }

    enum class Unit { PERCENT, BYTES, BYTES_PER_SEC, MILLIS, NONE }
}

@Serializable
data class MetricPoint(val t: Long, val avg: Double, val max: Double = avg, val p95: Double? = null)

@Serializable
data class MetricSeries(
    val target: String,
    val metric: String,
    val stepMillis: Long,
    val points: List<MetricPoint>,
    /** Periods when the target was unresponsive, shaded on charts. */
    val gaps: List<TimeRange> = emptyList(),
)

@Serializable
data class TimeRange(val fromMillis: Long, val toMillis: Long)

@Serializable
data class Trend(
    val target: String,
    /** Linear regression over 7 days, bytes per day. */
    val diskGrowthBytesPerDay: Double? = null,
    val diskFullInDays: Double? = null,
    /** Share of time above 90 % memory in the last 24 h, 0..1. */
    val memoryPressure: Double? = null,
    val avgCpuWeek: Double? = null,
)

@Serializable
data class TrendsReport(val trends: List<Trend>, val topCpu: List<String> = emptyList(), val storeBytes: Long = 0)

// ---------- Alerts ----------

@Serializable
enum class Severity { INFO, WARNING, CRITICAL }

@Serializable
enum class Comparison { GT, GTE, LT, LTE }

@Serializable
enum class SelectorKind { VM, ALL_VMS, ALL_CONTAINERS, NAME_PATTERN, HOST }

@Serializable
data class TargetSelector(val kind: SelectorKind, val value: String? = null)

/** Rule kinds besides plain metric thresholds. */
object AlertKind {
    const val THRESHOLD = "threshold"
    const val VM_DOWN = "vm_down"
    const val VM_UNRESPONSIVE = "vm_unresponsive"
    const val HOST_DISK_FREE = "host_disk_free"
    const val DISK_FORECAST = "disk_forecast"
    const val CONTAINER_RESTARTS = "container_restarts"
    const val CONTAINER_EXITED = "container_exited"
}

@Serializable
data class QuietHours(val fromHour: Int, val toHour: Int)

@Serializable
data class AlertRule(
    val id: String,
    val name: String,
    val kind: String = AlertKind.THRESHOLD,
    val selector: TargetSelector = TargetSelector(SelectorKind.ALL_VMS),
    val metric: String? = null,
    val comparison: Comparison = Comparison.GTE,
    val threshold: Double = 0.0,
    /** Second threshold that raises severity to critical (e.g. disk ≥ 97 %). */
    val criticalThreshold: Double? = null,
    val forSeconds: Long = 0,
    val severity: Severity = Severity.WARNING,
    val channels: List<String> = emptyList(),
    val quietHours: QuietHours? = null,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val silencedUntilMillis: Long? = null,
    val renotifyMinutes: Int = 60,
    val notifyResolved: Boolean = false,
)

@Serializable
enum class AlertState { PENDING, FIRING, ACKNOWLEDGED, RESOLVED }

@Serializable
data class AlertEvent(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val target: String,
    val severity: Severity,
    val state: AlertState,
    val message: String,
    val startedMillis: Long,
    val firingMillis: Long? = null,
    val resolvedMillis: Long? = null,
    val acknowledgedBy: String? = null,
    val lastNotifiedMillis: Long? = null,
    val value: Double? = null,
)

@Serializable
data class SilenceRequest(val minutes: Long? = null, val target: String? = null)

@Serializable
enum class ChannelKind { DESKTOP, WEBHOOK, TELEGRAM, SLACK }

/** Notification channel. Secrets are write-only: [secret] is accepted on PUT and never returned. */
@Serializable
data class NotificationChannel(
    val id: String,
    val kind: ChannelKind,
    val enabled: Boolean = true,
    /** Webhook URL (non-secret part), or Telegram chat id. */
    val target: String? = null,
    val secret: String? = null,
    val hasSecret: Boolean = false,
    val lastResult: String? = null,
)

@Serializable
data class TestNotificationRequest(val channelId: String)

// ---------- Logs ----------

@Serializable
data class LogSource(val id: String, val target: String, val kind: String, val label: String, val available: Boolean = true, val reason: String? = null)

@Serializable
data class LogLine(val ts: Long?, val stream: String, val line: String, val level: String? = null)

// ---------- Backups ----------

@Serializable
data class BackupItem(val target: String, val path: String, val sha256: String, val sizeBytes: Long, val secretsFlagged: List<String> = emptyList())

@Serializable
data class BackupInfo(
    val id: String,
    val createdMillis: Long,
    val scheduled: Boolean,
    val sizeBytes: Long,
    val items: List<BackupItem>,
    val limaVersion: String? = null,
    val colimaVersion: String? = null,
    val redacted: Boolean = false,
)

@Serializable
data class CreateBackupRequest(val targets: List<String> = emptyList(), val includeSettings: Boolean = true, val redactSecrets: Boolean = false)

@Serializable
data class SecretScan(val target: String, val findings: List<String>)

@Serializable
data class DiffLine(val kind: DiffKind, val text: String)

@Serializable
enum class DiffKind { SAME, ADDED, REMOVED }

@Serializable
data class BackupDiff(val target: String, val lines: List<DiffLine>, val currentExists: Boolean)

@Serializable
data class RestoreRequest(val targets: List<String>, val confirmName: String, val newName: String? = null)

@Serializable
data class RestoreResult(
    val restored: List<String>,
    val restartRequired: List<String> = emptyList(),
    val created: List<String> = emptyList(),
    /** Set when a VM is being created from the backup (`limactl create`), which runs as an action. */
    val actionId: String? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class BackupSchedule(val enabled: Boolean = false, val everyDays: Int = 1, val keep: Int = 7)

// ---------- Templates ----------

@Serializable
data class VmTemplate(
    val id: String,
    val name: String,
    val base: String,
    val description: String,
    val builtIn: Boolean,
    val cpus: Int? = null,
    val memoryGiB: Double? = null,
    val diskGiB: Double? = null,
    val arch: String? = null,
    val yaml: String? = null,
    /** Colima templates cannot be created stopped; they are preview-only. */
    val colima: Boolean = false,
)

@Serializable
data class CreateVmRequest(
    val name: String,
    val cpus: Int? = null,
    val memoryGiB: Double? = null,
    val diskGiB: Double? = null,
    val mounts: List<String> = emptyList(),
    val portForwards: List<String> = emptyList(),
    val provision: String? = null,
)

@Serializable
data class CreateVmPreview(val command: List<String>, val yaml: String?, val warnings: List<String> = emptyList())

@Serializable
data class SaveTemplateRequest(val name: String, val description: String = "", val fromVm: String? = null, val yaml: String? = null)

// ---------- Disk ----------

@Serializable
data class VmDisk(val target: String, val name: String, val allocatedBytes: Long, val apparentBytes: Long, val guestUsedBytes: Long? = null, val guestTotalBytes: Long? = null)

@Serializable
data class DockerDisk(val profile: String, val imagesBytes: Long, val containersBytes: Long, val volumesBytes: Long, val buildCacheBytes: Long, val reclaimableBytes: Long)

@Serializable
data class DiskReport(
    val hostFreeBytes: Long,
    val hostTotalBytes: Long,
    val vms: List<VmDisk>,
    val docker: List<DockerDisk>,
    val backupsBytes: Long = 0,
    val metricsBytes: Long = 0,
    val suggestions: List<CleanupSuggestion> = emptyList(),
)

@Serializable
data class CleanupSuggestion(
    val id: String,
    val title: String,
    val bytes: Long?,
    val items: List<String> = emptyList(),
    val note: String? = null,
    /** What the user types to confirm; null means the suggestion is informational only. */
    val confirm: String? = null,
)

@Serializable
data class CleanupRequest(val id: String, val confirmName: String, val profile: String? = null)

@Serializable
data class CleanupResult(val id: String, val dryRun: Boolean, val items: List<String>, val bytes: Long)

@Serializable
data class PullRequest(val ref: String)

@Serializable
data class PruneResult(val dryRun: Boolean, val images: List<String>, val bytes: Long)

// ---------- Settings ----------

@Serializable
enum class Theme { SYSTEM, LIGHT, DARK }

@Serializable
enum class SizeUnits { BINARY, DECIMAL }

@Serializable
data class Settings(
    val limaHomes: List<String> = emptyList(),
    val colimaProfiles: List<String> = emptyList(),
    val defaultProfile: String = "default",
    val limactlPath: String = "limactl",
    val colimaPath: String = "colima",
    val dockerSocketOverrides: Map<String, String> = emptyMap(),
    val limaArgs: List<String> = emptyList(),
    val colimaArgs: List<String> = emptyList(),
    val liveIntervalSeconds: Int = 2,
    val backgroundIntervalSeconds: Int = 15,
    val probeTimeoutSeconds: Int = 3,
    val protectedNames: List<String> = ProtectedNames.defaults.toList(),
    val retentionRawHours: Int = 24,
    val retention1mDays: Int = 7,
    val retention15mDays: Int = 30,
    val retention1hDays: Int = 365,
    val theme: Theme = Theme.SYSTEM,
    val compact: Boolean = false,
    val units: SizeUnits = SizeUnits.BINARY,
    val updateCheck: Boolean = false,
    val multiUser: Boolean = false,
    val backupSchedule: BackupSchedule = BackupSchedule(),
    val redactionPatterns: List<String> = listOf("(?i)(password|passwd|secret|token|api[_-]?key)=\\S+"),
)

@Serializable
data class AboutInfo(
    val version: String,
    val dataDir: String,
    val configFile: String,
    val limaVersion: String? = null,
    val colimaVersion: String? = null,
    val warnings: List<String> = emptyList(),
    val latestVersion: String? = null,
)

@Serializable
data class SettingsResponse(val settings: Settings, val about: AboutInfo)

// ---------- WebSocket messages ----------

/** Client → `/ws/stats`: change the watched targets. */
@Serializable
data class StatsSubscription(val subscribe: List<String> = emptyList(), val unsubscribe: List<String> = emptyList())

/** Server → `/ws/stats`: the buffered history of newly watched targets first (`history = true`), then live samples. */
@Serializable
data class StatsBatch(val samples: List<StatsSample>, val history: Boolean = false)

/** Server → `/ws/logs`: lines in small batches; [error] ends the stream. */
@Serializable
data class LogBatch(val lines: List<LogLine> = emptyList(), val error: String? = null)

