package controllima.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import controllima.shared.ApiException
import controllima.shared.ApiRoutes
import controllima.shared.BackupDiff
import controllima.shared.BackupInfo
import controllima.shared.CreateBackupRequest
import controllima.shared.CreateVmPreview
import controllima.shared.CreateVmRequest
import controllima.shared.DiffKind
import controllima.shared.Format
import controllima.shared.Names
import controllima.shared.RestoreRequest
import controllima.shared.RestoreResult
import controllima.shared.Role
import controllima.shared.SaveTemplateRequest
import controllima.shared.SecretScan
import controllima.shared.Targets
import controllima.shared.VmSource
import controllima.shared.VmTemplate
import kotlinx.coroutines.launch

// ---------- Templates (docs/pages/templates.md) ----------

@Composable
fun TemplatesPage(m: AppModel) {
    val templates = load(Unit) { m.api.templates() }
    var creating by remember { mutableStateOf<VmTemplate?>(null) }
    var saving by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VmTemplate?>(null) }
    PageHeader(Page.Templates.title) {
        RoleButton(m, "Save VM as template", Role.ADMIN) { saving = true }
        TextButton(onClick = { importing = true }, enabled = m.can(Role.ADMIN)) { Text("Import YAML") }
    }
    val list = templates.value
    if (list == null) {
        if (templates.error != null) Banner(templates.error, action = Strings.RETRY to templates.reload) else Skeleton(4)
        return
    }
    if (list.none { !it.builtIn }) Text("Only built-in templates so far. Save a VM as a template to reuse its configuration.", Modifier.padding(bottom = 12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEach { t ->
            Card(Modifier.width(300.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        if (t.builtIn) Tag("built-in")
                        if (t.colima) Tag("Colima")
                    }
                    Text(t.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                    Text(listOfNotNull(t.cpus?.let { "$it CPU" }, t.memoryGiB?.let { "${Format.oneDecimal(it)} GiB RAM" }, t.diskGiB?.let { "${Format.oneDecimal(it)} GiB disk" }, t.arch).joinToString(" · ").ifEmpty { "Base: ${t.base}" })
                    FlowRow {
                        SmallAction(if (t.colima) "Preview" else "Create VM", m.can(Role.OPERATOR)) { creating = t }
                        t.yaml?.let { y -> SmallAction("Export") { saveText(y, "${t.id}.yaml", "application/yaml") } }
                        if (!t.builtIn) SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { deleting = t }
                    }
                }
            }
        }
    }
    creating?.let { t -> CreateVmDialog(m, t) { creating = null } }
    if (saving) {
        val names = m.vms?.vms.orEmpty().filter { it.source == VmSource.LIMA }.map { it.ref.targetId }
        SaveTemplateDialog(m, names, onDismiss = { saving = false }) { templates.reload() }
    }
    if (importing) {
        ImportTemplateDialog(m, onDismiss = { importing = false }) { templates.reload() }
    }
    deleting?.let { t ->
        ConfirmDialog("Delete template ${t.name}?", t.name, listOf("VMs created from it are not affected."), onDismiss = { deleting = null }) {
            m.api.deleteTemplate(t.id)
            templates.reload()
        }
    }
}

