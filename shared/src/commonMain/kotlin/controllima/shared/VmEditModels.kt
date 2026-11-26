package controllima.shared

import kotlinx.serialization.Serializable

/** `GET /api/vms/{source}/{name}/snapshots` (docs/plan/backup-restore.md, "Snapshots"). */
@Serializable
data class SnapshotList(
    val supported: Boolean,
    /** Why snapshots are unavailable (VZ, Colima), shown instead of the list. */
    val reason: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class SnapshotRequest(val tag: String)

/** `GET /api/vms/{source}/{name}/config`: the unredacted `lima.yaml`, admin only. */
@Serializable
data class VmConfig(
    val yaml: String,
    val editable: Boolean,
    /** Why the config cannot be saved right now (running VM, Colima, too large). */
    val reason: String? = null,
)

@Serializable
data class SaveConfigRequest(val yaml: String, val confirmName: String)

@Serializable
data class ValidateRequest(val yaml: String)

/** Result of `limactl validate`; [errors] are the messages Lima reported. */
@Serializable
data class ValidationResult(val ok: Boolean, val errors: List<String> = emptyList())

@Serializable
data class SaveConfigResult(
    val validation: ValidationResult,
    val saved: Boolean,
    /** Backup taken of the previous config, so the edit can be undone from the Backups page. */
    val backupId: String? = null,
)

object VmEditRoutes {
    /** Validates any Lima YAML (VM config or template) without saving it. */
    const val LIMA_VALIDATE = "/api/lima/validate"

    fun snapshots(ref: VmRef) = ApiRoutes.vmAction(ref, "snapshots")
    fun snapshot(ref: VmRef, tag: String, action: String? = null): String {
        val base = "${ApiRoutes.VMS}/${ref.source.slug}/${ApiRoutes.encode(ref.name)}/snapshots/${ApiRoutes.encode(tag)}"
        return base + (action?.let { "/$it" } ?: "") + (ref.home?.let { "?home=${ApiRoutes.encode(it)}" } ?: "")
    }
    fun config(ref: VmRef) = ApiRoutes.vmAction(ref, "config")
}
