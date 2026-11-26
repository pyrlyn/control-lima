package controllima.server

import controllima.server.backup.BackupService
import controllima.server.http.conflict
import controllima.server.http.notFound
import controllima.server.metrics.MetricsStore
import controllima.shared.CleanupRequest
import controllima.shared.CleanupResult
import controllima.shared.CleanupSuggestion
import controllima.shared.DiskReport
import controllima.shared.DockerDisk
import controllima.shared.Targets
import controllima.shared.VmDisk
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Disk usage and reclaim suggestions (docs/plan/disk-cleanup.md). Every cleanup runs as a dry run
 * first; the real run needs the suggestion's confirm word. Unused volumes are never deleted.
 */
class DiskService(
    private val backends: Backends,
    private val inventory: Inventory,
    private val stats: StatsCollector,
    private val metrics: MetricsStore,
    private val backups: BackupService,
) {
    suspend fun report(): DiskReport = withContext(Dispatchers.IO) {
        val vms = inventory.snapshot.value.vms
        val homeDir = limaHomeDir()
        val space = runCatching { backends.host.diskSpace(homeDir) }.getOrNull()
        val vmDisks = vms.mapNotNull { vm ->
            val dir = vm.dir?.let(::File) ?: return@mapNotNull null
            val files = DISK_FILES.map { File(dir, it) }.filter { it.isFile }
            if (files.isEmpty()) return@mapNotNull null
            val sizes = files.mapNotNull { backends.host.fileSize(it.path) }
            val sample = stats.latest(vm.ref.targetId)
            VmDisk(vm.ref.targetId, vm.name, sizes.sumOf { it.allocatedBytes }, sizes.sumOf { it.apparentBytes }, sample?.diskUsedBytes, sample?.diskTotalBytes)
        }
        val docker = coroutineScope {
            backends.colimaProfiles().map { profile ->
                async { runCatching { backends.docker(profile)?.systemDf(profile) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        DiskReport(
            hostFreeBytes = space?.freeBytes ?: 0,
            hostTotalBytes = space?.totalBytes ?: 0,
            vms = vmDisks,
            docker = docker,
            backupsBytes = backups.sizeBytes(),
            metricsBytes = metrics.sizeBytes(),
            suggestions = suggestions(docker),
        )
    }

    private fun limaHomeDir(): String =
        backends.limaProviders().firstOrNull()?.home ?: System.getenv("LIMA_HOME") ?: "${System.getProperty("user.home")}/.lima"

    private fun suggestions(docker: List<DockerDisk>): List<CleanupSuggestion> {
        val out = mutableListOf<CleanupSuggestion>()
        for (d in docker) {
            val provider = backends.docker(d.profile) ?: continue
            runCatching { provider.pruneImages(dryRun = true) }.getOrNull()?.takeIf { it.images.isNotEmpty() }?.let {
                out += CleanupSuggestion("images:${d.profile}", "Dangling images (${d.profile})", it.bytes, it.images, confirm = PRUNE)
            }
            runCatching { provider.pruneContainers(dryRun = true) }.getOrNull()?.takeIf { it.images.isNotEmpty() }?.let {
                out += CleanupSuggestion("containers:${d.profile}", "Stopped containers (${d.profile})", it.bytes, it.images, confirm = PRUNE)
            }
            if (d.buildCacheBytes > 0) {
                out += CleanupSuggestion("build-cache:${d.profile}", "Build cache (${d.profile})", d.buildCacheBytes, confirm = PRUNE)
            }
            if (d.volumesBytes > 0) {
                out += CleanupSuggestion("volumes:${d.profile}", "Volumes (${d.profile})", d.volumesBytes,
                    note = "Volumes hold data; ControlLima lists them but never deletes them. Use docker volume rm yourself.")
            }
        }
        inventory.snapshot.value.vms.filter { it.status == VmStatus.RUNNING && it.sshConfigFile != null }.forEach { vm ->
            out += CleanupSuggestion("fstrim:${vm.ref.targetId}", "Trim free space in ${vm.name}", null,
                note = "Runs fstrim inside the guest so the sparse disk file can shrink.", confirm = vm.name)
        }
        oldBackups().takeIf { it.isNotEmpty() }?.let { list ->
            out += CleanupSuggestion("backups", "Old backups (keeping the newest $KEEP_BACKUPS)", list.sumOf { it.sizeBytes }, list.map { it.id }, confirm = DELETE)
        }
        orphanMetrics().takeIf { it.isNotEmpty() }?.let { list ->
            out += CleanupSuggestion("metrics", "History of removed VMs and containers", null, list.map(Targets::label), confirm = DELETE)
        }
        return out
    }

    private fun oldBackups() = backups.list().drop(KEEP_BACKUPS)

    private fun orphanMetrics(): List<String> {
        val snapshot = inventory.snapshot.value
        // A failed listing would make every VM look deleted.
        if (snapshot.errors.isNotEmpty()) return emptyList()
        val live = snapshot.vms.map { it.ref.targetId }.toSet()
        // Containers are left alone: a stopped Colima hides its containers without deleting them.
        return metrics.targets().filter { it.startsWith("vm:") && it !in live }
    }

    suspend fun cleanup(request: CleanupRequest, dryRun: Boolean): CleanupResult = withContext(Dispatchers.IO) {
        val id = request.id
        val kind = id.substringBefore(':')
        val arg = id.substringAfter(':', "")
        fun confirm(word: String) {
            if (!dryRun && request.confirmName != word) throw IllegalArgumentException("Type \"$word\" to confirm")
        }
        when (kind) {
            "images", "containers", "build-cache" -> {
                val docker = backends.docker(arg) ?: notFound("Docker is not available for profile $arg")
                confirm(PRUNE)
                when (kind) {
                    "images" -> docker.pruneImages(dryRun).let { CleanupResult(id, dryRun, it.images, it.bytes) }
                    "containers" -> docker.pruneContainers(dryRun).let { CleanupResult(id, dryRun, it.images, it.bytes) }
                    else -> if (dryRun) CleanupResult(id, true, emptyList(), docker.systemDf(arg).buildCacheBytes)
                    else CleanupResult(id, false, emptyList(), docker.pruneBuildCache())
                }
            }
            "fstrim" -> {
                val ref = Targets.parseVm(arg) ?: notFound("Unknown VM")
                val vm = inventory.find(ref) ?: notFound("No VM ${ref.name}")
                if (vm.status != VmStatus.RUNNING) conflict("${vm.name} is not running")
                confirm(vm.name)
                if (dryRun) return@withContext CleanupResult(id, true, listOf("sudo fstrim -av in ${vm.name}"), 0)
                val instance = if (vm.source == VmSource.COLIMA) backends.colima().limaInstance(vm.name) else vm.name
                val result = backends.guest.exec(vm.sshConfigFile ?: conflict("${vm.name} has no SSH config"), instance, "sudo -n fstrim -av", 5.minutes)
                check(result.ok) { "fstrim failed (exit ${result.exitCode}); passwordless sudo is required" }
                CleanupResult(id, false, result.stdout.lines().filter { it.isNotBlank() }, 0)
            }
            "backups" -> {
                val old = oldBackups()
                confirm(DELETE)
                if (!dryRun) old.forEach { backups.delete(it.id) }
                CleanupResult(id, dryRun, old.map { it.id }, old.sumOf { it.sizeBytes })
            }
            "metrics" -> {
                val orphans = orphanMetrics()
                confirm(DELETE)
                if (!dryRun) orphans.forEach(metrics::forget)
                CleanupResult(id, dryRun, orphans, 0)
            }
            "volumes" -> conflict("Volumes are never deleted by ControlLima")
            else -> notFound("Unknown cleanup $id")
        }
    }

    companion object {
        const val PRUNE = "prune"
        const val DELETE = "delete"
        const val KEEP_BACKUPS = 5
        /** Lima 1.x keeps `basedisk` + `diffdisk`; 2.x may name the main disk `disk`. */
        val DISK_FILES = listOf("basedisk", "diffdisk", "disk")
    }
}
