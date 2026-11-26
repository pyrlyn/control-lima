package controllima.server

import controllima.server.http.badRequest
import controllima.server.http.conflict
import controllima.shared.ConfirmRequest
import controllima.shared.Names
import controllima.shared.Role
import controllima.shared.SaveConfigRequest
import controllima.shared.SaveConfigResult
import controllima.shared.SnapshotList
import controllima.shared.SnapshotRequest
import controllima.shared.ValidateRequest
import controllima.shared.ValidationResult
import controllima.shared.VmConfig
import controllima.shared.VmEditRoutes
import controllima.shared.VmStatus

/** Snapshots and `lima.yaml` editing (v2). */
internal fun Api.vmEditRoutes() {
    val vmPath = "/api/vms/{source}/{name}"

    get("$vmPath/snapshots") { json(s.vmEdit.snapshots(vm()), SnapshotList.serializer()) }
    post("$vmPath/snapshots", Role.OPERATOR) {
        val vm = vm()
        s.vmEdit.unsupportedReason(vm)?.let { conflict(it) }
        val tag = decode(request, SnapshotRequest.serializer()).tag
        if (!Names.isValidSnapshotTag(tag)) badRequest("Invalid snapshot tag")
        if (tag in s.vmEdit.snapshots(vm).tags) conflict("Snapshot $tag already exists")
        accepted(s.actions.launch("vm.snapshot_create", vm.ref.targetId, principal, mapOf("tag" to tag)) { progress ->
            lima(vm.ref).snapshotCreate(vm.name, tag, progress)
            "Snapshot $tag of ${vm.name} created"
        })
    }
    // Applying a snapshot throws away the disk's current state, so it is guarded like a delete.
    post("$vmPath/snapshots/{tag}/apply", Role.ADMIN) {
        val vm = vm()
        val tag = snapshotTag()
        requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, vm.name)
        s.vmEdit.unsupportedReason(vm)?.let { conflict(it) }
        accepted(s.actions.launch("vm.snapshot_apply", vm.ref.targetId, principal, mapOf("tag" to tag)) { progress ->
            lima(vm.ref).snapshotApply(vm.name, tag, progress)
            s.inventory.refresh()
            "${vm.name} reverted to snapshot $tag"
        })
    }
    delete("$vmPath/snapshots/{tag}", Role.ADMIN) {
        val vm = vm()
        val tag = snapshotTag()
        requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, tag)
        accepted(s.actions.launch("vm.snapshot_delete", vm.ref.targetId, principal, mapOf("tag" to tag)) { _ ->
            lima(vm.ref).snapshotDelete(vm.name, tag)
            "Snapshot $tag of ${vm.name} deleted"
        })
    }

    // The raw config can hold secrets (provision scripts, env), so reading it unredacted is admin-only.
    get("$vmPath/config", Role.ADMIN) { json(s.vmEdit.config(vm()), VmConfig.serializer()) }
    put("$vmPath/config", Role.ADMIN) {
        val vm = vm()
        val body = decode(request, SaveConfigRequest.serializer())
        requireConfirm(body.confirmName, vm.name)
        if (vm.status != VmStatus.STOPPED) conflict("Stop ${vm.name} to edit its config")
        if (s.actions.isBusy(vm.ref.targetId)) conflict("Another action is running on ${vm.name}")
        val result = s.vmEdit.save(vm, body.yaml)
        if (result.saved) s.actions.markUserAction(vm.ref.targetId)
        json(result, SaveConfigResult.serializer())
    }
    post(VmEditRoutes.LIMA_VALIDATE, Role.OPERATOR) {
        json(s.vmEdit.validate(decode(request, ValidateRequest.serializer()).yaml), ValidationResult.serializer())
    }
}

private fun Api.Ctx.snapshotTag(): String = request.path("tag").also { if (!Names.isValidSnapshotTag(it)) badRequest("Invalid snapshot tag") }
