package controllima.server.alerts

import controllima.server.ActionManager
import controllima.server.EventBus
import controllima.server.writePrivate
import controllima.shared.AlertEvent
import controllima.shared.AlertKind
import controllima.shared.AlertRule
import controllima.shared.AlertState
import controllima.shared.ApiJson
import controllima.shared.Comparison
import controllima.shared.Container
import controllima.shared.EventType
import controllima.shared.Metric
import controllima.shared.QuietHours
import controllima.shared.SelectorKind
import controllima.shared.ServerEvent
import controllima.shared.Severity
import controllima.shared.StatsSample
import controllima.shared.TargetSelector
import controllima.shared.Targets
import controllima.shared.Trend
import controllima.shared.Vm
import controllima.shared.VmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** What the engine reads each tick; built by the server from inventory, stats and trends. */
data class AlertInputs(
    val vms: List<Vm>,
    val containers: List<Container>,
    val samples: Map<String, StatsSample>,
    /** Free bytes per host volume target (`host:<path>`). */
    val hostFree: Map<String, Long>,
    val trends: List<Trend>,
    /** Restart counts per container target: (time, count) history. */
    val restarts: Map<String, List<Pair<Long, Int>>>,
    /** Containers that exited with a non-zero code without a user action: target → exit code. */
    val exited: Map<String, Int>,
    /** VMs that went down without a user action. */
    val down: Set<String>,
)

/** Measured condition for one target. */
data class Check(val target: String, val active: Boolean, val value: Double?, val message: String, val critical: Boolean = false)

/**
 * Alert rules and their lifecycle: pending → firing → (acknowledged) → resolved, de-duplicated per
 * rule and target, re-notified while firing, silences and quiet hours (docs/plan/alerts.md). Alerts
 * never act on VMs; they only notify.
 */
