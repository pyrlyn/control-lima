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
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import controllima.shared.AboutInfo
import controllima.shared.ApiException
import controllima.shared.AuditEntry
import controllima.shared.CleanupRequest
import controllima.shared.CleanupResult
import controllima.shared.CleanupSuggestion
import controllima.shared.CreateTokenRequest
import controllima.shared.CreateUserRequest
import controllima.shared.CreatedToken
import controllima.shared.Format
import controllima.shared.Role
import controllima.shared.Settings
import controllima.shared.SizeUnits
import controllima.shared.Theme
import kotlinx.coroutines.launch

// ---------- Settings & Access (docs/pages/settings-access.md) ----------

@Composable
fun SettingsPage(m: AppModel) {
    var tab by remember { mutableStateOf(0) }
    PageHeader(Page.Settings.title)
    Tabs(listOf("General", "Protected names", "Metrics & disk", "Access", "Audit log", "About"), tab) { tab = it }
    val data = load(Unit) { m.api.settings() }
    val response = data.value
    when (tab) {
        3 -> AccessTab(m)
        4 -> AuditTab(m)
        else -> {
            if (response == null) {
                if (data.error != null) Banner(data.error, action = Strings.RETRY to data.reload) else Skeleton(5)
                return
            }
            val save: (Settings) -> Unit = { s ->
                m.launchCall("Settings saved", { m.api.saveSettings(s) }) { data.reload(); m.applySettings(s) }
            }
            when (tab) {
                0 -> GeneralTab(m, response.settings, save)
                1 -> ProtectedTab(m, response.settings, save)
                2 -> MetricsDiskTab(m, response.settings, save)
                5 -> AboutTab(m, response.about, response.settings, save)
            }
        }
    }
}

@Composable
private fun GeneralTab(m: AppModel, s: Settings, save: (Settings) -> Unit) {
    val admin = m.can(Role.ADMIN)
    var homes by remember(s) { mutableStateOf(s.limaHomes.joinToString("\n")) }
    var profiles by remember(s) { mutableStateOf(s.colimaProfiles.joinToString("\n")) }
    var defaultProfile by remember(s) { mutableStateOf(s.defaultProfile) }
    var limactl by remember(s) { mutableStateOf(s.limactlPath) }
    var colima by remember(s) { mutableStateOf(s.colimaPath) }
    var limaArgs by remember(s) { mutableStateOf(s.limaArgs.joinToString(" ")) }
    var colimaArgs by remember(s) { mutableStateOf(s.colimaArgs.joinToString(" ")) }
    var live by remember(s) { mutableStateOf(s.liveIntervalSeconds.toString()) }
    var background by remember(s) { mutableStateOf(s.backgroundIntervalSeconds.toString()) }
    var probe by remember(s) { mutableStateOf(s.probeTimeoutSeconds.toString()) }
    var theme by remember(s) { mutableStateOf(s.theme) }
    var units by remember(s) { mutableStateOf(s.units) }
    var compact by remember(s) { mutableStateOf(s.compact) }
    var multiUser by remember(s) { mutableStateOf(s.multiUser) }
    var webTerminal by remember(s) { mutableStateOf(s.webTerminal) }
    val ints = listOf(live, background, probe).map { it.toIntOrNull() }
    val intsOk = ints.all { it != null && it in 1..3600 }
    val homesOk = lines(homes).all { it.startsWith("/") }
    Section("Sources") {
        Field("Lima homes (absolute paths, one per line; empty means ~/.lima)", homes, admin, error = if (homesOk) null else "Paths must be absolute", singleLine = false) { homes = it }
        Field("Colima profiles (one per line; empty means auto-detect)", profiles, admin, singleLine = false) { profiles = it }
        Field("Default Colima profile", defaultProfile, admin) { defaultProfile = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("limactl", limactl, admin, Modifier.weight(1f)) { limactl = it }
            Field("colima", colima, admin, Modifier.weight(1f)) { colima = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Extra limactl arguments", limaArgs, admin, Modifier.weight(1f)) { limaArgs = it }
            Field("Extra colima arguments", colimaArgs, admin, Modifier.weight(1f)) { colimaArgs = it }
        }
    }
    Section("Polling") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Live interval, s", live, admin, Modifier.width(160.dp)) { live = it }
            Field("Background interval, s", background, admin, Modifier.width(180.dp)) { background = it }
            Field("Probe timeout, s", probe, admin, Modifier.width(160.dp)) { probe = it }
        }
        if (!intsOk) Text("Intervals are whole seconds from 1 to 3600.", color = MaterialTheme.colorScheme.error)
    }
    Section("Display and access") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker("Theme", Theme.entries, theme, { it.name.lowercase() }) { theme = it }
            Picker("Sizes", SizeUnits.entries, units, { if (it == SizeUnits.BINARY) "GiB (1024)" else "GB (1000)" }) { units = it }
            Toggle("Compact tables", compact, admin) { compact = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Toggle("Multi-user sign-in", multiUser, admin) { multiUser = it }
            Toggle("Web terminal for admins", webTerminal, admin) { webTerminal = it }
        }
        if (webTerminal) Text("Admins can open a shell in any running VM from the browser. Sessions are audited.", style = MaterialTheme.typography.bodySmall)
    }
    RoleButton(m, Strings.SAVE, Role.ADMIN, enabled = intsOk && homesOk) {
        save(
            s.copy(
                limaHomes = lines(homes), colimaProfiles = lines(profiles), defaultProfile = defaultProfile.trim().ifEmpty { "default" },
                limactlPath = limactl.trim(), colimaPath = colima.trim(), limaArgs = words(limaArgs), colimaArgs = words(colimaArgs),
                liveIntervalSeconds = ints[0]!!, backgroundIntervalSeconds = ints[1]!!, probeTimeoutSeconds = ints[2]!!,
                theme = theme, units = units, compact = compact, multiUser = multiUser,
                webTerminal = webTerminal,
            ),
        )
    }
}

