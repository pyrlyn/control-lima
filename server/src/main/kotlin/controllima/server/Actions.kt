package controllima.server

import controllima.server.security.AuditLog
import controllima.server.security.Principal
import controllima.shared.ActionState
import controllima.shared.ActionStatus
import controllima.shared.EventType
import controllima.shared.ServerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Fan-out of [ServerEvent]s to `/ws/events` and in-process listeners. Slow consumers lose old events. */
class EventBus {
    private val flow = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ServerEvent> get() = flow

    fun emit(event: ServerEvent) {
        flow.tryEmit(event)
    }
}

/**
 * Long-running actions (start, stop, pull, restore, create): `202 {actionId}`, progress on `/ws/events`,
 * final state at `GET /api/actions/{id}` (docs/plan/api.md). One action per target at a time.
 */
class ActionManager(
    private val scope: CoroutineScope,
    private val events: EventBus,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val actions = ConcurrentHashMap<String, ActionStatus>()
    private val running = ConcurrentHashMap<String, String>()
    private val userActions = ConcurrentHashMap<String, Long>()

    class Busy(target: String) : RuntimeException("Another action is already running on $target")

    /**
     * Starts [block] in the background. [block] gets a progress callback and returns an optional
     * final message; throwing marks the action failed with the exception message.
     */
    fun launch(
        kind: String,
        target: String,
        principal: Principal,
        params: Map<String, String> = emptyMap(),
        block: suspend (progress: (String) -> Unit) -> String?,
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        if (running.putIfAbsent(target, id) != null) throw Busy(target)
        markUserAction(target)
        val started = clock()
        actions[id] = ActionStatus(id, kind, target, ActionState.RUNNING, started)
        prune()
        scope.launch(Dispatchers.IO) {
            val progress: (String) -> Unit = { line ->
                val clean = line.take(500)
                actions.computeIfPresent(id) { _, s -> s.copy(progress = (s.progress + clean).takeLast(MAX_LINES)) }
                events.emit(ServerEvent(EventType.ACTION_PROGRESS, clock(), target, clean, actionId = id))
            }
            val result = runCatching { block(progress) }
            val finished = clock()
            val status = actions.computeIfPresent(id) { _, s ->
                s.copy(
                    state = if (result.isSuccess) ActionState.SUCCEEDED else ActionState.FAILED,
                    finishedMillis = finished,
                    error = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
                )
            }
            running.remove(target, id)
            markUserAction(target)
            val message = result.fold({ it ?: "$kind finished" }, { "$kind failed: ${it.message}" })
            events.emit(ServerEvent(EventType.ACTION_DONE, finished, target, message, actionId = id))
            audit.record(principal.actor, principal.role, kind, target, params, if (result.isSuccess) "ok" else "error: ${status?.error}", finished - started)
        }
        return id
    }

    fun get(id: String): ActionStatus? = actions[id]

    fun isBusy(target: String): Boolean = running.containsKey(target)

    /** Records a user-initiated change, so "VM down" alerts can tell it from a crash. */
    fun markUserAction(target: String) {
        userActions[target] = clock()
    }

    fun recentUserAction(target: String, withinMillis: Long = 10 * 60_000): Boolean =
        userActions[target]?.let { clock() - it <= withinMillis } ?: false

    private fun prune() {
        if (actions.size <= MAX_ACTIONS) return
        actions.values.filter { it.state != ActionState.RUNNING }.sortedBy { it.startedMillis }
            .take(actions.size - MAX_ACTIONS).forEach { actions.remove(it.id) }
    }

    companion object {
        const val MAX_LINES = 200
        const val MAX_ACTIONS = 200
    }
}
