package controllima.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import controllima.shared.ApiException
import controllima.shared.Container
import controllima.shared.ContainerList
import controllima.shared.Format
import controllima.shared.Image
import controllima.shared.Metric
import controllima.shared.Names
import controllima.shared.PruneResult
import controllima.shared.Role
import controllima.shared.VmRef
import controllima.shared.VmSource
import controllima.shared.VmTable

@Composable
private fun ProfilePicker(m: AppModel, profile: String, profiles: List<String> = m.profiles(), onChange: (String) -> Unit) {
    if (profiles.size > 1) Picker("Profile", profiles, profile, { it }, onChange)
}

/** Colima stopped: the socket is missing. The user may start it here, explicitly. */
@Composable
private fun ColimaStopped(m: AppModel, profile: String, message: String?) {
    val vm = m.vms?.vms?.firstOrNull { it.source == VmSource.COLIMA && it.name == profile }
    Banner(
        Strings.format(Strings.COLIMA_STOPPED, profile) + (message?.let { ". $it" } ?: ""),
        BannerKind.WARNING,
        if (vm != null && VmTable.canStart(vm) && m.can(Role.OPERATOR)) "Start Colima $profile" to {
            m.runAction("Start Colima $profile", { m.api.startVm(VmRef(VmSource.COLIMA, profile)).actionId })
        } else null,
    )
}

// ---------- Containers (docs/pages/containers.md) ----------

@Composable
fun ContainersPage(m: AppModel) {
    var profile by remember { mutableStateOf(m.profiles().first()) }
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("all") }
    val data = load(profile, everyMillis = 10_000) { m.refreshContainers(profile) }
    // A failed refresh keeps the last list on screen, greyed (unresponsive state).
    val list: ContainerList? = data.value ?: m.containerLists[profile]
    WatchStats(m, list?.containers.orEmpty().filter { it.running }.map { it.targetId })

    PageHeader(Page.Containers.title, list?.let { "${it.containers.size} containers in $profile" }) {
        ProfilePicker(m, profile) { profile = it }
        SearchField(query, { query = it }, m, "Name or image")
    }
    ChipRow(listOf("all", "running", "exited", "other"), state, { it.replaceFirstChar(Char::uppercase) }) { state = it }
    if (list == null) {
        if (data.error != null) Banner(data.error, action = Strings.RETRY to data.reload) else Skeleton(6)
        return
    }
    if (list.unavailable) {
        if (list.error?.contains("socket", ignoreCase = true) == true) ColimaStopped(m, profile, null)
        else Banner(list.error ?: "Docker API failed", action = Strings.RETRY to data.reload)
        if (list.containers.isEmpty()) return
    }
    if (data.error != null) Banner("Docker API did not answer; showing the last known list. ${data.error}", BannerKind.WARNING)
    val rows = list.containers.filter { c ->
        (query.isBlank() || c.name.contains(query, true) || c.image.contains(query, true)) &&
            when (state) {
                "running" -> c.running
                "exited" -> c.state == "exited"
                "other" -> !c.running && c.state != "exited"
                else -> true
            }
    }
    if (rows.isEmpty()) {
        EmptyState(Strings.format(Strings.NO_CONTAINERS, profile))
        return
    }
    TableHeader(listOf(Col("State", 1.2f), Col("Name", 2.2f), Col("Image", 2.6f), Col("Status", 1.8f), Col("Ports", 2f), Col("IPs", 1.4f), Col("Created", 1.4f), Col("CPU", 1.3f), Col("Mem", 1.3f), Col("", 3.5f)))
    val dim = data.error != null || list.unavailable
    rows.forEach { c ->
        val s = m.latest(c.targetId)
        TableRow(onClick = { m.navigate(Screen.ContainerDetail(c.profile, c.id)) }, dim = dim) {
            Cell(1.2f) { ContainerState(c.state) }
            TextCell(2.2f, c.name)
            TextCell(2.6f, c.image, mono = true)
            TextCell(1.8f, c.status)
            Cell(2f) { PortLinks(c) }
            TextCell(1.4f, c.ipAddresses.joinToString("\n").ifEmpty { null })
            TextCell(1.4f, c.createdEpochSeconds?.let { Format.timestamp(it * 1000).take(10) })
            Cell(1.3f) { MiniBar(s?.cpuPercent) }
            Cell(1.3f) { MiniBar(s?.memoryPercent) }
            Cell(3.5f) { ContainerActions(m, c, compact = true) { data.reload() } }
        }
    }
}

