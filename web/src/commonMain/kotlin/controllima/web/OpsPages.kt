package controllima.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import controllima.shared.AlertEvent
import controllima.shared.AlertKind
import controllima.shared.AlertRule
import controllima.shared.AlertState
import controllima.shared.ApiException
import controllima.shared.ChannelKind
import controllima.shared.ChartMath
import controllima.shared.Comparison
import controllima.shared.Format
import controllima.shared.Metric
import controllima.shared.NotificationChannel
import controllima.shared.PortOwnerKind
import controllima.shared.Role
import controllima.shared.SelectorKind
import controllima.shared.Severity
import controllima.shared.TargetSelector
import controllima.shared.Targets
import controllima.shared.VmTable
import kotlinx.coroutines.launch

// ---------- Ports & Network (docs/pages/ports-network.md) ----------

@Composable
fun PortsPage(m: AppModel) {
    var query by remember { mutableStateOf("") }
    val ports = load(Unit, everyMillis = 15_000) { m.api.ports() }
    val networks = load(Unit) { m.api.networks() }
    PageHeader(Page.Ports.title) { SearchField(query, { query = it }, m, "Port or owner") }
    val r = ports.value
    if (r == null) {
        if (ports.error != null) Banner(ports.error, action = Strings.RETRY to ports.reload) else Skeleton(6)
        return
    }
    r.warnings.forEach { Banner(it, BannerKind.WARNING) }
    val rows = r.ports.filter { p -> query.isBlank() || p.hostPort.toString().contains(query) || p.owner.contains(query, true) || p.hostIp.contains(query) }
    Section("Ports") {
        if (rows.isEmpty()) {
            Text(Strings.NO_PORTS)
        } else {
            TableHeader(listOf(Col("Host", 2f), Col("Proto", 0.8f), Col("Target", 1f), Col("Owner", 2.4f), Col("State", 1.6f), Col("Conflict", 2.4f), Col("", 2.2f)))
            rows.sortedWith(compareBy({ it.hostPort }, { it.hostIp })).forEach { p ->
                TableRow(highlight = p.conflict != null) {
                    TextCell(2f, "${p.hostIp}:${p.hostPort}", mono = true)
                    TextCell(0.8f, p.proto)
                    TextCell(1f, p.targetPort?.toString())
                    Cell(2.4f) {
                        val kind = when (p.ownerKind) { PortOwnerKind.VM -> "VM"; PortOwnerKind.CONTAINER -> "Container"; PortOwnerKind.PROCESS -> "Process"; PortOwnerKind.SERVICE -> "k8s service"; PortOwnerKind.FORWARD -> "k8s forward" }
                        val jump = p.ownerTarget?.let { t -> ownerScreen(t) }
                        if (jump != null) TextButton(onClick = { m.navigate(jump) }) { Text("$kind ${p.owner}") } else Text("$kind ${p.owner}")
                    }
                    TextCell(1.6f, p.state.ifEmpty { null })
                    Cell(2.4f) { p.conflict?.let { Marked(Glyph.TRIANGLE, it, MaterialTheme.colorScheme.error) } }
                    Cell(2.2f) {
                        FlowRow {
                            val host = if (p.hostIp == "0.0.0.0" || p.hostIp == "::" || p.hostIp == "*") "127.0.0.1" else p.hostIp
                            if (p.proto == "tcp") SmallAction(Strings.OPEN) { openExternal("http://${if (':' in host) "[$host]" else host}:${p.hostPort}") }
                            SmallAction(Strings.COPY) { if (copyToClipboard("$host:${p.hostPort}")) m.toast("Copied") }
                        }
                    }
                }
            }
        }
    }
    Section("IP addresses") {
        if (r.ips.isEmpty()) Text("No addresses known yet.")
        r.ips.forEach { ip ->
            Row {
                val jump = ownerScreen(ip.ownerTarget)
                if (jump != null) TextButton(onClick = { m.navigate(jump) }) { Text(ip.owner) } else Text(ip.owner, Modifier.width(200.dp))
                Mono(ip.addresses.joinToString(", "), Modifier.padding(top = 12.dp))
            }
        }
    }
    Section("Networks") {
        val n = networks.value
        when {
            n == null -> if (networks.error != null) Text(networks.error) else Skeleton(2)
            n.isEmpty() -> Text("No networks reported.")
            else -> {
                TableHeader(listOf(Col("Name", 2f), Col("Kind", 1.2f), Col("Driver", 1.2f), Col("Subnet", 1.6f), Col("Details", 3f)))
                n.forEach { net ->
                    TableRow {
                        TextCell(2f, net.name)
                        TextCell(1.2f, net.kind)
                        TextCell(1.2f, net.driver)
                        TextCell(1.6f, net.subnet, mono = true)
                        TextCell(3f, net.details)
                    }
                }
            }
        }
    }
}

