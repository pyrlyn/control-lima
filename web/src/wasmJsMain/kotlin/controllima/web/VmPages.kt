package controllima.web

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import controllima.shared.AlertState
import controllima.shared.ApiException
import controllima.shared.Confirm
import controllima.shared.CreateBackupRequest
import controllima.shared.EventType
import controllima.shared.Format
import controllima.shared.Metric
import controllima.shared.MetricSeries
import controllima.shared.Names
import controllima.shared.Role
import controllima.shared.Severity
import controllima.shared.Vm
import controllima.shared.VmRef
import controllima.shared.VmSource
import controllima.shared.VmStatus
import controllima.shared.VmTable
import kotlinx.coroutines.launch

// ---------- Overview (docs/pages/overview.md) ----------

@Composable
fun OverviewPage(m: AppModel) {
    val list = m.vms
    val running = list?.vms.orEmpty().filter { it.status == VmStatus.RUNNING || it.status == VmStatus.UNRESPONSIVE }
    WatchStats(m, running.map { it.ref.targetId })
    val profiles = m.profiles()
    val containers = load(profiles, everyMillis = 15_000) { profiles.map { m.refreshContainers(it) } }
    val images = load(m.dockerProfiles().first()) { m.api.images(m.dockerProfiles().first()) }
    val disk = load(Unit, everyMillis = 60_000) { m.api.disk() }

    PageHeader(Page.Overview.title, m.vmsAt.takeIf { it > 0 }?.let { "Updated ${Format.clock(it)}" })
    if (m.firing.isNotEmpty()) {
        val critical = m.firing.count { it.severity == Severity.CRITICAL }
        Banner("${m.firing.size} alert(s) firing" + (if (critical > 0) ", $critical critical" else "") + ": " + m.firing.take(3).joinToString("; ") { it.message },
            if (critical > 0) BannerKind.ERROR else BannerKind.WARNING, "Open alerts" to { m.navigate(Screen.Main(Page.Alerts)) })
    }
    if (list == null) {
        if (m.vmsError != null) Banner(m.vmsError!!, action = Strings.RETRY to { m.scope.launch { m.refreshVms() } }) else Skeleton(6)
        return
    }
    list.errors.forEach { e -> Banner("${e.source}: ${e.message}", action = Strings.RETRY to { m.scope.launch { m.refreshVms() } }) }
    if (list.vms.isEmpty() && list.errors.isEmpty()) {
        EmptyState(Strings.NO_LIMA, Strings.INSTALL_HINT)
    }

    // Counters; each one opens the filtered list.
    val counts = VmTable.counts(list.vms)
    val allContainers = containers.value.orEmpty().flatMap { it.containers }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
        Counter("VMs running", counts.running) { m.navigate(Screen.Main(Page.Vms)) }
        Counter("VMs stopped", counts.stopped) { m.navigate(Screen.Main(Page.Vms)) }
        Counter("Unresponsive", counts.unresponsive, alarm = counts.unresponsive > 0) { m.navigate(Screen.Main(Page.Vms)) }
        Counter("Broken", counts.broken, alarm = counts.broken > 0) { m.navigate(Screen.Main(Page.Vms)) }
        Counter("Containers running", allContainers.count { it.running }) { m.navigate(Screen.Main(Page.Containers)) }
        Counter("Containers exited", allContainers.count { it.state == "exited" }) { m.navigate(Screen.Main(Page.Containers)) }
        images.value?.let { Counter("Images · ${Format.bytes(it.totalBytes, m.units)}", it.images.size) { m.navigate(Screen.Main(Page.Images)) } }
    }

    if (running.isNotEmpty()) {
        Text("Running VMs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            running.forEach { VmTile(m, it) }
        }
    }
    val stopped = list.vms.filter { VmTable.canStart(it) }
    if (stopped.isNotEmpty()) {
        Section("Stopped VMs") {
            stopped.forEach { vm ->
                TableRow(onClick = { m.navigate(Screen.VmDetail(vm.ref)) }) {
                    Cell(3f) { StatusBadge(vm.status) }
                    TextCell(4f, vm.name)
                    TextCell(2f, vm.source.slug)
                    // Start only on an explicit click; the app never starts a VM on its own.
                    Cell(2f) { SmallAction(Strings.START, enabled = m.can(Role.OPERATOR)) { m.runAction("Start ${vm.name}", { m.api.startVm(vm.ref).actionId }) } }
                }
            }
        }
    }

    Section("Host") {
        val d = disk.value
        if (d == null) {
            if (disk.error != null) Text(disk.error, color = MaterialTheme.colorScheme.error) else Skeleton(2)
        } else {
            val usedPercent = if (d.hostTotalBytes > 0) 100.0 * (d.hostTotalBytes - d.hostFreeBytes) / d.hostTotalBytes else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free disk on the Lima home volume: ${Format.bytes(d.hostFreeBytes, m.units)} of ${Format.bytes(d.hostTotalBytes, m.units)}", Modifier.padding(end = 12.dp))
                MiniBar(usedPercent)
            }
        }
        val given = running.sumOf { it.memoryBytes ?: 0 }
        Text("Memory given to running VMs: ${Format.bytes(given, m.units)}", Modifier.padding(top = 6.dp))
    }

    Section("Recent events") {
        if (m.events.isEmpty()) Text("No events since this page was opened.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.events.take(15).forEach { e ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(Format.clock(e.timeMillis), Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(eventLabel(e.type), Modifier.width(90.dp), style = MaterialTheme.typography.labelMedium)
                Text(e.message)
            }
        }
    }
}

