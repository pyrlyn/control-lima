package controllima.cli

import controllima.server.ControlLimaServer
import controllima.server.ServerConfig
import controllima.server.Services
import controllima.server.security.Principal
import controllima.server.security.Secrets
import controllima.server.security.TokenStore
import controllima.shared.ActionState
import controllima.shared.ApiJson
import controllima.shared.Container
import controllima.shared.ContainerList
import controllima.shared.CreateBackupRequest
import controllima.shared.Format
import controllima.shared.RestoreRequest
import controllima.shared.Role
import controllima.shared.StatsSample
import controllima.shared.Vm
import controllima.shared.VmList
import controllima.shared.VmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.PrintStream

/** Subcommands from docs/plan/cli-tui.md. They use the same services as the server, in this process. */
class Commands(private val options: CliOptions, private val out: PrintStream = System.out) {
    // CLI writes are audited like API writes; the local user is the admin of their own machine.
    private val principal = Principal("cli", Role.ADMIN, "cli", Principal.Via.LAUNCH_TOKEN)

    fun run(): Int = when (options.command) {
        "status" -> status()
        "token" -> token()
        "backup" -> withServices { backup(it) }
        "restore" -> withServices { restore(it) }
        "top" -> withServices { top(it) }
        else -> throw UsageError("Unknown command ${options.command}")
    }

    private fun <T> withServices(block: (Services) -> T): T {
        // The HTTP server is built but never started; only the services are used.
        val server = ControlLimaServer(ServerConfig(port = options.port, token = Secrets.randomHex(24)), paths(options), overrides(options))
        return server.use { block(it.services) }
    }

    // ---------- status ----------

    @Serializable
    data class Status(val vms: VmList, val containers: List<ContainerList>)

    private fun status(): Int {
        val args = CommandArgs(options.commandArgs, emptySet(), setOf("--json"))
        if (args.positionals.isNotEmpty()) throw UsageError("status takes no arguments")
        val status = withServices { s -> runBlocking { Status(s.inventory.refresh(), s.containers.all(0)) } }
        if ("--json" in args.flags) {
            out.println(ApiJson.encodeToString(Status.serializer(), status))
        } else {
            out.print(renderStatus(status))
        }
        return if (limaUnavailable(status.vms)) Exit.UNAVAILABLE else Exit.OK
    }

    // ---------- token ----------