@Composable
private fun PortLinks(c: Container) {
    Column {
        c.ports.filter { it.publicPort != null }.forEach { p ->
            val host = p.ip?.takeIf { it != "0.0.0.0" && it != "::" } ?: "127.0.0.1"
            Text(
                "${p.publicPort}:${p.privatePort}/${p.type}",
                if (p.type == "tcp") Modifier.padding(vertical = 1.dp).clickable { openExternal("http://$host:${p.publicPort}") } else Modifier.padding(vertical = 1.dp),
                color = if (p.type == "tcp") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun ContainerActions(m: AppModel, c: Container, compact: Boolean, onChanged: () -> Unit) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val op = m.can(Role.OPERATOR)
    fun act(action: String, label: String) = m.launchCall("$label ${c.name}: requested", { m.api.containerAction(c.id, c.profile, action) }) { onChanged() }
    FlowRow {
        if (!c.running) SmallAction(Strings.START, op) { act("start", "Start") }
        if (c.running) SmallAction(Strings.STOP, op) { act("stop", "Stop") }
        SmallAction(Strings.RESTART, op) { act("restart", "Restart") }
        if (!compact) SmallAction(Strings.RENAME, op) { dialog = "rename" }
        SmallAction(Strings.LOGS) { m.navigate(Screen.Logs("${c.targetId}#docker")) }
        SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { dialog = "delete" }
    }
    when (dialog) {
        "rename" -> InputDialog(
            "Rename ${c.name}", "New name", c.name, Strings.RENAME,
            validate = { if (Names.isValidContainerName(it)) null else "Letters, digits, '_', '.', '-'; must start with a letter or digit" },
            onDismiss = { dialog = null },
        ) { name ->
            m.api.renameContainer(c.id, c.profile, name)
            m.toast("Renamed to $name")
            onChanged()
        }
        "delete" -> ConfirmDialog(
            "Delete container ${c.name}?", c.name,
            listOf("Image ${c.image}", "State: ${c.state}" + if (c.running) " (it will be stopped and removed)" else "", "Its writable layer is lost; volumes are kept."),
            onDismiss = { dialog = null },
        ) { typed ->
            m.api.deleteContainer(c.id, c.profile, typed)
            m.toast("Deleted ${c.name}")
            if (m.screen is Screen.ContainerDetail) m.navigate(Screen.Main(Page.Containers)) else onChanged()
        }
    }
}

// ---------- Container detail (docs/pages/container-detail.md) ----------

@Composable
fun ContainerDetailPage(m: AppModel, profile: String, id: String) {
    val detail = load(profile, id, everyMillis = 10_000) { m.api.container(id, profile) }
    val revealed = remember(id) { mutableStateMapOf<String, String>() }
    var tab by remember { mutableStateOf(0) }
    val d = detail.value
    if (d == null) {
        when {
            detail.status == 404 -> EmptyState(Strings.CONTAINER_NOT_FOUND, null, "Back to containers" to { m.navigate(Screen.Main(Page.Containers)) })
            detail.error != null -> Banner(detail.error, action = Strings.RETRY to detail.reload)
            else -> Skeleton(8)
        }
        return
    }
    val c = d.container
    if (c.running) WatchStats(m, listOf(c.targetId))
    PageHeader(c.name, "Colima profile ${c.profile} · ${c.id.take(12)}") { ContainerState(c.state) }
    if (detail.error != null) Banner("Docker API did not answer; live charts paused. ${detail.error}", BannerKind.WARNING, Strings.RETRY to detail.reload)
    ContainerActions(m, c, compact = false) { detail.reload() }
    Tabs(listOf("Summary", "Charts", "Logs"), tab) { tab = it }
    when (tab) {
        0 -> {
            Section("Config") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Labeled("Image", c.image)
                    c.imageId?.let { TextButton(onClick = { m.navigate(Screen.Main(Page.Images)) }) { Text("Images") } }
                }
                Labeled("Command", d.command.joinToString(" ").ifEmpty { null })
                Labeled("Status", c.status)
                Labeled("Started", d.startedAt)
                Labeled("Exit code", d.exitCode?.toString())
                Labeled("Restart policy", d.restartPolicy)
                Labeled("Restart count", d.restartCount.toString())
            }
            Section("Ports and networks") {
                PortLinks(c)
                d.networks.forEach { (net, ip) -> Labeled(net, ip) }
            }
            if (d.mounts.isNotEmpty()) Section("Mounts") { d.mounts.forEach { Mono("${it.source} : ${it.destination}" + if (it.readWrite) "" else " (ro)") } }
            Section("Environment") {
                // Values are masked by the server; an admin can reveal one, and that is audited.
                d.env.forEach { entry ->
                    val key = entry.substringBefore('=')
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mono(revealed[key]?.let { "$key=$it" } ?: entry, Modifier.weight(1f))
                        if (revealed[key] == null && entry.endsWith("•••") && m.can(Role.ADMIN)) {
                            TextButton(onClick = {
                                m.launchCall(null, { m.api.revealEnv(c.id, c.profile, key) }) { revealed[key] = it.value }
                            }) { Text("Reveal") }
                        }
                    }
                }
                if (d.env.isEmpty()) Text("No environment variables.")
            }
            if (c.labels.isNotEmpty()) Section("Labels") { c.labels.forEach { (k, v) -> Mono("$k=$v") } }
        }
        1 -> if (c.running) MetricCharts(m, c.targetId, listOf(Metric.CPU, Metric.MEMORY_PERCENT, Metric.NET_RX, Metric.NET_TX, Metric.DISK_READ, Metric.DISK_WRITE))
        else EmptyState("The container is not running; history is on the Metrics page.")
        2 -> LogsPage(m, "${c.targetId}#docker", embedded = true)
    }
}