fun ownerScreen(target: String): Screen? =
    Targets.parseVm(target)?.let { Screen.VmDetail(it) } ?: Targets.parseContainer(target)?.let { (p, id) -> Screen.ContainerDetail(p, id) }

// ---------- Metrics (docs/pages/metrics.md) ----------

@Composable
fun MetricsPage(m: AppModel) {
    val targets = remember { mutableStateOf(listOf<String>()) }
    var metric by remember { mutableStateOf(Metric.CPU) }
    var range by remember { mutableStateOf(Range.H1) }
    var asTable by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<String?>(null) }
    val options = m.vms?.vms.orEmpty().map { it.ref.targetId } + m.containerLists.values.flatMap { l -> l.containers.map { it.targetId } }
    if (targets.value.isEmpty() && options.isNotEmpty()) {
        targets.value = m.vms?.vms.orEmpty().filter { it.status.name == "RUNNING" }.map { it.ref.targetId }.take(3).ifEmpty { options.take(1) }
    }
    val trends = load(Unit, everyMillis = 300_000) { m.api.trends() }
    PageHeader(Page.Metrics.title, trends.value?.let { "History store ${Format.bytes(it.storeBytes, m.units)}" }) {
        TextButton(onClick = { asTable = !asTable }) { Text(if (asTable) Strings.CHART_VIEW else Strings.TABLE_VIEW) }
    }
    trends.value?.let { t ->
        val cards = t.trends.filter { it.diskFullInDays != null || (it.memoryPressure ?: 0.0) > 0.05 }
        if (cards.isNotEmpty() || t.topCpu.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                cards.forEach { tr ->
                    Card(Modifier.width(260.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.label(tr.target), style = MaterialTheme.typography.titleSmall)
                            tr.diskFullInDays?.let { Text("Disk full in ~${Format.oneDecimal(it)} days", color = if (it < 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                            tr.diskGrowthBytesPerDay?.let { Text("Growth ${Format.bytes(it.toLong(), m.units)}/day") }
                            tr.memoryPressure?.takeIf { it > 0.05 }?.let { Text("Memory at or above 90 % for ${Format.percent(it * 100)} of the last 24 h") }
                        }
                    }
                }
                if (t.topCpu.isNotEmpty()) Card(Modifier.width(260.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Top CPU this week", style = MaterialTheme.typography.titleSmall)
                        t.topCpu.take(5).forEach { Text(m.label(it)) }
                    }
                }
            }
        }
    }
    Text("Targets (up to 6)", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        options.forEach { t ->
            val on = t in targets.value
            FilterChip(selected = on, enabled = on || targets.value.size < 6, onClick = { targets.value = if (on) targets.value - t else targets.value + t }, label = { Text(m.label(t)) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Picker("Metric", Metric.all, metric, ::metricTitle) { metric = it }
        ChipRow(Range.entries.drop(0), range, { if (it == Range.LIVE) "15 m" else it.label }) { range = it }
    }
    val now = Clock.now()
    val selected = targets.value
    val data = load(selected, metric, range, now / 30_000) {
        selected.map { t -> m.api.metrics(t, metric, now - range.millis, now) }
    }
    val series = data.value
    if (data.error != null) Banner(data.error, action = Strings.RETRY to data.reload)
    if (series == null) {
        Skeleton(5)
        return
    }
    if (series.all { it.points.isEmpty() }) {
        EmptyState(Strings.COLLECTING)
    } else if (asTable) {
        Section(metricTitle(metric)) {
            TableHeader(listOf(Col("Time", 2f)) + series.map { Col(m.label(it.target), 1.5f) })
            val times = series.flatMap { s -> s.points.map { it.t } }.distinct().sortedDescending().take(200)
            times.forEach { t ->
                TableRow {
                    TextCell(2f, Format.timestamp(t))
                    series.forEach { s -> TextCell(1.5f, s.points.firstOrNull { it.t == t }?.let { Format.metric(metric, it.avg, m.units) }) }
                }
            }
        }
    } else {
        Section(metricTitle(metric), actions = {
            TextButton(onClick = { saveText(ChartMath.csv(series), "metrics-$metric.csv", "text/csv") }) { Text(Strings.EXPORT_CSV) }
        }) {
            LineChart(series.map { ChartLine(m.label(it.target), it.points, it.stepMillis) }, now - range.millis, now, metric, m.units, series.flatMap { it.gaps }, height = 280)
        }
    }
    if (m.can(Role.ADMIN) && selected.isNotEmpty()) {
        Section("Forget history") {
            Text("Deletes stored history for one target (it keeps collecting).")
            FlowRow { selected.forEach { t -> SmallAction("Forget ${m.label(t)}", danger = true) { forget = t } } }
        }
    }
    forget?.let { t ->
        val label = m.label(t)
        ConfirmDialog("Forget history of $label?", label, listOf("All stored points of every metric for $label are deleted."), confirmLabel = "Forget", onDismiss = { forget = null }) {
            m.api.forgetHistory(t)
            m.toast("History of $label deleted")
            data.reload()
        }
    }
}

// ---------- Alerts (docs/pages/alerts.md) ----------

@Composable
fun AlertsPage(m: AppModel) {
    var tab by remember { mutableStateOf(0) }
    val events = load(Unit, everyMillis = 15_000) { m.api.alertEvents() }
    PageHeader(Page.Alerts.title)
    Tabs(listOf("Firing", "History", "Rules", "Channels"), tab) { tab = it }
    when (tab) {
        0, 1 -> {
            val list = events.value
            if (list == null) {
                if (events.error != null) Banner("Alert engine: ${events.error}", action = Strings.RETRY to events.reload) else Skeleton(4)
                return
            }
            val shown = if (tab == 0) list.filter { it.state == AlertState.FIRING || it.state == AlertState.ACKNOWLEDGED || it.state == AlertState.PENDING }
            else list.filter { it.state == AlertState.RESOLVED }
            if (shown.isEmpty()) {
                EmptyState(if (tab == 0) Strings.NO_ALERTS else "No resolved alerts in the last 90 days")
                return
            }
            TableHeader(listOf(Col("Severity", 1.2f), Col("Rule", 2f), Col("Target", 2f), Col("State", 1.4f), Col("Since", 1.6f), Col("Message", 4f), Col("", 3f)))
            shown.sortedByDescending { it.startedMillis }.forEach { e -> AlertRow(m, e) { events.reload(); m.scope.launch { m.refreshAlerts() } } }
        }
        2 -> RulesTab(m)
        3 -> ChannelsTab(m)
    }
}

@Composable
private fun AlertRow(m: AppModel, e: AlertEvent, onChanged: () -> Unit) {
    TableRow(highlight = e.state == AlertState.FIRING && e.severity == Severity.CRITICAL) {
        Cell(1.2f) { SeverityMark(e.severity) }
        TextCell(2f, e.ruleName)
        Cell(2f) {
            val jump = ownerScreen(e.target)
            if (jump != null) TextButton(onClick = { m.navigate(jump) }) { Text(m.label(e.target)) } else Text(m.label(e.target))
        }
        TextCell(1.4f, e.state.name.lowercase() + (e.acknowledgedBy?.let { " by $it" } ?: ""))
        TextCell(1.6f, Format.timestamp(e.firingMillis ?: e.startedMillis))
        TextCell(4f, e.message)
        Cell(3f) {
            FlowRow {
                if (e.state == AlertState.FIRING) SmallAction("Acknowledge", m.can(Role.OPERATOR)) { m.launchCall("Acknowledged", { m.api.ackAlert(e.id) }) { onChanged() } }
                if (e.state != AlertState.RESOLVED) SmallAction("Silence 1 h", m.can(Role.OPERATOR)) { m.launchCall("Silenced for 1 h", { m.api.silenceRule(e.ruleId, 60, e.target) }) { onChanged() } }
                // VM-down alerts offer a start, but only on this explicit click.
                val ref = Targets.parseVm(e.target)
                val vm = ref?.let { r -> m.vms?.vms?.firstOrNull { it.ref == r } }
                if (e.ruleId.contains(AlertKind.VM_DOWN) && vm != null && VmTable.canStart(vm)) {
                    SmallAction("Start VM", m.can(Role.OPERATOR)) { m.runAction("Start ${vm.name}", { m.api.startVm(vm.ref).actionId }) }
                }
            }
        }
    }
}

@Composable
private fun SeverityMark(s: Severity) = when (s) {
    Severity.INFO -> Marked(Glyph.RING, "info", MaterialTheme.colorScheme.onSurfaceVariant)
    Severity.WARNING -> Marked(Glyph.TRIANGLE, "warning", Palette.warning)
    Severity.CRITICAL -> Marked(Glyph.CROSS, "critical", MaterialTheme.colorScheme.error)
}

@Composable
private fun RulesTab(m: AppModel) {
    val rules = load(Unit) { m.api.alertRules() }
    var editing by remember { mutableStateOf<AlertRule?>(null) }
    var deleting by remember { mutableStateOf<AlertRule?>(null) }
    Row(Modifier.padding(bottom = 8.dp)) {
        RoleButton(m, "New rule", Role.ADMIN, primary = true) { editing = AlertRule("", "New rule", metric = Metric.CPU, threshold = 90.0, forSeconds = 300) }
    }
    val list = rules.value
    if (list == null) {
        if (rules.error != null) Banner(rules.error, action = Strings.RETRY to rules.reload) else Skeleton(4)
        return
    }
    TableHeader(listOf(Col("On", 0.7f), Col("Name", 2.4f), Col("Kind", 1.6f), Col("Targets", 1.8f), Col("Condition", 2.4f), Col("Severity", 1.2f), Col("", 2.4f)))
    list.forEach { r ->
        TableRow {
            Cell(0.7f) {
                Switch(r.enabled, enabled = m.can(Role.ADMIN), onCheckedChange = { on -> m.launchCall(null, { m.api.saveAlertRule(r.copy(enabled = on)) }) { rules.reload() } })
            }
            Cell(2.4f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name)
                    if (r.builtIn) Tag("built-in")
                    r.silencedUntilMillis?.takeIf { it > Clock.now() }?.let { Tag("silenced to ${Format.clock(it)}") }
                }
            }
            TextCell(1.6f, r.kind)
            TextCell(1.8f, selectorLabel(r.selector))
            TextCell(2.4f, conditionLabel(r))
            TextCell(1.2f, r.severity.name.lowercase())
            Cell(2.4f) {
                Row {
                    SmallAction("Edit", m.can(Role.ADMIN)) { editing = r }
                    if (!r.builtIn) SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { deleting = r }
                }
            }
        }
    }
    editing?.let { r -> RuleDialog(m, r, onDismiss = { editing = null }) { rules.reload() } }
    deleting?.let { r ->
        ConfirmDialog("Delete rule ${r.name}?", r.name, listOf("Its alert history is kept."), onDismiss = { deleting = null }) {
            m.api.deleteAlertRule(r.id)
            rules.reload()
        }
    }
}

private fun selectorLabel(s: TargetSelector) = when (s.kind) {
    SelectorKind.VM -> "VM ${s.value}"
    SelectorKind.ALL_VMS -> "All VMs"
    SelectorKind.ALL_CONTAINERS -> "All containers"
    SelectorKind.NAME_PATTERN -> "Names like ${s.value}"
    SelectorKind.HOST -> "Host"
}

private fun conditionLabel(r: AlertRule): String = when (r.kind) {
    AlertKind.THRESHOLD -> "${r.metric?.let(::metricTitle) ?: "?"} ${cmp(r.comparison)} ${Format.oneDecimal(r.threshold)}" +
        (r.criticalThreshold?.let { " (critical ${Format.oneDecimal(it)})" } ?: "") + if (r.forSeconds > 0) " for ${Format.duration(r.forSeconds * 1000)}" else ""
    else -> r.kind.replace('_', ' ') + if (r.threshold != 0.0) " (${Format.oneDecimal(r.threshold)})" else ""
}

private fun cmp(c: Comparison) = when (c) { Comparison.GT -> ">"; Comparison.GTE -> ">="; Comparison.LT -> "<"; Comparison.LTE -> "<=" }

@Composable
private fun RuleDialog(m: AppModel, initial: AlertRule, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var r by remember { mutableStateOf(initial) }
    var threshold by remember { mutableStateOf(Format.oneDecimal(initial.threshold)) }
    var critical by remember { mutableStateOf(initial.criticalThreshold?.let(Format::oneDecimal) ?: "") }
    var forMinutes by remember { mutableStateOf((initial.forSeconds / 60).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val channels = load(Unit) { m.api.channels() }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "New alert rule" else "Edit ${initial.name}") },
        text = {
            Column {
                OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = !r.builtIn, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (!r.builtIn) Picker("Kind", listOf(AlertKind.THRESHOLD, AlertKind.VM_DOWN, AlertKind.VM_UNRESPONSIVE, AlertKind.HOST_DISK_FREE, AlertKind.DISK_FORECAST, AlertKind.CONTAINER_RESTARTS, AlertKind.CONTAINER_EXITED), r.kind, { it.replace('_', ' ') }) { r = r.copy(kind = it) }
                    Picker("Severity", Severity.entries, r.severity, { it.name.lowercase() }) { r = r.copy(severity = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Picker("Targets", SelectorKind.entries, r.selector.kind, { it.name.lowercase().replace('_', ' ') }) { r = r.copy(selector = TargetSelector(it, r.selector.value)) }
                    if (r.selector.kind == SelectorKind.VM || r.selector.kind == SelectorKind.NAME_PATTERN) {
                        OutlinedTextField(r.selector.value ?: "", { r = r.copy(selector = r.selector.copy(value = it)) }, label = { Text(if (r.selector.kind == SelectorKind.VM) "VM name" else "Pattern (tc-*)") }, singleLine = true)
                    }
                }
                if (r.kind == AlertKind.THRESHOLD) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Picker("Metric", Metric.all, r.metric, ::metricTitle) { r = r.copy(metric = it) }
                        Picker("", Comparison.entries, r.comparison, ::cmp) { r = r.copy(comparison = it) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(threshold, { threshold = it }, label = { Text("Threshold") }, singleLine = true, modifier = Modifier.width(130.dp))
                    OutlinedTextField(critical, { critical = it }, label = { Text("Critical at") }, singleLine = true, modifier = Modifier.width(130.dp))
                    OutlinedTextField(forMinutes, { forMinutes = it }, label = { Text("For minutes") }, singleLine = true, modifier = Modifier.width(130.dp))
                }
                Text("Channels", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow {
                    channels.value.orEmpty().forEach { ch ->
                        val on = ch.id in r.channels
                        FilterChip(on, { r = r.copy(channels = if (on) r.channels - ch.id else r.channels + ch.id) }, label = { Text("${ch.kind.name.lowercase()} ${ch.id}") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.notifyResolved, { r = r.copy(notifyResolved = it) })
                    Text("Notify when resolved")
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                val t = threshold.toDoubleOrNull()
                val c = critical.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                val f = forMinutes.toLongOrNull()
                if (t == null || f == null || f < 0 || (critical.isNotBlank() && c == null)) {
                    error = "Threshold and minutes must be numbers"
                    return@Button
                }
                scope.launch {
                    try {
                        m.api.saveAlertRule(r.copy(threshold = t, criticalThreshold = c, forSeconds = f * 60))
                        onSaved()
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            }) { Text(Strings.SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
private fun ChannelsTab(m: AppModel) {
    if (!m.can(Role.ADMIN)) {
        Text(Strings.NEEDS_ADMIN)
        return
    }
    val data = load(Unit) { m.api.channels() }
    var draft by remember(data.value) { mutableStateOf(data.value.orEmpty()) }
    val list = data.value
    if (list == null) {
        if (data.error != null) Banner(data.error, action = Strings.RETRY to data.reload) else Skeleton(3)
        return
    }
    Text("Secrets (bot tokens, webhook URLs with keys) are write-only: they are stored in the OS keychain and never shown again.", Modifier.padding(bottom = 8.dp))
    draft.forEachIndexed { i, ch ->
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(ch.enabled, { on -> draft = draft.toMutableList().also { it[i] = ch.copy(enabled = on) } })
                Picker("Kind", ChannelKind.entries, ch.kind, { it.name.lowercase() }) { k -> draft = draft.toMutableList().also { it[i] = ch.copy(kind = k) } }
                OutlinedTextField(ch.id, { v -> draft = draft.toMutableList().also { it[i] = ch.copy(id = v) } }, label = { Text("Id") }, singleLine = true, modifier = Modifier.width(140.dp))
                if (ch.kind != ChannelKind.DESKTOP) {
                    OutlinedTextField(ch.target ?: "", { v -> draft = draft.toMutableList().also { it[i] = ch.copy(target = v) } },
                        label = { Text(if (ch.kind == ChannelKind.TELEGRAM) "Chat id" else "URL") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(ch.secret ?: "", { v -> draft = draft.toMutableList().also { it[i] = ch.copy(secret = v) } },
                        label = { Text(if (ch.hasSecret) "Secret (set; type to replace)" else "Secret") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.width(200.dp))
                }
                Column {
                    SmallAction("Test") { m.launchCall("Test sent", { m.api.testChannel(ch.id) }) { data.reload() } }
                    SmallAction("Remove", danger = true) { draft = draft.filterIndexed { j, _ -> j != i } }
                }
            }
            ch.lastResult?.let { Text("Last send: $it", Modifier.padding(start = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { draft = draft + NotificationChannel("channel-${draft.size + 1}", ChannelKind.DESKTOP) }) { Text("Add channel") }
        Button(onClick = { m.launchCall("Channels saved", { m.api.saveChannels(draft) }) { data.reload() } }) { Text(Strings.SAVE) }
    }
}
