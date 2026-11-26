package controllima.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import controllima.shared.ApiException
import controllima.shared.Names
import controllima.shared.Role
import controllima.shared.ValidationResult
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.launch

/** VM detail → Snapshots (docs/pages/vm-detail.md). */
@Composable
fun VmSnapshotsTab(m: AppModel, vm: Vm) {
    var nonce by remember { mutableStateOf(0) }
    val list = load(vm.ref, nonce) { m.api.snapshots(vm.ref) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    val s = list.value
    if (s == null) {
        if (list.error != null) Banner(list.error, action = Strings.RETRY to list.reload) else Skeleton(3)
        return
    }
    if (!s.supported) {
        EmptyState("Snapshots are not available", s.reason)
        return
    }
    val refresh: () -> Unit = { nonce++ }
    Section("Snapshots", actions = { SmallAction("Take snapshot", m.can(Role.OPERATOR)) { dialog = "create" to "" } }) {
        Text(
            "Snapshots save the VM's disk (and memory when it is running) inside its instance directory. " +
                "They are not backups: deleting the VM deletes them too.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (s.tags.isEmpty()) EmptyState("No snapshots yet")
        s.tags.forEach { tag ->
            TableRow {
                TextCell(3f, tag)
                Cell(3f) {
                    Row {
                        SmallAction("Revert to this", m.can(Role.ADMIN), danger = true) { dialog = "apply" to tag }
                        SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { dialog = "delete" to tag }
                    }
                }
            }
        }
    }
    when (dialog?.first) {
        "create" -> InputDialog(
            title = "Snapshot ${vm.name}",
            label = "Tag",
            confirmLabel = "Take snapshot",
            validate = { if (Names.isValidSnapshotTag(it)) if (it in s.tags) "A snapshot with this tag exists" else null else "Use letters, digits, '.', '-' and '_'" },
            onDismiss = { dialog = null },
        ) { tag ->
            m.track("Snapshot $tag", m.api.createSnapshot(vm.ref, tag).actionId, refresh)
        }
        "apply" -> {
            val tag = dialog!!.second
            ConfirmDialog(
                title = "Revert ${vm.name} to $tag?",
                name = vm.name,
                details = listOf(
                    "The VM's disk goes back to the state saved in $tag.",
                    "Everything written since then is lost unless another snapshot or backup has it.",
                ),
                confirmLabel = "Revert",
                onDismiss = { dialog = null },
            ) { typed -> m.track("Revert ${vm.name} to $tag", m.api.applySnapshot(vm.ref, tag, typed).actionId, refresh) }
        }
        "delete" -> {
            val tag = dialog!!.second
            ConfirmDialog(
                title = "Delete snapshot $tag?",
                name = tag,
                details = listOf("The snapshot of ${vm.name} is removed and cannot be restored."),
                onDismiss = { dialog = null },
            ) { typed -> m.track("Delete snapshot $tag", m.api.deleteSnapshot(vm.ref, tag, typed).actionId, refresh) }
        }
    }
}

/**
 * VM detail → Config. Everyone sees the redacted file; admins of a stopped Lima VM can edit the real
 * one. Saving validates with `limactl validate` and backs up the old file first.
 */
@Composable
fun VmConfigTab(m: AppModel, vm: Vm, redacted: String?) {
    val canEdit = vm.source == VmSource.LIMA && m.can(Role.ADMIN)
    var editing by remember(vm.ref) { mutableStateOf(false) }
    if (!editing) {
        Section("lima.yaml", actions = {
            if (canEdit) SmallAction("Edit", vm.status == VmStatus.STOPPED) { editing = true }
        }) {
            if (canEdit && vm.status != VmStatus.STOPPED) Text("Stop the VM to edit its config.", style = MaterialTheme.typography.bodySmall)
            if (redacted != null) CodeBlock(redacted) else Text("No config file found.")
        }
        return
    }
    val config = load(vm.ref) { m.api.vmConfig(vm.ref) }
    val c = config.value
    if (c == null) {
        if (config.error != null) Banner(config.error, action = Strings.RETRY to config.reload) else Skeleton(6)
        return
    }
    if (!c.editable) {
        Banner(c.reason ?: "This config cannot be edited now.", BannerKind.WARNING)
        TextButton(onClick = { editing = false }) { Text(Strings.CANCEL) }
        return
    }
    ConfigEditor(m, vm, c.yaml) { editing = false }
}

@Composable
private fun ConfigEditor(m: AppModel, vm: Vm, original: String, onClose: () -> Unit) {
    var text by remember { mutableStateOf(original) }
    var validation by remember { mutableStateOf<ValidationResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changed = text != original
    Section("Edit lima.yaml") {
        Text(
            "Lima reads this file when the VM starts. Saving checks it with limactl validate and keeps the old file in a backup.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            text,
            { text = it; validation = null },
            textStyle = TextStyle(fontFamily = MaterialTheme.typography.bodySmall.fontFamily, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(vertical = 8.dp),
        )
        validation?.let { v ->
            if (v.ok) Banner("limactl validate: OK" + v.errors.joinToString("") { "\n$it" }, BannerKind.INFO)
            else Banner(v.errors.joinToString("\n"), BannerKind.ERROR)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { validation = m.api.validateLimaYaml(text) } catch (e: ApiException) { m.toast(e.message ?: "Validation failed") }
                    busy = false
                }
            }) { Text("Validate") }
            Button(enabled = !busy && changed, onClick = { confirm = true }) { Text(Strings.SAVE) }
            TextButton(enabled = !busy, onClick = onClose) { Text(Strings.CANCEL) }
            if (!changed) Text("No changes", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) ConfirmDialog(
        title = "Save lima.yaml of ${vm.name}?",
        name = vm.name,
        details = listOf("The current file is backed up first.", "The change applies the next time ${vm.name} starts."),
        confirmLabel = Strings.SAVE,
        onDismiss = { confirm = false },
    ) { typed ->
        val result = m.api.saveVmConfig(vm.ref, text, typed)
        validation = result.validation
        if (result.saved) {
            m.toast("Saved; backup ${result.backupId}")
            onClose()
        }
    }
}