    private fun token(): Int {
        val sub = options.commandArgs.firstOrNull() ?: throw UsageError("token create|list|revoke")
        val args = CommandArgs(options.commandArgs.drop(1), setOf("--name", "--role", "--expires"))
        val paths = paths(options).also { it.ensure() }
        val store = TokenStore(paths.tokens)
        when (sub) {
            "create" -> {
                val name = args.value("--name") ?: throw UsageError("token create needs --name")
                val role = args.value("--role")?.let { r -> Role.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } }
                    ?: throw UsageError("--role must be viewer, operator or admin")
                val days = args.value("--expires")?.let(::parseDays)
                val created = store.create(name, role, days)
                // The secret is shown once; only its hash is stored.
                out.println(created.token)
                System.err.println("Token ${created.info.name} (${created.info.role.name.lowercase()}) created; it is shown only once.")
            }
            "list" -> {
                val rows = store.list().map {
                    listOf(it.id, it.name, it.role.name.lowercase(), Format.timestamp(it.createdMillis), Format.timestamp(it.expiresMillis), Format.timestamp(it.lastUsedMillis))
                }
                out.print(table(listOf("ID", "NAME", "ROLE", "CREATED", "EXPIRES", "LAST USED"), rows))
            }
            "revoke" -> {
                val target = args.positionals.singleOrNull() ?: throw UsageError("token revoke <id|name>")
                if (!store.revoke(target)) throw IllegalStateException("No token $target")
                out.println("Revoked $target")
            }
            else -> throw UsageError("token create|list|revoke")
        }
        return Exit.OK
    }

    // ---------- backup and restore ----------

    private fun backup(s: Services): Int {
        val args = CommandArgs(options.commandArgs, emptySet(), setOf("--redact"))
        val vms = runBlocking { s.inventory.refresh() }
        if (limaUnavailable(vms) && vms.vms.isEmpty()) throw UnavailableError(vms.errors.joinToString { "${it.source}: ${it.message}" })
        val targets = args.positionals.map { name -> resolveVm(vms, name).ref.targetId }
        val info = s.backups.create(CreateBackupRequest(targets = targets, redactSecrets = "--redact" in args.flags))
        s.audit.record(principal.actor, principal.role, "cli backup", targets.joinToString().ifEmpty { "all" }, mapOf("id" to info.id))
        out.println(info.id)
        System.err.println("${info.items.size} files, ${Format.bytes(info.sizeBytes)} in ${s.paths.backups}")
        val findings = info.items.sumOf { it.secretsFlagged.size }
        if (findings > 0 && !info.redacted) System.err.println("Warning: $findings possible secrets stored as-is (use --redact to mask them)")
        return Exit.OK
    }

    private fun restore(s: Services): Int {
        val args = CommandArgs(options.commandArgs, setOf("--target", "--confirm", "--new-name"))
        val id = args.positionals.singleOrNull() ?: throw UsageError("restore <backup-id> --target <vm> --confirm <name>")
        val vms = runBlocking { s.inventory.refresh() }
        val targets = args.all("--target").map { t -> if (t.contains(':')) t else resolveVm(vms, t, mustExist = false).ref.targetId }
        if (targets.isEmpty()) throw UsageError("restore needs at least one --target")
        val confirm = args.value("--confirm") ?: throw UsageError("restore needs --confirm (the VM name, the new name, or the backup id for several targets)")
        val result = s.backups.restore(id, RestoreRequest(targets, confirm, args.value("--new-name")), principal)
        s.audit.record(principal.actor, principal.role, "cli restore", targets.joinToString(), mapOf("id" to id))
        result.restored.forEach { out.println("restored $it") }
        result.restartRequired.forEach { out.println("restart required: $it") }
        result.notes.forEach { out.println(it) }
        val actionId = result.actionId ?: return Exit.OK
        // `limactl create` runs as an action; the CLI waits for it because exiting would cancel it.
        var printed = 0
        while (true) {
            val status = s.actions.get(actionId) ?: throw IllegalStateException("Action $actionId disappeared")
            status.progress.drop(printed).forEach { out.println(it) }
            printed = status.progress.size
            when (status.state) {
                ActionState.RUNNING -> Thread.sleep(500)
                ActionState.SUCCEEDED -> {
                    result.created.forEach { out.println("created $it (stopped)") }
                    return Exit.OK
                }
                ActionState.FAILED -> throw IllegalStateException(status.error ?: "restore failed")
            }
        }
    }

    // ---------- top ----------

    private fun top(s: Services): Int {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        s.inventory.start(scope)
        s.stats.start(scope)
        var watch: Closeable? = null
        Runtime.getRuntime().addShutdownHook(Thread { out.print(SHOW_CURSOR) })
        out.print(HIDE_CURSOR)
        try {
            runBlocking {
                while (true) {
                    val vms = s.inventory.vms()
                    val containers = s.containers.all(5_000)
                    val running = vms.vms.filter { it.status == VmStatus.RUNNING }.map { it.ref.targetId } +
                        containers.flatMap { list -> list.containers.filter { it.running }.map { it.targetId } }
                    // Re-watch every frame so new VMs and containers start streaming.
                    val next = s.stats.watch(running)
                    watch?.close()
                    watch = next
                    out.print(CLEAR + renderTop(vms, containers, s.stats.latestAll(), System.currentTimeMillis()))
                    out.flush()
                    delay(2_000)
                }
            }
        } finally {
            watch?.close()
            s.stats.stop()
            scope.cancel()
            out.print(SHOW_CURSOR)
        }
        @Suppress("UNREACHABLE_CODE")
        return Exit.OK
    }

    companion object {
        private const val CLEAR = "\u001b[H\u001b[2J"
        private const val HIDE_CURSOR = "\u001b[?25l"
        private const val SHOW_CURSOR = "\u001b[?25h"

        fun parseDays(text: String): Int {
            val n = text.removeSuffix("d").toIntOrNull()
            if (n == null || n !in 1..3650) throw UsageError("--expires must look like 30d (1..3650 days)")
            return n
        }

        /** Every Lima home failed: the tool is missing or broken, which is exit code 3. */
        fun limaUnavailable(list: VmList): Boolean = list.errors.any { it.source.startsWith("lima") } &&
            list.vms.none { it.source == controllima.shared.VmSource.LIMA }

        fun resolveVm(list: VmList, name: String, mustExist: Boolean = true): Vm {
            val matches = list.vms.filter { it.name == name || it.ref.targetId == name }
            return when {
                matches.size == 1 -> matches[0]
                matches.size > 1 -> throw UsageError("$name is ambiguous: ${matches.joinToString { it.ref.targetId }}")
                mustExist -> throw IllegalStateException("No VM $name")
                else -> Vm(name, controllima.shared.VmSource.LIMA, VmStatus.UNKNOWN)
            }
        }

        fun renderStatus(status: Status): String = buildString {
            val vmRows = status.vms.vms.map { vm ->
                listOf(vm.name, vm.source.slug, vm.status.name.lowercase(), vm.cpus?.toString() ?: "—", Format.bytes(vm.memoryBytes), Format.bytes(vm.diskBytes), vm.home ?: "")
            }
            append(table(listOf("VM", "SOURCE", "STATUS", "CPUS", "MEMORY", "DISK", "HOME"), vmRows))
            status.vms.errors.forEach { append("! ${it.source}: ${it.message}\n") }
            for (list in status.containers) {
                append('\n')
                if (list.error != null) {
                    append("Colima ${list.profile}: ${list.error}\n")
                    continue
                }
                val rows = list.containers.map { c -> listOf(c.name, c.image, c.state, c.status, ports(c)) }
                append(table(listOf("CONTAINER (${list.profile})", "IMAGE", "STATE", "STATUS", "PORTS"), rows))
            }
        }

        fun renderTop(vms: VmList, containers: List<ContainerList>, samples: Map<String, StatsSample>, now: Long): String = buildString {
            append("ControlLima top — ${Format.timestamp(now)} — Ctrl-C to quit\n\n")
            val vmRows = vms.vms.map { vm ->
                val s = samples[vm.ref.targetId]
                listOf(vm.name, vm.status.name.lowercase(), Format.percent(s?.cpuPercent), memory(s), Format.rate(s?.netRxBytesPerSec), Format.rate(s?.netTxBytesPerSec))
            }
            append(table(listOf("VM", "STATUS", "CPU", "MEMORY", "NET IN", "NET OUT"), vmRows))
            val rows = containers.flatMap { list -> list.containers.filter { it.running }.map { list.profile to it } }.map { (profile, c) ->
                val s = samples[c.targetId]
                listOf("${c.name} ($profile)", Format.percent(s?.cpuPercent), memory(s), Format.rate(s?.netRxBytesPerSec), Format.rate(s?.netTxBytesPerSec))
            }
            if (rows.isNotEmpty()) {
                append('\n')
                append(table(listOf("CONTAINER", "CPU", "MEMORY", "NET IN", "NET OUT"), rows))
            }
        }

        private fun memory(s: StatsSample?): String {
            val used = s?.memoryUsedBytes ?: return "—"
            return Format.bytes(used) + (s.memoryTotalBytes?.let { " / ${Format.bytes(it)}" } ?: "")
        }

        private fun ports(c: Container): String = c.ports.filter { it.publicPort != null }.joinToString(", ") { "${it.publicPort}->${it.privatePort}" }

        fun table(header: List<String>, rows: List<List<String>>): String {
            val widths = header.indices.map { i -> (rows.map { it[i].length } + header[i].length).max() }
            fun line(cells: List<String>) = cells.mapIndexed { i, c -> if (i == cells.lastIndex) c else c.padEnd(widths[i]) }.joinToString("  ").trimEnd() + "\n"
            return line(header) + rows.joinToString("") { line(it) }
        }
    }
}