class AlertEngine(
    private val dir: File,
    private val channels: ChannelStore,
    private val notifier: Notifier,
    private val events: EventBus,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val rulesFile get() = File(dir, "rules.json")
    private val eventsFile get() = File(dir, "events.json")
    private val ruleSerializer = ListSerializer(AlertRule.serializer())
    private val eventSerializer = ListSerializer(AlertEvent.serializer())

    private var custom: List<AlertRule> = load(rulesFile, ruleSerializer)
    private val active = ConcurrentHashMap<String, AlertEvent>()
    private var history: List<AlertEvent> = load(eventsFile, eventSerializer)
    private val targetSilences = ConcurrentHashMap<String, Long>()

    init {
        history.filter { it.state == AlertState.FIRING || it.state == AlertState.ACKNOWLEDGED || it.state == AlertState.PENDING }
            .forEach { active[key(it.ruleId, it.target)] = it }
        history = history.filter { it.state == AlertState.RESOLVED }
    }

    private fun <T> load(file: File, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        if (!file.isFile) emptyList() else runCatching { ApiJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    // ---------- rules ----------

    /** Built-ins merged with the user's overrides, then custom rules. */
    @Synchronized
    fun rules(): List<AlertRule> {
        val overrides = custom.associateBy { it.id }
        return BUILT_INS.map { b -> overrides[b.id]?.copy(builtIn = true, kind = b.kind) ?: b } + custom.filter { c -> BUILT_INS.none { it.id == c.id } }
    }

    @Synchronized
    fun saveRule(rule: AlertRule, isNew: Boolean): AlertRule {
        validate(rule)
        val builtIn = BUILT_INS.any { it.id == rule.id }
        val saved = when {
            isNew -> rule.copy(id = "rule-" + java.util.UUID.randomUUID().toString().take(8), builtIn = false)
            builtIn -> rule.copy(builtIn = true)
            custom.any { it.id == rule.id } -> rule.copy(builtIn = false)
            else -> throw NoSuchElementException("No rule ${rule.id}")
        }
        custom = custom.filterNot { it.id == saved.id } + saved
        writePrivate(rulesFile, ApiJson.encodeToString(ruleSerializer, custom))
        return saved
    }

    /** Built-in rules cannot be deleted, only disabled. */
    @Synchronized
    fun deleteRule(id: String): Boolean {
        require(BUILT_INS.none { it.id == id }) { "Built-in rules can be disabled but not deleted" }
        val before = custom.size
        custom = custom.filterNot { it.id == id }
        writePrivate(rulesFile, ApiJson.encodeToString(ruleSerializer, custom))
        active.keys.filter { it.startsWith("$id|") }.forEach { active.remove(it) }
        return custom.size != before
    }

    /** Silences a rule (target == null) or one target of it; minutes null = until resumed, 0 = resume. */
    fun silence(ruleId: String, minutes: Long?, target: String?) {
        val rule = rules().firstOrNull { it.id == ruleId } ?: throw NoSuchElementException("No rule $ruleId")
        require(minutes == null || minutes in 0..525_600) { "minutes must be 0..525600" }
        val until = when (minutes) { null -> Long.MAX_VALUE; 0L -> null; else -> clock() + minutes * 60_000 }
        if (target != null) {
            if (until == null) targetSilences.remove(key(ruleId, target)) else targetSilences[key(ruleId, target)] = until
        } else {
            saveRule(rule.copy(silencedUntilMillis = until), isNew = false)
        }
    }

    private fun validate(rule: AlertRule) {
        require(rule.name.isNotBlank() && rule.name.length <= 100) { "Rule name is required (≤ 100 chars)" }
        require(rule.kind in KINDS) { "Unknown rule kind ${rule.kind}" }
        if (rule.kind == AlertKind.THRESHOLD) require(rule.metric in Metric.all) { "Threshold rules need a metric" }
        require(rule.forSeconds in 0..86_400) { "for must be 0..86400 s" }
        require(rule.renotifyMinutes in 0..10_080) { "re-notify must be 0..10080 min" }
        rule.quietHours?.let { require(it.fromHour in 0..23 && it.toHour in 0..23) { "Quiet hours must be 0..23" } }
        if (rule.selector.kind == SelectorKind.NAME_PATTERN || rule.selector.kind == SelectorKind.VM) {
            val value = rule.selector.value
            require(!value.isNullOrBlank() && value.length <= 200) { "Selector needs a value" }
        }
    }

    // ---------- events ----------

    fun events(): List<AlertEvent> {
        val cutoff = clock() - KEEP_MILLIS
        return (active.values + synchronized(this) { history }).filter { (it.resolvedMillis ?: it.startedMillis) >= cutoff }
            .sortedByDescending { it.firingMillis ?: it.startedMillis }
    }

    fun firing(): List<AlertEvent> = active.values.filter { it.state == AlertState.FIRING || it.state == AlertState.ACKNOWLEDGED }

    fun acknowledge(eventId: String, actor: String): AlertEvent {
        val (k, event) = active.entries.firstOrNull { it.value.id == eventId }?.toPair() ?: throw NoSuchElementException("No active alert $eventId")
        require(event.state == AlertState.FIRING) { "Only firing alerts can be acknowledged" }
        val acked = event.copy(state = AlertState.ACKNOWLEDGED, acknowledgedBy = actor)
        active[k] = acked
        events.emit(ServerEvent(EventType.ALERT, clock(), event.target, "${event.ruleName} acknowledged by $actor", alert = acked))
        persist()
        return acked
    }

    // ---------- evaluation ----------

    fun start(scope: CoroutineScope, inputs: suspend () -> AlertInputs): Job = scope.launch {
        while (isActive) {
            runCatching { evaluate(inputs()) }
            delay(5_000)
        }
    }

    suspend fun evaluate(inputs: AlertInputs) {
        val now = clock()
        val seen = mutableSetOf<String>()
        for (rule in rules().filter { it.enabled }) {
            for (check in checks(rule, inputs)) {
                val k = key(rule.id, check.target)
                seen += k
                step(rule, check, k, now)
            }
        }
        // Targets that disappeared (VM deleted, rule disabled) resolve.
        for (k in active.keys.toList()) if (k !in seen) {
            val e = active[k] ?: continue
            val rule = rules().firstOrNull { it.id == e.ruleId }
            resolve(k, e, rule, now)
        }
        persist()
    }

    private suspend fun step(rule: AlertRule, check: Check, k: String, now: Long) {
        val current = active[k]
        val severity = if (check.critical) Severity.CRITICAL else rule.severity
        if (!check.active) {
            if (current != null) resolve(k, current, rule, now)
            return
        }
        when {
            current == null -> {
                val pending = AlertEvent(
                    id = java.util.UUID.randomUUID().toString(), ruleId = rule.id, ruleName = rule.name, target = check.target,
                    severity = severity, state = AlertState.PENDING, message = check.message, startedMillis = now, value = check.value,
                )
                active[k] = pending
                if (rule.forSeconds == 0L) fire(k, pending, rule, now)
            }
            current.state == AlertState.PENDING -> {
                val updated = current.copy(message = check.message, value = check.value, severity = severity)
                active[k] = updated
                if (now - current.startedMillis >= rule.forSeconds * 1000) fire(k, updated, rule, now)
            }
            else -> {
                var updated = current.copy(message = check.message, value = check.value, severity = maxOf(severity, current.severity))
                val renotify = rule.renotifyMinutes > 0 && current.state == AlertState.FIRING &&
                    now - (current.lastNotifiedMillis ?: 0) >= rule.renotifyMinutes * 60_000L
                if (renotify && notify(rule, updated, "Still firing")) updated = updated.copy(lastNotifiedMillis = now)
                active[k] = updated
            }
        }
    }

    private suspend fun fire(k: String, event: AlertEvent, rule: AlertRule, now: Long) {
        var firing = event.copy(state = AlertState.FIRING, firingMillis = now)
        events.emit(ServerEvent(EventType.ALERT, now, event.target, "${rule.name}: ${event.message}", alert = firing))
        if (notify(rule, firing, "Firing")) firing = firing.copy(lastNotifiedMillis = now)
        active[k] = firing
    }

    private suspend fun resolve(k: String, event: AlertEvent, rule: AlertRule?, now: Long) {
        active.remove(k)
        if (event.state == AlertState.PENDING) return
        val resolved = event.copy(state = AlertState.RESOLVED, resolvedMillis = now)
        synchronized(this) { history = (history + resolved).takeLast(MAX_HISTORY) }
        events.emit(ServerEvent(EventType.ALERT, now, event.target, "${event.ruleName} resolved", alert = resolved))
        if (rule != null && rule.notifyResolved) notify(rule, resolved, "Resolved")
    }

    /** True when at least one channel was attempted; silences and quiet hours suppress sending only. */
    private suspend fun notify(rule: AlertRule, event: AlertEvent, prefix: String): Boolean {
        val now = clock()
        if ((rule.silencedUntilMillis ?: 0) > now) return false
        if ((targetSilences[key(rule.id, event.target)] ?: 0) > now) return false
        if (inQuietHours(rule.quietHours, now)) return false
        val title = "ControlLima: $prefix ${event.severity.name.lowercase()} — ${rule.name}"
        val message = "${Targets.label(event.target)}: ${event.message}"
        var attempted = false
        for (id in rule.channels) {
            val channel = channels.get(id)?.takeIf { it.enabled } ?: continue
            attempted = true
            val result = notifier.send(channel, title, message)
            channels.recordResult(id, result.fold({ "ok" }, { "failed: ${it.message}" }))
        }
        return attempted
    }

    fun inQuietHours(q: QuietHours?, now: Long): Boolean {
        if (q == null || q.fromHour == q.toHour) return false
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        return if (q.fromHour < q.toHour) hour in q.fromHour until q.toHour else hour >= q.fromHour || hour < q.toHour
    }

    private fun persist() {
        val all = active.values.toList() + synchronized(this) {
            val cutoff = clock() - KEEP_MILLIS
            history = history.filter { (it.resolvedMillis ?: it.startedMillis) >= cutoff }
            history
        }
        runCatching { writePrivate(eventsFile, ApiJson.encodeToString(eventSerializer, all)) }
    }

    private fun key(ruleId: String, target: String) = "$ruleId|$target"

    companion object {
        const val KEEP_MILLIS = 90L * 86_400_000
        const val MAX_HISTORY = 5_000
        private val KINDS = setOf(
            AlertKind.THRESHOLD, AlertKind.VM_DOWN, AlertKind.VM_UNRESPONSIVE, AlertKind.HOST_DISK_FREE,
            AlertKind.DISK_FORECAST, AlertKind.CONTAINER_RESTARTS, AlertKind.CONTAINER_EXITED,
        )

        private val ALL_VMS = TargetSelector(SelectorKind.ALL_VMS)
        private val ALL_CONTAINERS = TargetSelector(SelectorKind.ALL_CONTAINERS)
        private val DESKTOP = listOf("desktop")

        val BUILT_INS = listOf(
            AlertRule("builtin.vm_down", "VM down", AlertKind.VM_DOWN, ALL_VMS, severity = Severity.CRITICAL, channels = DESKTOP, builtIn = true),
            AlertRule("builtin.vm_unresponsive", "VM unresponsive", AlertKind.VM_UNRESPONSIVE, ALL_VMS, forSeconds = 30, severity = Severity.CRITICAL, channels = DESKTOP, builtIn = true),
            AlertRule("builtin.guest_disk", "Guest disk almost full", AlertKind.THRESHOLD, ALL_VMS, Metric.DISK_PERCENT, Comparison.GTE, 90.0, 97.0, 300, Severity.WARNING, DESKTOP, builtIn = true),
            AlertRule("builtin.host_disk", "Host disk almost full", AlertKind.HOST_DISK_FREE, TargetSelector(SelectorKind.HOST), comparison = Comparison.LT, threshold = 10.0, severity = Severity.WARNING, channels = DESKTOP, builtIn = true),
            AlertRule("builtin.disk_forecast", "Disk full forecast", AlertKind.DISK_FORECAST, ALL_VMS, comparison = Comparison.LT, threshold = 3.0, severity = Severity.WARNING, channels = DESKTOP, builtIn = true),
            AlertRule("builtin.memory", "High memory", AlertKind.THRESHOLD, ALL_VMS, Metric.MEMORY_PERCENT, Comparison.GTE, 95.0, null, 600, Severity.WARNING, DESKTOP, builtIn = true),
            AlertRule("builtin.cpu", "High CPU", AlertKind.THRESHOLD, ALL_VMS, Metric.CPU, Comparison.GTE, 90.0, null, 900, Severity.INFO, emptyList(), builtIn = true),
            AlertRule("builtin.container_restarts", "Container restart loop", AlertKind.CONTAINER_RESTARTS, ALL_CONTAINERS, comparison = Comparison.GTE, threshold = 3.0, severity = Severity.WARNING, channels = DESKTOP, builtIn = true),
            AlertRule("builtin.container_exited", "Container exited unexpectedly", AlertKind.CONTAINER_EXITED, ALL_CONTAINERS, severity = Severity.WARNING, channels = DESKTOP, builtIn = true),
        )

        fun compare(value: Double, op: Comparison, threshold: Double): Boolean = when (op) {
            Comparison.GT -> value > threshold
            Comparison.GTE -> value >= threshold
            Comparison.LT -> value < threshold
            Comparison.LTE -> value <= threshold
        }

        /** `*` matches any run of characters; everything else is literal. */
        fun glob(pattern: String, text: String): Boolean =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE).matches(text)

        fun selects(selector: TargetSelector, target: String, name: String, isVm: Boolean): Boolean = when (selector.kind) {
            SelectorKind.VM -> isVm && (selector.value == target || selector.value == name)
            SelectorKind.ALL_VMS -> isVm
            SelectorKind.ALL_CONTAINERS -> !isVm
            SelectorKind.NAME_PATTERN -> selector.value?.let { glob(it, name) || glob(it, target) } ?: false
            SelectorKind.HOST -> false
        }

        /** Conditions of [rule] for every target it selects. */
        fun checks(rule: AlertRule, inputs: AlertInputs): List<Check> {
            val vmTargets = inputs.vms.map { Triple(it.ref.targetId, it.name, true) }
            val containerTargets = inputs.containers.map { Triple(it.targetId, it.name, false) }
            val selected = (vmTargets + containerTargets).filter { (t, n, vm) -> selects(rule.selector, t, n, vm) }
            return when (rule.kind) {
                AlertKind.THRESHOLD -> selected.mapNotNull { (t, _, _) ->
                    val value = inputs.samples[t]?.let { Metric.valuesOf(it)[rule.metric] } ?: return@mapNotNull null
                    val active = compare(value, rule.comparison, rule.threshold)
                    val critical = rule.criticalThreshold?.let { compare(value, rule.comparison, it) } ?: false
                    Check(t, active, value, "${rule.metric} ${"%.1f".format(value)} (threshold ${rule.threshold})", critical)
                }
                AlertKind.VM_DOWN -> selected.filter { it.third }.map { (t, n, _) ->
                    val vm = inputs.vms.first { it.ref.targetId == t }
                    Check(t, t in inputs.down && vm.status != VmStatus.RUNNING, null, "$n is ${vm.status.name.lowercase()} without a user action")
                }
                AlertKind.VM_UNRESPONSIVE -> selected.filter { it.third }.map { (t, n, _) ->
                    val vm = inputs.vms.first { it.ref.targetId == t }
                    Check(t, vm.status == VmStatus.UNRESPONSIVE, null, "$n does not answer probes")
                }
                AlertKind.HOST_DISK_FREE -> inputs.hostFree.map { (t, free) ->
                    val gb = free / 1e9
                    Check(t, compare(gb, rule.comparison, rule.threshold), gb, "%.1f GB free on %s".format(gb, t.removePrefix("host:")))
                }
                AlertKind.DISK_FORECAST -> selected.mapNotNull { (t, n, _) ->
                    val days = inputs.trends.firstOrNull { it.target == t }?.diskFullInDays ?: return@mapNotNull Check(t, false, null, "")
                    Check(t, compare(days, rule.comparison, rule.threshold), days, "disk of $n full in %.1f days at the current rate".format(days))
                }
                AlertKind.CONTAINER_RESTARTS -> selected.filter { !it.third }.map { (t, n, _) ->
                    val history = inputs.restarts[t].orEmpty()
                    val now = history.lastOrNull()?.first ?: 0
                    val window = history.filter { now - it.first <= 10 * 60_000 }
                    val restarts = if (window.size < 2) 0 else window.last().second - window.first().second
                    Check(t, compare(restarts.toDouble(), rule.comparison, rule.threshold), restarts.toDouble(), "$n restarted $restarts times in 10 min")
                }
                AlertKind.CONTAINER_EXITED -> selected.filter { !it.third }.map { (t, n, _) ->
                    val code = inputs.exited[t]
                    Check(t, code != null, code?.toDouble(), "$n exited with code $code")
                }
                else -> emptyList()
            }
        }
    }
}