@Composable
private fun ProtectedTab(m: AppModel, s: Settings, save: (Settings) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    Text(Strings.PROTECTED_HINT, Modifier.padding(bottom = 8.dp))
    Text("Names may end in * to match a prefix (tc-*). Changes are audited.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.protectedNames.forEach { n ->
            InputChip(selected = false, enabled = m.can(Role.ADMIN), onClick = { removing = n }, label = { Text("$n  ×") })
        }
    }
    RoleButton(m, "Add name", Role.ADMIN) { adding = true }
    if (adding) {
        InputDialog("Protect a name", "VM or container name", confirmLabel = "Protect",
            validate = { if (it.isBlank() || it.any { c -> c.isWhitespace() }) "No spaces" else if (it in s.protectedNames) "Already protected" else null },
            onDismiss = { adding = false }) { name ->
            m.api.saveSettings(s.copy(protectedNames = s.protectedNames + name))
            m.refreshSession()
        }
    }
    removing?.let { n ->
        // Removing protection is what makes deletion possible, so it gets the same typed confirmation.
        ConfirmDialog("Stop protecting $n?", n, listOf("After this, $n can be deleted with a typed confirmation."), confirmLabel = "Remove protection", onDismiss = { removing = null }) {
            m.api.saveSettings(s.copy(protectedNames = s.protectedNames - n))
            m.refreshSession()
        }
    }
}

@Composable
private fun MetricsDiskTab(m: AppModel, s: Settings, save: (Settings) -> Unit) {
    val admin = m.can(Role.ADMIN)
    var raw by remember(s) { mutableStateOf(s.retentionRawHours.toString()) }
    var m1 by remember(s) { mutableStateOf(s.retention1mDays.toString()) }
    var m15 by remember(s) { mutableStateOf(s.retention15mDays.toString()) }
    var h1 by remember(s) { mutableStateOf(s.retention1hDays.toString()) }
    val disk = load(Unit) { m.api.disk() }
    val values = listOf(raw, m1, m15, h1).map { it.toIntOrNull() }
    val ok = values.all { it != null && it > 0 }
    Section("Metrics retention", actions = { disk.value?.let { Text("Store ${Format.bytes(it.metricsBytes, m.units)}") } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Raw (2 s), hours", raw, admin, Modifier.width(150.dp)) { raw = it }
            Field("1-minute, days", m1, admin, Modifier.width(150.dp)) { m1 = it }
            Field("15-minute, days", m15, admin, Modifier.width(150.dp)) { m15 = it }
            Field("1-hour, days", h1, admin, Modifier.width(150.dp)) { h1 = it }
        }
        RoleButton(m, Strings.SAVE, Role.ADMIN, enabled = ok) {
            save(s.copy(retentionRawHours = values[0]!!, retention1mDays = values[1]!!, retention15mDays = values[2]!!, retention1hDays = values[3]!!))
        }
    }
    Section("Disk") {
        val d = disk.value
        if (d == null) {
            if (disk.error != null) Banner(disk.error, action = Strings.RETRY to disk.reload) else Skeleton(3)
            return@Section
        }
        Labeled("Host free", "${Format.bytes(d.hostFreeBytes, m.units)} of ${Format.bytes(d.hostTotalBytes, m.units)}")
        Labeled("Backups", Format.bytes(d.backupsBytes, m.units))
        d.vms.forEach { v -> Labeled(v.name, "${Format.bytes(v.allocatedBytes, m.units)} on disk (max ${Format.bytes(v.apparentBytes, m.units)})") }
        d.docker.forEach { k -> Labeled("Docker ${k.profile}", "images ${Format.bytes(k.imagesBytes, m.units)}, reclaimable ${Format.bytes(k.reclaimableBytes, m.units)}") }
    }
    disk.value?.suggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
        Section("Cleanup suggestions") { suggestions.forEach { CleanupRow(m, it) { disk.reload() } } }
    }
}