private fun eventLabel(t: EventType) = when (t) {
    EventType.VM_STATE -> "VM"
    EventType.CONTAINER_STATE -> "Container"
    EventType.ACTION_PROGRESS, EventType.ACTION_DONE -> "Action"
    EventType.ALERT -> "Alert"
    EventType.INFO -> "Info"
}

@Composable
private fun Counter(label: String, value: Int, alarm: Boolean = false, onClick: () -> Unit) {
    Card(Modifier.width(170.dp).clickable(onClick = onClick), colors = if (alarm) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VmTile(m: AppModel, vm: Vm) {
    val target = vm.ref.targetId
    val unresponsive = vm.status == VmStatus.UNRESPONSIVE
    Card(
        Modifier.width(280.dp).clickable { m.navigate(Screen.VmDetail(vm.ref)) },
        colors = if (unresponsive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Tag(if (vm.source == VmSource.LIMA) "Lima" else "Colima")
            }
            StatusBadge(vm.status)
            if (unresponsive) {
                Text("Unresponsive since ${vm.unresponsiveSinceMillis?.let(Format::clock) ?: "?"}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { m.navigate(Screen.Logs(target)) }) { Text("Host agent log") }
            } else {
                val cpu = liveSeries(m, target, Metric.CPU)
                val mem = liveSeries(m, target, Metric.MEMORY_PERCENT)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU ${Format.percent(cpu.lastOrNull()?.avg)}", Modifier.width(110.dp), style = MaterialTheme.typography.labelSmall)
                    Sparkline(cpu.map { it.avg }, fixedMax = 100.0)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mem ${Format.percent(mem.lastOrNull()?.avg)}", Modifier.width(110.dp), style = MaterialTheme.typography.labelSmall)
                    Sparkline(mem.map { it.avg }, fixedMax = 100.0)
                }
            }
            Text(vm.ipAddresses.firstOrNull() ?: "", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- VMs (docs/pages/vms.md) ----------

@Composable
fun VmsPage(m: AppModel) {
    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<VmSource?>(null) }
    var status by remember { mutableStateOf<VmStatus?>(null) }
    var sort by remember { mutableStateOf(VmTable.SortKey.NAME) }
    var desc by remember { mutableStateOf(false) }
    val list = m.vms
    WatchStats(m, list?.vms.orEmpty().filter { it.status == VmStatus.RUNNING }.map { it.ref.targetId })

    PageHeader(Page.Vms.title, list?.let { "${it.vms.size} VMs" }) {
        SearchField(query, { query = it }, m)
        RoleButton(m, "New VM from template", Role.OPERATOR) { m.navigate(Screen.Main(Page.Templates)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        ChipRow(listOf<VmSource?>(null) + VmSource.entries, source, { it?.slug?.replaceFirstChar(Char::uppercase) ?: "All sources" }) { source = it }
        ChipRow(listOf<VmStatus?>(null) + VmStatus.entries, status, { it?.let(Format::status) ?: "Any status" }) { status = it }
    }
    if (list == null) {
        if (m.vmsError != null) Banner(m.vmsError!!, action = Strings.RETRY to { m.scope.launch { m.refreshVms() } }) else Skeleton(8)
        return
    }
    list.errors.forEach { e -> Banner("${e.source}: ${e.message}", action = Strings.RETRY to { m.scope.launch { m.refreshVms() } }) }
    if (m.vmsError != null) Banner("Showing the list from ${Format.clock(m.vmsAt)}: ${m.vmsError}", BannerKind.WARNING)
    if (list.vms.isEmpty()) {
        EmptyState(Strings.NO_VMS, Strings.NO_VMS_HINT, ("Create from template" to { m.navigate(Screen.Main(Page.Templates)) }).takeIf { m.can(Role.OPERATOR) })
        return
    }
    val rows = VmTable.sort(VmTable.filter(list.vms, query, source, status), sort, desc)
    val cols = listOf(
        Col("Status", 1.6f, VmTable.SortKey.STATUS), Col("Name", 2f, VmTable.SortKey.NAME), Col("Source", 1.1f, VmTable.SortKey.SOURCE),
        Col("Home / profile", 1.6f), Col("Arch · type", 1.4f), Col("CPUs", 0.7f, VmTable.SortKey.CPUS), Col("Memory", 1f, VmTable.SortKey.MEMORY),
        Col("Disk", 1f, VmTable.SortKey.DISK), Col("IPs · SSH", 1.6f), Col("CPU", 1.3f), Col("Mem", 1.3f), Col("", 4f),
    )
    TableHeader(cols, sort, desc) { key ->
        if (key == sort) desc = !desc else { sort = key as VmTable.SortKey; desc = false }
    }
    rows.forEach { vm ->
        val s = m.latest(vm.ref.targetId)
        TableRow(onClick = { m.navigate(Screen.VmDetail(vm.ref)) }) {
            Cell(1.6f) { StatusBadge(vm.status) }
            Cell(2f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.name)
                    if (isProtected(m, vm)) Tag(Strings.PROTECTED)
                }
            }
            TextCell(1.1f, vm.source.slug)
            TextCell(1.6f, if (vm.source == VmSource.COLIMA) "profile ${vm.name}" else vm.home ?: "default")
            TextCell(1.4f, listOfNotNull(vm.arch, vm.vmType).joinToString(" · ").ifEmpty { null })
            TextCell(0.7f, vm.cpus?.toString())
            TextCell(1f, Format.bytes(vm.memoryBytes, m.units))
            TextCell(1f, Format.bytes(vm.diskBytes, m.units) + (s?.diskPercent?.let { " · ${Format.percent(it)}" } ?: ""))
            TextCell(1.6f, (vm.ipAddresses.take(2) + listOfNotNull(vm.sshPort?.let { "ssh :$it" })).joinToString("\n").ifEmpty { null })
            Cell(1.3f) { MiniBar(s?.cpuPercent) }
            Cell(1.3f) { MiniBar(s?.memoryPercent) }
            Cell(4f) { VmActions(m, vm, compact = true) }
        }
    }
}

fun isProtected(m: AppModel, vm: Vm) = vm.protected || !Confirm.canDelete(vm.name, m.protectedNames)

/** Buttons for one VM with their dialogs; used by the list and the detail page. */
@Composable
fun VmActions(m: AppModel, vm: Vm, compact: Boolean, onDeleted: () -> Unit = {}) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val op = m.can(Role.OPERATOR)
    val admin = m.can(Role.ADMIN)
    val unresponsive = vm.status == VmStatus.UNRESPONSIVE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        if (VmTable.canStart(vm)) SmallAction(Strings.START, op) { m.runAction("Start ${vm.name}", { m.api.startVm(vm.ref).actionId }) }
        if (VmTable.canStop(vm)) SmallAction(Strings.STOP, op) { m.runAction("Stop ${vm.name}", { m.api.stopVm(vm.ref).actionId }) }
        if (VmTable.canForceStop(vm) && (unresponsive || !compact)) SmallAction(Strings.FORCE_STOP, admin, danger = true) { dialog = "force" }
        if (unresponsive) SmallAction(Strings.LOGS) { m.navigate(Screen.Logs(vm.ref.targetId)) }
        if (!compact || !unresponsive) {
            SmallAction(Strings.DELETE, admin && !isProtected(m, vm), danger = true) { dialog = "delete" }
            if (vm.source == VmSource.LIMA) {
                SmallAction(Strings.RENAME, admin && vm.status == VmStatus.STOPPED && !isProtected(m, vm)) { dialog = "rename" }
                SmallAction(Strings.CLONE, admin && vm.status == VmStatus.STOPPED) { dialog = "clone" }
            }
            SmallAction(Strings.BACK_UP, op) {
                m.launchCall("Backup created", { m.api.createBackup(CreateBackupRequest(listOf(vm.ref.targetId))) })
            }
        }
    }
    when (dialog) {
        "delete" -> ConfirmDialog(
            title = "Delete VM ${vm.name}?",
            name = vm.name,
            details = listOf(
                "Source: ${vm.source.slug}" + (vm.home?.let { ", home $it" } ?: ""),
                "Disk to be freed: up to ${Format.bytes(vm.diskBytes, m.units)}",
                "The VM, its disk and its data are deleted and cannot be restored except from a backup.",
            ),
            isProtected = isProtected(m, vm),
            warning = if (unresponsive) Strings.UNRESPONSIVE_DELETE else if (vm.status == VmStatus.RUNNING) "Stop the VM first; a running VM cannot be deleted." else null,
            onDismiss = { dialog = null },
        ) { typed ->
            val id = m.api.deleteVm(vm.ref, typed).actionId
            m.track("Delete ${vm.name}", id) { onDeleted() }
        }
        "force" -> ConfirmDialog(
            title = "Force stop ${vm.name}?",
            name = vm.name,
            details = listOf("Kills the VM like pulling the power cord; unsaved guest data can be lost."),
            confirmLabel = Strings.FORCE_STOP,
            onDismiss = { dialog = null },
        ) { _ ->
            val id = m.api.stopVm(vm.ref, force = true).actionId
            m.track("Force stop ${vm.name}", id)
        }
        "rename" -> RenameVmDialog(m, vm) { dialog = null }
        "clone" -> InputDialog(
            title = "Clone ${vm.name}",
            label = "New VM name",
            confirmLabel = Strings.CLONE,
            note = "Copies the stopped VM; the clone is created stopped.",
            validate = { if (Names.isValidVmName(it)) null else "Use letters, digits, '-' and '_' (max 64)" },
            onDismiss = { dialog = null },
        ) { name ->
            val id = m.api.cloneVm(vm.ref, name).actionId
            m.track("Clone ${vm.name} as $name", id)
        }
    }
}

/** Rename asks for the new name and the current name typed out (docs/pages/confirm-dialog.md). */
@Composable
private fun RenameVmDialog(m: AppModel, vm: Vm, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = Names.isValidVmName(newName) && newName != vm.name
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Rename ${vm.name}") },
        text = {
            Column {
                Text("Only stopped Lima VMs can be renamed. Scripts that use the old name will break.")
                OutlinedTextField(newName, { newName = it; error = null }, label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(Strings.format(Strings.TYPE_TO_CONFIRM, "“${vm.name}”"), Modifier.padding(top = 8.dp))
                OutlinedTextField(typed, { typed = it; error = null }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        val renamed = m.api.renameVm(vm.ref, newName, typed)
                        m.toast("Renamed to ${renamed.name}")
                        m.refreshVms()
                        onDismiss()
                        if (m.screen is Screen.VmDetail) m.navigate(Screen.VmDetail(renamed.ref))
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }, enabled = valid && Confirm.matches(typed, vm.name) && !busy) { Text(Strings.RENAME) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

// ---------- VM detail (docs/pages/vm-detail.md) ----------

enum class Range(val label: String, val millis: Long) {
    LIVE("15 m (live)", 15 * 60_000L), H1("1 h", 3_600_000L), H24("24 h", 86_400_000L), D7("7 d", 7 * 86_400_000L), D30("30 d", 30 * 86_400_000L),
}

@Composable
fun VmDetailPage(m: AppModel, ref: VmRef) {
    // Refreshed in place: a changing key would drop the loaded detail and, with it, the open tab's state
    // (an unsaved config edit, a terminal session).
    val detail = load(ref, everyMillis = 15_000) { m.api.vm(ref) }
    var tab by remember { mutableStateOf(0) }
    val d = detail.value
    if (d == null) {
        when {
            detail.status == 404 -> EmptyState(Strings.VM_NOT_FOUND, null, "Back to VMs" to { m.navigate(Screen.Main(Page.Vms)) })
            detail.error != null -> Banner(detail.error, action = Strings.RETRY to detail.reload)
            else -> Skeleton(8)
        }
        return
    }
    // The list refreshes more often than the detail; prefer its status.
    val vm = m.vms?.vms?.firstOrNull { it.ref == ref } ?: d.vm
    val target = vm.ref.targetId
    WatchStats(m, listOf(target))
    PageHeader(vm.name, "${vm.source.slug}" + (vm.home?.let { " · $it" } ?: "")) {
        StatusBadge(vm.status)
        if (isProtected(m, vm)) Tag(Strings.PROTECTED)
    }
    if (detail.error != null) Banner("Showing cached data: ${detail.error}", BannerKind.WARNING, Strings.RETRY to detail.reload)
    if (vm.status == VmStatus.UNRESPONSIVE) {
        Banner(Strings.UNRESPONSIVE_BANNER + (vm.unresponsiveSinceMillis?.let { " Since ${Format.timestamp(it)}." } ?: "") + (vm.message?.let { " $it" } ?: ""),
            action = "Host agent log" to { m.navigate(Screen.Logs("$target#ha.stderr")) })
    }
    VmActions(m, vm, compact = false) { m.navigate(Screen.Main(Page.Vms)) }

    val terminal = m.webTerminal && m.can(Role.ADMIN)
    Tabs(listOf("Summary", "Charts", "Config", "Logs", "Backups", "Alerts", "Snapshots") + if (terminal) listOf("Terminal") else emptyList(), tab) { tab = it }
    when (tab) {
        0 -> {
            Section("Summary") {
                Labeled("Arch", vm.arch)
                Labeled("VM type", vm.vmType)
                Labeled("CPUs", vm.cpus?.toString())
                Labeled("Memory", Format.bytes(vm.memoryBytes, m.units))
                Labeled("Disk", Format.bytes(vm.diskBytes, m.units))
                Labeled("Mounts", vm.mounts.joinToString(", ").ifEmpty { null })
                Labeled("Lima version", d.limaVersion)
                Labeled("Directory", vm.dir)
            }
            Section("Network", actions = { TextButton(onClick = { m.navigate(Screen.Main(Page.Ports)) }) { Text("Ports & Network") } }) {
                Labeled("IPs", (vm.ipAddresses + d.guestIps).distinct().joinToString(", ").ifEmpty { null })
                Labeled("SSH port", vm.sshPort?.toString())
                d.sshCommand?.let { cmd ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Labeled("SSH", cmd)
                        TextButton(onClick = { if (copyToClipboard(cmd)) m.toast("Copied") }) { Text(Strings.COPY) }
                    }
                }
                vm.portForwards.forEach { p -> Text("${p.hostIp}:${p.hostPort} to guest ${p.guestPort}/${p.proto}") }
            }
            if (d.containers.isNotEmpty()) {
                Section("Containers in this VM") {
                    d.containers.forEach { c ->
                        TableRow(onClick = { m.navigate(Screen.ContainerDetail(c.profile, c.id)) }) {
                            Cell(1f) { ContainerState(c.state) }
                            TextCell(2f, c.name)
                            TextCell(3f, c.image)
                            TextCell(2f, c.status)
                        }
                    }
                }
            }
        }
        1 -> MetricCharts(m, target, listOf(Metric.CPU, Metric.MEMORY_PERCENT, Metric.NET_RX, Metric.NET_TX, Metric.DISK_READ, Metric.DISK_WRITE, Metric.DISK_PERCENT, Metric.LOAD, Metric.PROBE_LATENCY))
        2 -> VmConfigTab(m, vm, d.config)
        3 -> LogsPage(m, target, embedded = true)
        4 -> VmBackups(m, target)
        5 -> VmAlerts(m, target)
        6 -> VmSnapshotsTab(m, vm)
        7 -> if (terminal) VmTerminalTab(m, vm)
    }
}

/** Live and historical charts for one target, with a range picker and CSV export. */
@Composable
fun MetricCharts(m: AppModel, target: String, metrics: List<String>) {
    var range by remember { mutableStateOf(Range.LIVE) }
    var asTable by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        ChipRow(Range.entries, range, { it.label }) { range = it }
        TextButton(onClick = { asTable = !asTable }) { Text(if (asTable) Strings.CHART_VIEW else Strings.TABLE_VIEW) }
    }
    val now = Clock.now()
    val history = if (range == Range.LIVE) null else load(target, range, now / 60_000) {
        metrics.map { metric -> m.api.metrics(target, metric, now - range.millis, now) }
    }
    if (history?.error != null) Banner(history.error, action = Strings.RETRY to history.reload)
    val series: List<MetricSeries> = if (range == Range.LIVE) {
        metrics.map { MetricSeries(target, it, 2_000, liveSeries(m, target, it).filter { p -> p.t >= now - range.millis }) }
    } else history?.value.orEmpty()
    if (range != Range.LIVE && history?.value == null) {
        Skeleton(4)
        return
    }
    if (series.all { it.points.isEmpty() }) {
        EmptyState(Strings.COLLECTING)
        return
    }
    TextButton(onClick = { saveText(controllima.shared.ChartMath.csv(series), "${target.replace('/', '_')}.csv", "text/csv") }) { Text(Strings.EXPORT_CSV) }
    series.filter { it.points.isNotEmpty() }.forEach { s ->
        Section(metricTitle(s.metric)) {
            if (asTable) {
                s.points.takeLast(60).reversed().forEach { p ->
                    Row { Text(Format.timestamp(p.t), Modifier.width(180.dp)); Text(Format.metric(s.metric, p.avg, m.units)); Text("  max ${Format.metric(s.metric, p.max, m.units)}") }
                }
            } else {
                LineChart(listOf(ChartLine(Targets_label(target), s.points, s.stepMillis)), now - range.millis, now, s.metric, m.units, s.gaps)
            }
        }
    }
}

@Suppress("FunctionName")
fun Targets_label(target: String) = controllima.shared.Targets.label(target)

fun metricTitle(metric: String) = when (metric) {
    Metric.CPU -> "CPU"
    Metric.MEMORY -> "Memory used"
    Metric.MEMORY_PERCENT -> "Memory"
    Metric.NET_RX -> "Network in"
    Metric.NET_TX -> "Network out"
    Metric.DISK_READ -> "Disk read"
    Metric.DISK_WRITE -> "Disk write"
    Metric.DISK_USED -> "Disk used"
    Metric.DISK_PERCENT -> "Disk usage"
    Metric.DISK_TOTAL -> "Disk size"
    Metric.LOAD -> "Load (1 min)"
    Metric.PROBE_LATENCY -> "Probe latency"
    Metric.HOST_CPU -> "Host CPU of the VM process"
    Metric.HOST_RSS -> "Host memory of the VM process"
    else -> metric
}

@Composable
private fun VmBackups(m: AppModel, target: String) {
    val backups = load(target) { m.api.backups().filter { b -> b.items.any { it.target == target } } }
    Section("Backups of this VM", actions = {
        RoleButton(m, "Back up now", Role.OPERATOR) { m.launchCall("Backup created", { m.api.createBackup(CreateBackupRequest(listOf(target))) }) { backups.reload() } }
    }) {
        val list = backups.value
        when {
            list == null -> if (backups.error != null) Text(backups.error) else Skeleton(2)
            list.isEmpty() -> Text(Strings.NO_BACKUPS)
            else -> list.forEach { b -> Text("${Format.timestamp(b.createdMillis)} · ${b.id} · ${Format.bytes(b.sizeBytes, m.units)}") }
        }
        TextButton(onClick = { m.navigate(Screen.Main(Page.Backups)) }) { Text("Restore from Backups") }
    }
}

@Composable
private fun VmAlerts(m: AppModel, target: String) {
    val events = load(target, everyMillis = 30_000) { m.api.alertEvents().filter { it.target == target } }
    Section("Alerts for this VM") {
        val list = events.value
        when {
            list == null -> Skeleton(2)
            list.isEmpty() -> Text(Strings.NO_ALERTS)
            else -> list.take(50).forEach { e ->
                Text("${Format.timestamp(e.startedMillis)} · ${e.severity.name.lowercase()} · ${e.state.name.lowercase()} · ${e.message}",
                    color = if (e.state == AlertState.FIRING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
