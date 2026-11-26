package controllima.server

import controllima.providers.lima.LimaJson
import controllima.server.backup.BackupService
import controllima.shared.CreateBackupRequest
import controllima.shared.SaveConfigResult
import controllima.shared.SnapshotList
import controllima.shared.ValidationResult
import controllima.shared.Vm
import controllima.shared.VmConfig
import controllima.shared.VmSource
import controllima.shared.VmStatus
import java.io.File
import java.nio.file.Files

/**
 * v2 VM editing (docs/plan/README.md, v2): `lima.yaml` edits checked by `limactl validate`, and
 * snapshots where the VM type supports them (docs/plan/backup-restore.md).
 */
class VmEditService(private val paths: DataPaths, private val backends: Backends, private val backups: BackupService) {

    fun snapshots(vm: Vm): SnapshotList {
        unsupportedReason(vm)?.let { return SnapshotList(false, it) }
        val lima = backends.lima(vm.home) ?: return SnapshotList(false, "Lima home is not configured")
        return SnapshotList(true, tags = lima.snapshots(vm.name))
    }

    /** Null when snapshots work for [vm]. Lima only implements them for QEMU; Colima has none. */
    fun unsupportedReason(vm: Vm): String? = when {
        vm.source != VmSource.LIMA -> "Colima has no snapshots"
        vm.vmType != null && vm.vmType != "qemu" -> "Lima supports snapshots only for QEMU VMs (this one uses ${vm.vmType})"
        else -> null
    }

    fun config(vm: Vm): VmConfig {
        val file = configFile(vm) ?: return VmConfig("", false, "This VM has no lima.yaml")
        if (!file.isFile) return VmConfig("", false, "lima.yaml not found")
        if (file.length() > MAX_YAML) return VmConfig("", false, "lima.yaml is too large to edit here")
        return VmConfig(file.readText(), editableReason(vm) == null, editableReason(vm))
    }

    private fun editableReason(vm: Vm): String? = when {
        vm.source != VmSource.LIMA -> "Colima configs are edited with colima start --edit"
        vm.status != VmStatus.STOPPED -> "Stop ${vm.name} to edit its config; Lima reads it only at start"
        else -> null
    }

    /** `limactl validate` needs a file whose name is a valid instance name, so the YAML goes to a temp dir. */
    fun validate(yaml: String, name: String = "check"): ValidationResult {
        require(yaml.length <= MAX_YAML) { "YAML is too large" }
        val lima = backends.limaProviders().firstOrNull() ?: throw IllegalStateException("Lima is not configured")
        val dir = Files.createTempDirectory(paths.tmp.toPath(), "validate").toFile()
        try {
            val file = File(dir, "$name.yaml")
            writePrivate(file, yaml)
            val result = lima.validate(file.absolutePath)
            if (result.timedOut) return ValidationResult(false, listOf("limactl validate timed out"))
            val messages = LimaJson.parseLogMessages(result.stderr + "\n" + result.stdout).map { it.replace(file.absolutePath, "lima.yaml") }
            return ValidationResult(result.ok, if (result.ok) messages else messages.ifEmpty { listOf("limactl validate failed (exit ${result.exitCode})") })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Validates, backs up the current config, then replaces it atomically. Nothing is written on failure. */
    fun save(vm: Vm, yaml: String): SaveConfigResult {
        editableReason(vm)?.let { throw IllegalStateException(it) }
        val file = configFile(vm) ?: throw IllegalStateException("This VM has no lima.yaml")
        val validation = validate(yaml, vm.name)
        if (!validation.ok) return SaveConfigResult(validation, saved = false)
        val backup = backups.create(CreateBackupRequest(listOf(vm.ref.targetId), includeSettings = false, redactSecrets = false))
        writePrivate(file, yaml)
        return SaveConfigResult(validation, saved = true, backupId = backup.id)
    }

    private fun configFile(vm: Vm): File? = if (vm.source == VmSource.LIMA) vm.dir?.let { File(it, "lima.yaml") } else null

    companion object {
        const val MAX_YAML = 512 * 1024
    }
}