@Composable
private fun CleanupRow(m: AppModel, c: CleanupSuggestion, onDone: () -> Unit) {
    var preview by remember { mutableStateOf<CleanupResult?>(null) }
    var confirming by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.title + (c.bytes?.let { " · ${Format.bytes(it, m.units)}" } ?: ""))
            c.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (c.confirm != null) {
            RoleButton(m, "Preview", Role.ADMIN) {
                m.launchCall(null, { m.api.cleanup(CleanupRequest(c.id, ""), dryRun = true) }) { preview = it; confirming = true }
            }
        }
    }
    val confirmName = c.confirm
    if (confirming && confirmName != null) {
        val p = preview
        ConfirmDialog(
            c.title,
            confirmName,
            (p?.items ?: c.items).take(20) + listOfNotNull(p?.let { "Frees about ${Format.bytes(it.bytes, m.units)}" }),
            confirmLabel = "Clean up",
            onDismiss = { confirming = false },
        ) { typed ->
            val r = m.api.cleanup(CleanupRequest(c.id, typed), dryRun = false)
            m.toast("Freed ${Format.bytes(r.bytes, m.units)}")
            onDone()
        }
    }
}

@Composable
private fun AccessTab(m: AppModel) {
    if (!m.can(Role.ADMIN)) {
        Text(Strings.NEEDS_ADMIN)
        return
    }
    val tokens = load(Unit) { m.api.tokens() }
    val users = load(Unit) { m.api.users() }
    var creatingToken by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<CreatedToken?>(null) }
    var revoking by remember { mutableStateOf<String?>(null) }
    var creatingUser by remember { mutableStateOf(false) }
    var deletingUser by remember { mutableStateOf<String?>(null) }
    Section("API tokens", actions = { TextButton(onClick = { creatingToken = true }) { Text("New token") } }) {
        val list = tokens.value
        when {
            list == null -> if (tokens.error != null) Banner(tokens.error, action = Strings.RETRY to tokens.reload) else Skeleton(2)
            list.isEmpty() -> Text("No tokens. The launch link token always works for this session.")
            else -> {
                TableHeader(listOf(Col("Name", 2f), Col("Role", 1f), Col("Created", 1.6f), Col("Expires", 1.6f), Col("Last used", 1.6f), Col("", 1f)))
                list.forEach { t ->
                    TableRow {
                        TextCell(2f, t.name)
                        TextCell(1f, t.role.name.lowercase())
                        TextCell(1.6f, Format.timestamp(t.createdMillis))
                        TextCell(1.6f, t.expiresMillis?.let { Format.timestamp(it) } ?: "never")
                        TextCell(1.6f, t.lastUsedMillis?.let { Format.timestamp(it) } ?: "never")
                        Cell(1f) { SmallAction("Revoke", danger = true) { revoking = t.id } }
                    }
                }
            }
        }
    }
    Section("Users", actions = { TextButton(onClick = { creatingUser = true }) { Text("New user") } }) {
        Text("Used only when multi-user sign-in is on (General).", style = MaterialTheme.typography.bodySmall)
        users.value?.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.username, Modifier.width(200.dp))
                Text(u.role.name.lowercase(), Modifier.width(120.dp))
                Text(Format.timestamp(u.createdMillis), Modifier.width(180.dp))
                SmallAction(Strings.DELETE, enabled = u.username != m.session?.actor, danger = true) { deletingUser = u.username }
            }
        }
        users.error?.let { Banner(it) }
    }
    if (creatingToken) TokenDialog(m, onDismiss = { creatingToken = false }) { created = it; tokens.reload() }
    created?.let { c ->
        InfoDialog("Token ${c.info.name}", { created = null }) {
            Banner(Strings.TOKEN_ONCE, BannerKind.WARNING)
            CodeBlock(c.token)
            TextButton(onClick = { if (copyToClipboard(c.token)) m.toast("Copied") }) { Text(Strings.COPY) }
        }
    }
    revoking?.let { id ->
        val name = tokens.value?.firstOrNull { it.id == id }?.name ?: id
        ConfirmDialog("Revoke token $name?", name, listOf("Clients using it stop working at once."), confirmLabel = "Revoke", onDismiss = { revoking = null }) {
            m.api.revokeToken(id)
            tokens.reload()
        }
    }
    if (creatingUser) UserDialog(m, onDismiss = { creatingUser = false }) { users.reload() }
    deletingUser?.let { u ->
        ConfirmDialog("Delete user $u?", u, listOf("Their sessions end."), onDismiss = { deletingUser = null }) {
            m.api.deleteUser(u)
            users.reload()
        }
    }
}