/** Wizard: parameters → dry-run preview (command, YAML, warnings) → create, stopped. */
@Composable
private fun CreateVmDialog(m: AppModel, t: VmTemplate, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var cpus by remember { mutableStateOf(t.cpus?.toString() ?: "") }
    var memory by remember { mutableStateOf(t.memoryGiB?.let(Format::oneDecimal) ?: "") }
    var disk by remember { mutableStateOf(t.diskGiB?.let(Format::oneDecimal) ?: "") }
    var mounts by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<CreateVmPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nameProblem = when {
        name.isEmpty() -> null
        !Names.isValidVmName(name) -> "Letters, digits, '.', '_' and '-' only"
        m.vms?.vms.orEmpty().any { it.name == name } -> "A VM with this name exists"
        else -> null
    }
    fun request(): CreateVmRequest? {
        val c = cpus.takeIf { it.isNotBlank() }?.toIntOrNull()
        val mem = memory.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val d = disk.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if ((cpus.isNotBlank() && c == null) || (memory.isNotBlank() && mem == null) || (disk.isNotBlank() && d == null)) {
            error = "CPU, memory and disk must be numbers"
            return null
        }
        return CreateVmRequest(name, c, mem, d, lines(mounts), lines(ports))
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (t.colima) "Preview Colima profile from ${t.name}" else "Create VM from ${t.name}") },
        text = {
            Column {
                if (preview == null) {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true, isError = nameProblem != null, modifier = Modifier.fillMaxWidth())
                    nameProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(cpus, { cpus = it }, label = { Text("CPUs") }, singleLine = true, modifier = Modifier.width(100.dp))
                        OutlinedTextField(memory, { memory = it }, label = { Text("Memory GiB") }, singleLine = true, modifier = Modifier.width(130.dp))
                        OutlinedTextField(disk, { disk = it }, label = { Text("Disk GiB") }, singleLine = true, modifier = Modifier.width(130.dp))
                    }
                    OutlinedTextField(mounts, { mounts = it }, label = { Text("Mounts (one host path per line, :w for writable)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(ports, { ports = it }, label = { Text("Port forwards (host:guest per line)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("The VM is created stopped. Starting it is a separate, explicit step.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    val p = preview!!
                    p.warnings.forEach { Banner(it, BannerKind.WARNING) }
                    Text("Command", style = MaterialTheme.typography.labelMedium)
                    CodeBlock(p.command.joinToString(" "))
                    p.yaml?.let {
                        Text("Configuration", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        CodeBlock(it, Modifier.height(260.dp))
                    }
                    if (t.colima) Text("Colima profiles cannot be created without starting them, so ControlLima only previews this command.", Modifier.padding(top = 8.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (preview == null) {
                Button(enabled = !busy && name.isNotEmpty() && nameProblem == null, onClick = {
                    val r = request() ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            preview = m.api.previewCreate(t.id, r)
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Preview") }
            } else if (!t.colima) {
                Button(enabled = !busy, onClick = {
                    val r = request() ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            val id = m.api.createFromTemplate(t.id, r).actionId
                            m.track("Create $name", id)
                            onDismiss()
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Create (stopped)") }
            }
        },
        dismissButton = {
            Row {
                if (preview != null) TextButton(onClick = { preview = null }, enabled = !busy) { Text("Back") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(Strings.CANCEL) }
            }
        },
    )
}

private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun SaveTemplateDialog(m: AppModel, vms: List<String>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var vm by remember { mutableStateOf(vms.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save VM as template") },
        text = {
            Column {
                if (vms.isEmpty()) Text("No Lima VMs to save.")
                else Picker("VM", vms, vm, Targets::label) { vm = it }
                OutlinedTextField(name, { name = it }, label = { Text("Template name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Secrets matching the redaction patterns are removed from the saved configuration.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = vm != null && name.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        m.api.saveTemplate(SaveTemplateRequest(name.trim(), description.trim(), fromVm = vm))
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

/** Import is a paste of `lima.yaml`: the browser never reads local files for the server. */
@Composable
private fun ImportTemplateDialog(m: AppModel, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var yaml by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import template") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Template name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(yaml, { yaml = it; checked = null }, label = { Text("lima.yaml") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp))
                checked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = yaml.isNotBlank() && m.can(Role.OPERATOR), onClick = {
                scope.launch {
                    checked = try {
                        val v = m.api.validateLimaYaml(yaml)
                        if (v.ok) "limactl validate: OK" else v.errors.joinToString("\n")
                    } catch (e: ApiException) {
                        e.message
                    }
                }
            }) { Text("Validate") }
            Button(enabled = name.isNotBlank() && yaml.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        m.api.saveTemplate(SaveTemplateRequest(name.trim(), yaml = yaml))
                        onSaved()
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    }
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

// ---------- Backups (docs/pages/backups.md) ----------

@Composable
fun BackupsPage(m: AppModel) {
    val backups = load(Unit) { m.api.backups() }
    var creating by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<BackupInfo?>(null) }
    var deleting by remember { mutableStateOf<BackupInfo?>(null) }
    PageHeader(Page.Backups.title) {
        RoleButton(m, "Back up now", Role.OPERATOR) { creating = true }
        TextButton(enabled = m.can(Role.ADMIN), onClick = {
            m.scope.launch {
                val headers = try { m.api.writeHeaders() } catch (e: ApiException) { m.toast(e.message ?: "Upload failed"); return@launch }
                uploadFile("${ApiRoutes.BACKUPS}/import", headers, ".tar.gz,.tgz,application/gzip") { status, _ ->
                    if (status in 200..299) { m.toast("Backup imported"); backups.reload() } else m.toast("Import failed (HTTP $status)")
                }
            }
        }) { Text("Upload archive") }
    }
    BackupSchedule(m)
    val list = backups.value
    if (list == null) {
        if (backups.error != null) Banner(backups.error, action = Strings.RETRY to backups.reload) else Skeleton(4)
        return
    }
    if (list.isEmpty()) {
        EmptyState(Strings.NO_BACKUPS, action = if (m.can(Role.OPERATOR)) "Back up now" to { creating = true } else null)
    } else {
        TableHeader(listOf(Col("Time", 1.8f), Col("Scope", 3f), Col("Size", 1f), Col("Kind", 1f), Col("Secrets", 1.4f), Col("", 3f)))
        list.sortedByDescending { it.createdMillis }.forEach { b ->
            TableRow {
                TextCell(1.8f, Format.timestamp(b.createdMillis))
                TextCell(3f, b.items.joinToString(", ") { Targets.label(it.target) })
                TextCell(1f, Format.bytes(b.sizeBytes, m.units))
                TextCell(1f, if (b.scheduled) "scheduled" else "manual")
                Cell(1.4f) {
                    val flagged = b.items.sumOf { it.secretsFlagged.size }
                    when {
                        b.redacted -> Text("redacted")
                        flagged > 0 -> Marked(Glyph.TRIANGLE, "$flagged flagged", Palette.warning)
                        else -> Text("—")
                    }
                }
                Cell(3f) {
                    Row {
                        SmallAction("Restore", m.can(Role.ADMIN)) { restoring = b }
                        SmallAction("Download", m.can(Role.ADMIN)) {
                            downloadAuthorized("${ApiRoutes.BACKUPS}/${ApiRoutes.encode(b.id)}/download", m.api.token, "${b.id}.tar.gz") { m.toast(it) }
                        }
                        SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { deleting = b }
                    }
                }
            }
        }
    }
    if (creating) CreateBackupDialog(m, onDismiss = { creating = false }) { backups.reload() }
    restoring?.let { b -> RestoreDialog(m, b) { restoring = null } }
    deleting?.let { b ->
        ConfirmDialog("Delete backup?", b.id, listOf("Created ${Format.timestamp(b.createdMillis)}", "${b.items.size} item(s), ${Format.bytes(b.sizeBytes, m.units)}"), onDismiss = { deleting = null }) { typed ->
            m.api.deleteBackup(b.id, typed)
            backups.reload()
        }
    }
}

@Composable
private fun BackupSchedule(m: AppModel) {
    val settings = load(Unit) { m.api.settings() }
    val s = settings.value?.settings ?: return
    var everyDays by remember(s) { mutableStateOf(s.backupSchedule.everyDays.toString()) }
    var keep by remember(s) { mutableStateOf(s.backupSchedule.keep.toString()) }
    var enabled by remember(s) { mutableStateOf(s.backupSchedule.enabled) }
    Section("Schedule") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(enabled, { enabled = it }, enabled = m.can(Role.ADMIN))
            Text("Back up every")
            OutlinedTextField(everyDays, { everyDays = it }, singleLine = true, enabled = m.can(Role.ADMIN), modifier = Modifier.width(80.dp))
            Text("days, keep")
            OutlinedTextField(keep, { keep = it }, singleLine = true, enabled = m.can(Role.ADMIN), modifier = Modifier.width(80.dp))
            Text("scheduled backups")
            val days = everyDays.toIntOrNull()
            val k = keep.toIntOrNull()
            RoleButton(m, Strings.SAVE, Role.ADMIN, enabled = days != null && days > 0 && k != null && k > 0) {
                m.launchCall("Schedule saved", { m.api.saveSettings(s.copy(backupSchedule = s.backupSchedule.copy(enabled = enabled, everyDays = days!!, keep = k!!))) }) { settings.reload() }
            }
        }
    }
}

@Composable
private fun CreateBackupDialog(m: AppModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    val vms = m.vms?.vms.orEmpty().filter { it.source == VmSource.LIMA }.map { it.ref.targetId }
    var selected by remember { mutableStateOf(vms.toSet()) }
    var redact by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<List<SecretScan>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Back up now") },
        text = {
            Column {
                Text("VM configurations (lima.yaml) and ControlLima settings. Disks are not included.")
                FlowRow(Modifier.padding(vertical = 8.dp)) {
                    vms.forEach { t ->
                        val on = t in selected
                        FilterChip(on, { selected = if (on) selected - t else selected + t; scan = null }, label = { Text(Targets.label(t)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(redact, { redact = it })
                    Text("Redact secrets in the archive")
                }
                scan?.let { found ->
                    val flagged = found.filter { it.findings.isNotEmpty() }
                    if (flagged.isEmpty()) Text("No secrets found.", color = Palette.ok)
                    else Banner("Possible secrets: " + flagged.joinToString("; ") { "${Targets.label(it.target)}: ${it.findings.joinToString()}" }, BannerKind.WARNING)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val request = CreateBackupRequest(selected.toList(), includeSettings = true, redactSecrets = redact)
                        // First click scans; secrets in an unredacted archive need a second, informed click.
                        if (scan == null) {
                            val found = m.api.scanSecrets(request.targets)
                            scan = found
                            if (!redact && found.any { it.findings.isNotEmpty() }) return@launch
                        }
                        m.api.createBackup(request)
                        m.toast("Backup created")
                        onDone()
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (scan == null) "Scan and back up" else "Back up") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(Strings.CANCEL) } },
    )
}

/** Restore wizard: pick items → diff per item → optional new name → typed confirm → result. */
@Composable
private fun RestoreDialog(m: AppModel, b: BackupInfo, onDismiss: () -> Unit) {
    var targets by remember { mutableStateOf(b.items.map { it.target }.toSet()) }
    var diffTarget by remember { mutableStateOf(b.items.firstOrNull()?.target) }
    var newName by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<RestoreResult?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val diff = load(b.id, diffTarget) { diffTarget?.let { m.api.backupDiff(b.id, it) } }
    val single = targets.singleOrNull()
    val singleVm = single?.let { Targets.parseVm(it) }
    val protectedTarget = singleVm != null && m.protectedNames.contains(singleVm.name) && newName.isBlank()
    result?.let { r ->
        InfoDialog("Restore finished", onDismiss) {
            if (r.restored.isNotEmpty()) Text("Restored: " + r.restored.joinToString { Targets.label(it) })
            if (r.created.isNotEmpty()) Text("Created: " + r.created.joinToString { Targets.label(it) })
            if (r.restartRequired.isNotEmpty()) Banner(Strings.RESTART_REQUIRED + " " + r.restartRequired.joinToString { Targets.label(it) }, BannerKind.WARNING)
            r.notes.forEach { Text(it) }
        }
        return
    }
    if (confirming) {
        // One target confirms with its name; several confirm with the backup id (docs/plan/backups.md).
        val confirmName = if (single != null) newName.ifBlank { singleVm?.name ?: Targets.label(single) } else b.id
        ConfirmDialog(
            "Restore from backup?",
            confirmName,
            listOf("Overwrites the current configuration of: " + targets.joinToString { Targets.label(it) }, "Running VMs keep running; restart them yourself to apply."),
            confirmLabel = "Restore",
            isProtected = protectedTarget,
            onDismiss = { confirming = false },
        ) { typed ->
            val r = m.api.restore(b.id, RestoreRequest(targets.toList(), typed, newName.ifBlank { null }))
            r.actionId?.let { m.track("Restore ${newName.ifBlank { confirmName }}", it) }
            result = r
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore ${Format.timestamp(b.createdMillis)}") },
        text = {
            Column {
                FlowRow {
                    b.items.forEach { item ->
                        val on = item.target in targets
                        FilterChip(on, { targets = if (on) targets - item.target else targets + item.target }, label = { Text(Targets.label(item.target)) })
                        TextButton(onClick = { diffTarget = item.target }) { Text("diff") }
                    }
                }
                diffTarget?.let { t ->
                    Text("Differences for ${Targets.label(t)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    when (val d = diff.value) {
                        null -> if (diff.error != null) Text(diff.error) else Skeleton(3)
                        else -> DiffView(d)
                    }
                }
                if (singleVm != null) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Restore as a new VM (optional name)") }, singleLine = true,
                        isError = newName.isNotEmpty() && !Names.isValidVmName(newName), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (protectedTarget) Text("${singleVm.name} is protected: restore it under a new name.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(enabled = targets.isNotEmpty() && (newName.isEmpty() || Names.isValidVmName(newName)) && !protectedTarget, onClick = { confirming = true }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
private fun DiffView(d: BackupDiff) {
    if (!d.currentExists) Text("The VM no longer exists; restoring creates it.", Modifier.padding(bottom = 4.dp))
    if (d.lines.none { it.kind != DiffKind.SAME }) {
        Text("No differences.")
        return
    }
    Column(Modifier.fillMaxWidth().height(240.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        androidx.compose.foundation.lazy.LazyColumn {
            items(d.lines.size) { i ->
                val l = d.lines[i]
                val (prefix, color) = when (l.kind) {
                    DiffKind.ADDED -> "+ " to Palette.ok
                    DiffKind.REMOVED -> "- " to MaterialTheme.colorScheme.error
                    DiffKind.SAME -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(prefix + l.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
            }
        }
    }
}
