package controllima.server

import controllima.server.http.WsChannel
import controllima.server.http.badRequest
import controllima.server.http.forbidden
import controllima.server.security.AuditLog
import controllima.server.security.Principal
import controllima.shared.Role
import controllima.shared.Settings
import controllima.shared.TerminalScreen
import controllima.shared.Targets
import controllima.shared.VmRef
import controllima.shared.VmStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The opt-in web terminal (docs/pages/vm-detail.md, Terminal): a login shell in a running VM over Lima's
 * own ssh config, relayed as raw text frames. Admin only, off by default, every session audited.
 */
class TerminalService(
    private val backends: Backends,
    private val inventory: Inventory,
    private val audit: AuditLog,
    private val settings: () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = MAX_SESSIONS,
) {
    private val open = AtomicInteger()

    class Request(val ref: VmRef, val rows: Int, val cols: Int)

    /** Hard gates run before the upgrade, so a refused caller never gets a socket at all. */
    fun authorize(principal: Principal, target: String?, rows: String?, cols: String?): Request {
        if (!settings().webTerminal) forbidden("The web terminal is turned off in Settings")
        if (principal.role != Role.ADMIN) forbidden("The web terminal needs the admin role")
        val ref = target?.let { Targets.parseVm(it) } ?: badRequest("target must be a VM")
        val r = rows?.toIntOrNull()?.takeIf { it in TerminalScreen.MIN_ROWS..TerminalScreen.MAX_ROWS } ?: badRequest("Invalid rows")
        val c = cols?.toIntOrNull()?.takeIf { it in TerminalScreen.MIN_COLS..TerminalScreen.MAX_COLS } ?: badRequest("Invalid cols")
        return Request(ref, r, c)
    }

    /**
     * Runs one session until either side ends it. Problems that only show up now (VM stopped, too many
     * sessions) are printed into the terminal, because browsers hide why a WebSocket upgrade failed.
     */
    suspend fun session(ch: WsChannel, principal: Principal, req: Request) {
        val vm = inventory.find(req.ref)
        val ssh = vm?.sshConfigFile
        val problem = when {
            vm == null -> "No VM ${req.ref.name}"
            vm.status != VmStatus.RUNNING -> "${vm.name} is not running; ControlLima never starts a VM on its own"
            ssh == null -> "${vm.name} has no ssh config"
            else -> null
        }
        if (problem != null) return refuse(ch, problem)
        if (open.incrementAndGet() > maxSessions) {
            open.decrementAndGet()
            return refuse(ch, "Too many open terminals (at most $maxSessions)")
        }
        val target = req.ref.targetId
        val started = clock()
        var result = "ok"
        try {
            coroutineScope {
                val output = Channel<String>(capacity = 256)
                // A blocking send pushes back on the ssh reader, so `cat` of a huge file cannot fill memory.
                val process = backends.guest.terminal(ssh!!, backends.limaInstance(vm!!), req.rows, req.cols,
                    onOutput = { output.trySendBlocking(it) }, onExit = { output.close() })
                audit.record(principal.actor, principal.role, "terminal.open", target, mapOf("rows" to "${req.rows}", "cols" to "${req.cols}"))
                try {
                    val pump = launch {
                        for (chunk in output) {
                            val frame = StringBuilder(chunk)
                            while (frame.length < MAX_FRAME) frame.append(output.tryReceive().getOrNull() ?: break)
                            ch.send(frame.toString())
                        }
                        ch.close("The shell exited")
                    }
                    while (true) {
                        val input = ch.receive() ?: break
                        if (input.length > MAX_INPUT) continue
                        if (!process.write(input)) break
                    }
                    pump.cancel()
                } finally {
                    process.close()
                }
            }
        } catch (e: Exception) {
            result = "error: ${e.message ?: e::class.simpleName}"
            throw e
        } finally {
            open.decrementAndGet()
            audit.record(principal.actor, principal.role, "terminal.close", target, result = result, durationMs = clock() - started)
        }
    }

    private suspend fun refuse(ch: WsChannel, message: String) {
        ch.send("\r\n$message\r\n")
        ch.close(message)
    }

    companion object {
        const val MAX_SESSIONS = 4
        private const val MAX_FRAME = 32 * 1024
        /** Keystrokes and pastes; anything larger is dropped rather than typed into a shell. */
        private const val MAX_INPUT = 64 * 1024
    }
}
