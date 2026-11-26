package controllima.server.backup

import controllima.providers.lima.CreateFlags
import controllima.server.ActionManager
import controllima.server.Backends
import controllima.server.DataPaths
import controllima.server.Inventory
import controllima.server.http.conflict
import controllima.server.http.notFound
import controllima.server.privateDir
import controllima.server.security.Principal
import controllima.server.security.Redactor
import controllima.server.security.Secrets
import controllima.server.writePrivate
import controllima.shared.ApiJson
import controllima.shared.BackupDiff
import controllima.shared.BackupInfo
import controllima.shared.BackupItem
import controllima.shared.CreateBackupRequest
import controllima.shared.LineDiff
import controllima.shared.RestoreRequest
import controllima.shared.RestoreResult
import controllima.shared.SecretScan
import controllima.shared.Settings
import controllima.shared.Targets
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One file that can be backed up. [target] groups files (a VM, `lima-config`, `settings`). */
data class ConfigSource(val target: String, val archivePath: String, val file: File, val vm: Vm? = null)

/**
 * Backups of VM configs and ControlLima settings as `<id>.tar.gz` with a `manifest.json`
 * (docs/plan/backup-restore.md). Disks are never backed up. Restore never starts or restarts a VM.
 */
class BackupService(
    private val paths: DataPaths,
    private val backends: Backends,
    private val inventory: Inventory,
    private val actions: ActionManager,
    private val settings: () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dir get() = paths.backups
    private val manifests = mutableMapOf<String, Pair<Long, BackupInfo>>()

    private fun redactor() = Redactor(settings().redactionPatterns)

    // ---------- sources ----------

    fun sources(): List<ConfigSource> {
        val vms = inventory.snapshot.value.vms
        val out = mutableListOf<ConfigSource>()
        for (vm in vms) {
            when (vm.source) {
                VmSource.LIMA -> vm.dir?.let { out += ConfigSource(vm.ref.targetId, "lima/${homeKey(vm.home)}/${vm.name}/lima.yaml", File(it, "lima.yaml"), vm) }
                VmSource.COLIMA -> backends.colima().configFile(vm.name)?.let { out += ConfigSource(vm.ref.targetId, "colima/${vm.name}/colima.yaml", File(it), vm) }
            }
        }
        for (provider in backends.limaProviders()) {
            val home = provider.home
            val homeDir = File(home ?: System.getenv("LIMA_HOME") ?: "${System.getProperty("user.home")}/.lima")
            for (name in listOf("override.yaml", "default.yaml")) {
                out += ConfigSource(limaConfigTarget(home), "lima/${homeKey(home)}/_config/$name", File(homeDir, "_config/$name"))
            }
        }
        out += ConfigSource(SETTINGS, "controllima/config.json", paths.configFile)
        out += ConfigSource(SETTINGS, "controllima/alerts/rules.json", File(paths.alerts, "rules.json"))
        out += ConfigSource(SETTINGS, "controllima/alerts/channels.json", File(paths.alerts, "channels.json"))
        paths.templates.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach {
            out += ConfigSource(SETTINGS, "controllima/templates/${it.name}", it)
        }
        return out.filter { it.file.isFile }
    }

    private fun homeKey(home: String?) = if (home == null) "default" else Secrets.sha256(home).take(10)

    private fun limaConfigTarget(home: String?) = if (home == null) LIMA_CONFIG else "$LIMA_CONFIG@$home"

    private fun selected(request: CreateBackupRequest): List<ConfigSource> {
        val all = sources()
        val wanted = request.targets.toSet()
        return all.filter { s ->
            when {
                s.target == SETTINGS -> request.includeSettings
                wanted.isEmpty() -> true
                s.target.startsWith(LIMA_CONFIG) -> true
                else -> s.target in wanted
            }
        }.also { picked ->
            val unknown = wanted - all.map { it.target }.toSet()
            require(unknown.isEmpty()) { "Unknown backup targets: ${unknown.joinToString()}" }
            require(picked.isNotEmpty()) { "Nothing to back up" }
        }
    }

    fun scan(request: CreateBackupRequest): List<SecretScan> {
        val r = redactor()
        return selected(request).groupBy { it.target }.map { (target, files) ->
            SecretScan(target, files.flatMap { f -> r.scan(f.file.readText()).map { "${f.archivePath} $it" } })
        }.filter { it.findings.isNotEmpty() }
    }

    // ---------- create ----------

    @Synchronized
    fun create(request: CreateBackupRequest, scheduled: Boolean = false): BackupInfo {
        val files = selected(request)
        val r = redactor()
        val now = clock()
        val label = when {
            scheduled -> "scheduled"
            request.targets.size == 1 -> Targets.parseVm(request.targets[0])?.name ?: "backup"
            else -> "all"
        }
        val id = uniqueId("${STAMP.format(Instant.ofEpochMilli(now))}-$label")
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val items = files.map { s ->
            val text = s.file.readText()
            val findings = r.scan(text)
            val stored = (if (request.redactSecrets) r.redact(text) else text).encodeToByteArray()
            entries += s.archivePath to stored
            BackupItem(s.target, s.archivePath, Secrets.run { java.security.MessageDigest.getInstance("SHA-256").digest(stored).toHex() }, stored.size.toLong(), findings)
        }
        val limaVersion = runCatching { backends.limaProviders().first().info().version }.getOrNull()
        val info = BackupInfo(id, now, scheduled, 0, items, limaVersion, null, request.redactSecrets)
        val manifest = ApiJson.encodeToString(BackupInfo.serializer(), info)
        val archive = Tar.gzip(listOf(MANIFEST to manifest.encodeToByteArray()) + entries, now / 1000)
        privateDir(dir)
        writePrivate(File(dir, "$id.tar.gz"), archive)
        return info.copy(sizeBytes = archive.size.toLong())
    }

    private fun uniqueId(base: String): String {
        var id = base
        var n = 2
        while (File(dir, "$id.tar.gz").exists()) id = "$base-${n++}"
        return id
    }

    // ---------- list, read, delete ----------

    @Synchronized
    fun list(): List<BackupInfo> {
        val files = dir.listFiles { f -> f.name.endsWith(".tar.gz") }.orEmpty()
        manifests.keys.retainAll(files.map { it.name.removeSuffix(".tar.gz") }.toSet())
        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".tar.gz")
            manifests[id]?.takeIf { it.first == f.lastModified() }?.second
                ?: runCatching { readArchive(f).first.copy(id = id, sizeBytes = f.length()) }.getOrNull()?.also { manifests[id] = f.lastModified() to it }
        }.sortedByDescending { it.createdMillis }
    }

    fun file(id: String): File {
        require(ID.matches(id)) { "Invalid backup id" }
        return File(dir, "$id.tar.gz").takeIf { it.isFile } ?: notFound("No backup $id")
    }

    private fun readArchive(file: File): Pair<BackupInfo, Map<String, ByteArray>> {
        val entries = file.inputStream().use { Tar.readGzip(it) }
        val manifest = entries[MANIFEST] ?: throw IllegalArgumentException("Not a ControlLima backup: manifest.json missing")
        return ApiJson.decodeFromString(BackupInfo.serializer(), manifest.decodeToString()) to entries
    }

    @Synchronized
    fun delete(id: String) {
        file(id).delete()
        manifests.remove(id)
    }

    fun sizeBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    /** Validates an uploaded archive and stores it under a fresh id. */
    @Synchronized
    fun import(bytes: ByteArray): BackupInfo {
        require(bytes.size <= MAX_UPLOAD) { "Backup larger than 64 MB" }
        val entries = bytes.inputStream().use { Tar.readGzip(it) }
        val manifest = entries[MANIFEST] ?: throw IllegalArgumentException("Not a ControlLima backup: manifest.json missing")
        val info = ApiJson.decodeFromString(BackupInfo.serializer(), manifest.decodeToString())
        info.items.forEach { require(entries.containsKey(it.path)) { "Archive is missing ${it.path}" } }
        val id = uniqueId("${STAMP.format(Instant.ofEpochMilli(clock()))}-imported")
        writePrivate(File(dir, "$id.tar.gz"), bytes)
        return info.copy(id = id, sizeBytes = bytes.size.toLong())
    }

    // ---------- diff and restore ----------

    fun diff(id: String, target: String): BackupDiff {
        val (info, entries) = readArchive(file(id))
        val items = info.items.filter { it.target == target }
        if (items.isEmpty()) notFound("Backup $id has no $target")
        val current = sources().associateBy { it.archivePath }
        val lines = items.flatMap { item ->
            val old = entries[item.path]?.decodeToString().orEmpty()
            val now = current[item.path]?.file?.takeIf { it.isFile }?.readText().orEmpty()
            require(old.length < MAX_DIFF && now.length < MAX_DIFF) { "File too large to diff" }
            // Backup on the left, current on the right: "removed" lines would be lost by restoring.
            listOf(controllima.shared.DiffLine(controllima.shared.DiffKind.SAME, "# ${item.path}")) + LineDiff.diff(now, old)
        }
        return BackupDiff(target, lines, items.any { current[it.path] != null })
    }

    /**
     * Writes configs back. A running VM gets "restart required"; a missing Lima VM (or [RestoreRequest.newName])
     * is created stopped through `limactl create` as an action. Confirmation: the VM name for one target
     * (the new name when given), otherwise the backup id.
     */
    fun restore(id: String, request: RestoreRequest, principal: Principal): RestoreResult {
        val (info, entries) = readArchive(file(id))
        require(request.targets.isNotEmpty()) { "Pick at least one target" }
        val known = info.items.map { it.target }.toSet()
        val unknown = request.targets.filter { it !in known }
        require(unknown.isEmpty()) { "Backup $id has no ${unknown.joinToString()}" }
        val expected = when {
            request.newName != null -> request.newName
            request.targets.size == 1 -> Targets.parseVm(request.targets[0])?.name ?: request.targets[0]
            else -> id
        }
        require(request.confirmName == expected) { "Type \"$expected\" to confirm" }
        require(request.newName == null || (request.targets.size == 1 && request.targets[0].startsWith("vm:lima/"))) { "A new name works for one Lima VM only" }

        val restored = mutableListOf<String>()
        val restart = mutableListOf<String>()
        val created = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var actionId: String? = null
        val current = sources().associateBy { it.archivePath }
        for (target in request.targets) {
            val items = info.items.filter { it.target == target }
            val ref = Targets.parseVm(target)
            if (ref != null && ref.source == VmSource.LIMA) {
                val item = items.first()
                val yaml = entries.getValue(item.path)
                val vm = inventory.find(ref)
                val lima = backends.lima(ref.home) ?: throw IllegalArgumentException("Lima home ${ref.home} is not configured")
                if (request.newName != null || vm == null) {
                    val name = request.newName ?: ref.name
                    if (inventory.find(ref.copy(name = name)) != null) conflict("A VM named $name already exists")
                    val file = File(paths.tmp, "restore-$name-${clock()}.yaml")
                    writePrivate(file, yaml)
                    actionId = actions.launch("vm.create", ref.copy(name = name).targetId, principal, mapOf("backup" to id)) { progress ->
                        try {
                            val result = lima.create(name, file.absolutePath, CreateFlags(), progress)
                            check(result.ok) { result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "limactl create failed" }
                            inventory.refresh()
                            "Created $name (stopped)"
                        } finally {
                            file.delete()
                        }
                    }
                    created += name
                } else {
                    val dest = vm.dir?.let { File(it, "lima.yaml") } ?: throw IllegalStateException("VM ${ref.name} has no instance directory")
                    keepPrevious(dest, ref.name)
                    writePrivate(dest, yaml)
                    restored += target
                    if (vm.status == VmStatus.RUNNING || vm.status == VmStatus.UNRESPONSIVE) restart += target
                }
            } else if (ref != null && ref.source == VmSource.COLIMA) {
                val item = items.first()
                val dest = File(backends.colima().configFile(ref.name) ?: throw IllegalStateException("No Colima config path for ${ref.name}"))
                keepPrevious(dest, "colima-${ref.name}")
                writePrivate(dest, entries.getValue(item.path))
                restored += target
                val vm = inventory.find(ref)
                if (vm == null) notes += "Colima profile ${ref.name} does not exist; its config was written, start it yourself with colima start --profile ${ref.name}"
                else if (vm.status == VmStatus.RUNNING || vm.status == VmStatus.UNRESPONSIVE) restart += target
            } else {
                // lima-config and settings: files go back to where they came from.
                for (item in items) {
                    val dest = current[item.path]?.file ?: destinationFor(item.path) ?: continue
                    if (item.path == "controllima/config.json") {
                        val parsed = ApiJson.decodeFromString(Settings.serializer(), entries.getValue(item.path).decodeToString())
                        controllima.server.SettingsStore.validate(parsed)
                        notes += "Settings restored; restart ControlLima to apply them"
                    }
                    keepPrevious(dest, item.path.replace('/', '_'))
                    writePrivate(dest, entries.getValue(item.path))
                }
                restored += target
            }
        }
        return RestoreResult(restored, restart, created, actionId, notes)
    }

    private fun destinationFor(path: String): File? = when {
        path == "controllima/config.json" -> paths.configFile
        path.startsWith("controllima/alerts/") -> File(paths.alerts, path.removePrefix("controllima/alerts/")).takeIf { it.name in setOf("rules.json", "channels.json") }
        path.startsWith("controllima/templates/") -> path.removePrefix("controllima/templates/").takeIf { TEMPLATE_FILE.matches(it) }?.let { File(paths.templates, it) }
        else -> null
    }

    /** The file being overwritten is kept next to the backups, never in the VM directory. */
    private fun keepPrevious(dest: File, label: String) {
        if (!dest.isFile) return
        val keep = File(dir, "pre-restore/${label.replace(Regex("[^A-Za-z0-9_.-]"), "_")}-${clock()}")
        writePrivate(keep, dest.readBytes())
    }

    // ---------- schedule ----------

    /** Runs a scheduled backup when one is due and keeps the newest N scheduled ones. */
    fun runSchedule() {
        val schedule = settings().backupSchedule
        if (!schedule.enabled) return
        val scheduled = list().filter { it.scheduled }
        val last = scheduled.maxOfOrNull { it.createdMillis } ?: 0
        if (clock() - last >= schedule.everyDays * 86_400_000L) {
            runCatching { create(CreateBackupRequest(), scheduled = true) }
        }
        list().filter { it.scheduled }.drop(schedule.keep).forEach { runCatching { delete(it.id) } }
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val SETTINGS = "settings"
        const val LIMA_CONFIG = "lima-config"
        private const val MAX_UPLOAD = 64 * 1024 * 1024
        private const val MAX_DIFF = 512 * 1024
        private val ID = Regex("^[A-Za-z0-9_.-]{1,128}$")
        private val TEMPLATE_FILE = Regex("^[A-Za-z0-9_.-]{1,80}\\.json$")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