@Composable
private fun TokenDialog(m: AppModel, onDismiss: () -> Unit, onCreated: (CreatedToken) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.VIEWER) }
    var days by remember { mutableStateOf("90") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val d = days.takeIf { it.isNotBlank() }?.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New API token") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Picker("Role", Role.entries, role, { it.name.lowercase() }) { role = it }
                    OutlinedTextField(days, { days = it }, label = { Text("Expires in days (empty: never)") }, singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && (days.isBlank() || (d != null && d > 0)), onClick = {
                scope.launch {
                    try {
                        onCreated(m.api.createToken(CreateTokenRequest(name.trim(), role, d)))
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
private fun UserDialog(m: AppModel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.VIEWER) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New user") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(Strings.USERNAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(Strings.PASSWORD + " (12+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Picker("Role", Role.entries, role, { it.name.lowercase() }) { role = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && password.length >= 12, onClick = {
                scope.launch {
                    try {
                        m.api.createUser(CreateUserRequest(name.trim(), password, role))
                        onCreated()
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
private fun AuditTab(m: AppModel) {
    if (!m.can(Role.ADMIN)) {
        Text(Strings.NEEDS_ADMIN)
        return
    }
    var actor by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(7) }
    val now = Clock.now()
    val audit = load(actor, days) { m.api.audit(fromMillis = now - days * 86_400_000L, actor = actor.ifBlank { null }) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(actor, { actor = it }, label = { Text("Actor") }, singleLine = true, modifier = Modifier.width(180.dp))
        SearchField(filter, { filter = it }, null, "Action or target")
        ChipRow(listOf(1, 7, 30, 90), days, { "$it d" }) { days = it }
        TextButton(onClick = { audit.value?.let { saveText(auditCsv(it), "audit.csv", "text/csv") } }) { Text(Strings.EXPORT_CSV) }
    }
    val list = audit.value
    if (list == null) {
        if (audit.error != null) Banner(audit.error, action = Strings.RETRY to audit.reload) else Skeleton(5)
        return
    }
    val shown = list.filter { e -> filter.isBlank() || e.action.contains(filter, true) || e.target?.contains(filter, true) == true }
    if (shown.isEmpty()) {
        EmptyState("No audit entries in this range")
        return
    }
    TableHeader(listOf(Col("Time", 1.8f), Col("Actor", 1.4f), Col("Action", 1.8f), Col("Target", 2.2f), Col("Result", 1.2f), Col("Params", 2.6f)))
    shown.sortedByDescending { it.timeMillis }.take(1000).forEach { e ->
        TableRow(highlight = e.result != "ok") {
            TextCell(1.8f, Format.timestamp(e.timeMillis))
            TextCell(1.4f, e.actor + (e.role?.let { " (${it.name.lowercase()})" } ?: ""))
            TextCell(1.8f, e.action)
            TextCell(2.2f, e.target)
            TextCell(1.2f, e.result)
            TextCell(2.6f, e.params.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { null })
        }
    }
}

private fun auditCsv(entries: List<AuditEntry>): String = buildString {
    append("time,actor,role,action,target,result,duration_ms,params\n")
    entries.forEach { e ->
        listOf(Format.timestamp(e.timeMillis), e.actor, e.role?.name?.lowercase() ?: "", e.action, e.target ?: "", e.result, e.durationMs.toString(),
            e.params.entries.joinToString(";") { "${it.key}=${it.value}" }).joinTo(this, ",") { Format.csvCell(it) }
        append('\n')
    }
}

@Composable
private fun AboutTab(m: AppModel, about: AboutInfo, s: Settings, save: (Settings) -> Unit) {
    Section("About") {
        Labeled("ControlLima", about.version)
        Labeled("Lima", about.limaVersion ?: "not found")
        Labeled("Colima", about.colimaVersion ?: "not found")
        Labeled("Data directory", about.dataDir)
        Labeled("Config file", about.configFile)
        about.latestVersion?.takeIf { it != about.version }?.let { Banner("Version $it is available.", BannerKind.INFO) }
        about.warnings.forEach { Banner(it, BannerKind.WARNING) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Switch(s.updateCheck, enabled = m.can(Role.ADMIN), onCheckedChange = { save(s.copy(updateCheck = it)) })
            Text("  Check GitHub for new releases (sends no data besides the request)")
        }
        Text(Strings.SHORTCUTS, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), error: String? = null, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Column(modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, singleLine = singleLine, isError = error != null, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(value, onChange, enabled = enabled)
        Text("  $label")
    }
}

private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
private fun words(text: String) = text.split(' ', '\t', '\n').filter { it.isNotEmpty() }