// ---------- Images (docs/pages/images.md) ----------

@Composable
fun ImagesPage(m: AppModel) {
    var profile by remember { mutableStateOf(m.dockerProfiles().first()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<Image?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var prunePreview by remember { mutableStateOf<PruneResult?>(null) }
    val data = load(profile, everyMillis = 30_000) { m.api.images(profile) }
    PageHeader(Page.Images.title, data.value?.let { "${it.images.size} images · ${Format.bytes(it.totalBytes, m.units)} · reclaimable ${Format.bytes(it.reclaimableBytes, m.units)}" }) {
        ProfilePicker(m, profile, m.dockerProfiles()) { profile = it }
        RoleButton(m, "Pull", Role.OPERATOR, primary = true) { dialog = "pull" }
        RoleButton(m, "Prune…", Role.ADMIN) {
            m.launchCall(null, { m.api.pruneImages(profile, dryRun = true) }) { prunePreview = it; dialog = "prune" }
        }
    }
    val list = data.value
    if (list == null) {
        if (data.error != null) Banner(data.error, action = Strings.RETRY to data.reload) else Skeleton(6)
        return
    }
    if (list.error != null) {
        if (list.error!!.contains("socket", true)) ColimaStopped(m, profile, null) else Banner(list.error!!, action = Strings.RETRY to data.reload)
    }
    if (data.error != null) Banner("Docker API did not answer: ${data.error}", BannerKind.WARNING)
    if (list.images.isEmpty()) {
        if (list.error == null) EmptyState(Strings.NO_IMAGES)
    } else {
        TableHeader(listOf(Col("Repository:tag", 3f), Col("ID", 1.4f), Col("Size", 1f), Col("Created", 1.3f), Col("Used by", 2f), Col("", 2f)))
        list.images.sortedByDescending { it.sizeBytes }.forEach { img ->
            TableRow(onClick = { expanded = if (expanded == img.id) null else img.id }) {
                TextCell(3f, if (img.dangling) "<none> (dangling)" else img.tags.joinToString("\n"), mono = true)
                TextCell(1.4f, shortId(img.id), mono = true)
                TextCell(1f, Format.bytes(img.sizeBytes, m.units))
                TextCell(1.3f, img.createdEpochSeconds?.let { Format.timestamp(it * 1000).take(10) })
                TextCell(2f, img.usedBy.joinToString(", ").ifEmpty { if ((img.containers ?: 0) > 0) "${img.containers} containers" else "—" })
                Cell(2f) {
                    // In-use images are refused by the server too; the button says why up front.
                    SmallAction(if (img.usedBy.isEmpty()) Strings.DELETE else "In use", m.can(Role.ADMIN) && img.usedBy.isEmpty(), danger = true) { target = img; dialog = "delete" }
                }
            }
            if (expanded == img.id) ImageLayers(m, img)
        }
    }
    when (dialog) {
        "pull" -> InputDialog(
            "Pull image", "Image reference (e.g. nginx:1.29)", confirmLabel = "Pull",
            validate = { if (Names.isValidImageRef(it)) null else "Not a valid image reference" },
            onDismiss = { dialog = null },
        ) { ref -> m.track("Pull $ref", m.api.pullImage(profile, ref).actionId) { data.reload() } }
        "delete" -> target?.let { img ->
            val name = img.tags.firstOrNull() ?: shortId(img.id)
            ConfirmDialog("Delete image $name?", name, listOf("Frees up to ${Format.bytes(img.sizeBytes, m.units)}", "Tags: ${img.tags.joinToString().ifEmpty { "none" }}"), onDismiss = { dialog = null }) { typed ->
                m.api.deleteImage(img.id, profile, typed)
                m.toast("Deleted $name")
                data.reload()
            }
        }
        "prune" -> prunePreview?.let { p ->
            ConfirmDialog(
                "Prune unused images in $profile?", "prune",
                listOf("${p.images.size} dangling images, ${Format.bytes(p.bytes, m.units)}") + p.images.take(10),
                confirmLabel = "Prune",
                onDismiss = { dialog = null },
            ) { typed ->
                val r = m.api.pruneImages(profile, dryRun = false, confirmName = typed)
                m.toast("Freed ${Format.bytes(r.bytes, m.units)}")
                data.reload()
            }
        }
    }
}

fun shortId(id: String) = id.removePrefix("sha256:").take(12)

@Composable
private fun ImageLayers(m: AppModel, img: Image) {
    val detail = load(img.id) { m.api.image(img.id, img.profile) }
    Column(Modifier.padding(start = 24.dp, bottom = 12.dp)) {
        val d = detail.value
        when {
            d == null -> if (detail.error != null) Text(detail.error) else Skeleton(2)
            else -> {
                Text("${d.os ?: "?"}/${d.architecture ?: "?"} · ${d.layers.size} layers", style = MaterialTheme.typography.labelMedium)
                d.layers.forEach { l ->
                    Row {
                        Text(Format.bytes(l.sizeBytes, m.units), Modifier.width(90.dp))
                        Mono(l.createdBy.take(160))
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun ApiException.short() = message ?: "HTTP $status"
