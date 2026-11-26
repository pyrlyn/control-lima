package controllima.server

import controllima.providers.docker.DockerProvider
import controllima.shared.Container
import controllima.shared.ContainerList
import controllima.shared.Names
import controllima.shared.EventType
import controllima.shared.ServerEvent
import controllima.shared.Settings
import controllima.shared.SourceError
import controllima.shared.Vm
import controllima.shared.VmList
import controllima.shared.VmRef
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Lima and Colima VMs from all homes and profiles, refreshed in the background. Probes run on their
 * own coroutine per running VM, never on the request path; a Running VM that does not answer is
 * reported as UNRESPONSIVE (docs/plan/architecture.md, "VM state model").
 */
class Inventory(
    private val backends: Backends,
    private val settings: () -> Settings,
    private val events: EventBus,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class ProbeState(
        val ok: Boolean,
        val since: Long,
        val lastProbe: Long,
        val latencyMs: Double?,
        val failures: Int,
    )

    private val probes = ConcurrentHashMap<String, ProbeState>()
    private val probing = ConcurrentHashMap.newKeySet<String>()
    private val refreshLock = Mutex()
    private val state = MutableStateFlow(VmList(emptyList()))
    @Volatile private var refreshedAt = 0L

    val snapshot: StateFlow<VmList> get() = state

    /** Listeners for status changes (old status null for a new VM, new status null when it disappeared). */
    private val listeners = mutableListOf<(Vm?, Vm?) -> Unit>()

    fun onChange(listener: (old: Vm?, new: Vm?) -> Unit) = synchronized(listeners) { listeners += listener }

    suspend fun vms(maxAgeMillis: Long = 3_000): VmList =
        if (clock() - refreshedAt <= maxAgeMillis && refreshedAt > 0) state.value else refresh()

    fun find(ref: VmRef): Vm? = state.value.vms.firstOrNull { it.source == ref.source && it.name == ref.name && it.home == ref.home }

    fun probeLatency(target: String): Double? = probes[target]?.takeIf { it.ok }?.latencyMs

    suspend fun refresh(): VmList = refreshLock.withLock {
        val (lima, colima) = coroutineScope {
            val limaJobs = backends.limaProviders().map { provider ->
                async(Dispatchers.IO) {
                    runCatching { provider.list() }.fold(
                        { it to null },
                        { emptyList<Vm>() to SourceError(provider.home?.let { "lima:$it" } ?: "lima", it.message ?: "limactl failed") },
                    )
                }
            }
            val colimaJob = async(Dispatchers.IO) {
                runCatching { backends.colima().list().map(::enrichColima) }.fold(
                    { it to null },
                    { emptyList<Vm>() to SourceError("colima", it.message ?: "colima failed") },
                )
            }
            limaJobs.awaitAll() to colimaJob.await()
        }
        val errors = (lima.mapNotNull { it.second } + listOfNotNull(colima.second)).filterNot { isMissingTool(it) }
        val raw = lima.flatMap { it.first } + colima.first
        val now = clock()
        val merged = raw.map { applyProbe(it, now) }
        val old = state.value.vms.associateBy { it.ref.targetId }
        val result = VmList(merged, errors)
        state.value = result
        refreshedAt = now
        // Probe state of VMs that are gone or no longer running is dropped, so a restart starts clean.
        val runningIds = merged.filter { it.status == VmStatus.RUNNING || it.status == VmStatus.UNRESPONSIVE }.map { it.ref.targetId }.toSet()
        probes.keys.retainAll(runningIds)
        notifyChanges(old, merged.associateBy { it.ref.targetId })
        result
    }

    /** A missing `colima` binary is not an error on a Lima-only machine. */
    private fun isMissingTool(error: SourceError) =
        error.source == "colima" && (error.message.contains("No such file") || error.message.contains("error=2"))

    private fun applyProbe(vm: Vm, now: Long): Vm {
        if (vm.status != VmStatus.RUNNING) return vm
        val target = vm.ref.targetId
        val probe = probes[target]
        val haDead = vm.source == VmSource.LIMA && vm.dir?.let { hostAgentSaysDead(File(it, "ha.stderr.log")) } == true
        return when {
            probe != null && !probe.ok -> vm.copy(status = VmStatus.UNRESPONSIVE, unresponsiveSinceMillis = probe.since, message = "No answer to probe for ${(now - probe.since) / 1000} s")
            haDead -> vm.copy(status = VmStatus.UNRESPONSIVE, unresponsiveSinceMillis = now, message = "Host agent reports the VM is no longer live")
            else -> vm
        }
    }

    private fun notifyChanges(old: Map<String, Vm>, new: Map<String, Vm>) {
        val ls = synchronized(listeners) { listeners.toList() }
        for ((id, vm) in new) {
            val before = old[id]
            if (before?.status != vm.status) {
                if (before != null) events.emit(ServerEvent(EventType.VM_STATE, clock(), id, "${vm.name}: ${before.status} → ${vm.status}"))
                ls.forEach { runCatching { it(before, vm) } }
            }
        }
        for ((id, vm) in old) if (id !in new) {
            events.emit(ServerEvent(EventType.VM_STATE, clock(), id, "${vm.name} was removed"))
            ls.forEach { runCatching { it(vm, null) } }
        }
    }

    /** Colima VMs are Lima instances; fill in what `colima list` does not report. */
    private fun enrichColima(vm: Vm): Vm {
        val colima = backends.colima()
        val dir = runCatching { colima.instanceDir(vm.name) }.getOrNull()
        val pid = dir?.let { pidFrom(File(it, "qemu.pid")) ?: pidFrom(File(it, "ha.pid")) }
        return vm.copy(
            dir = dir,
            sshConfigFile = runCatching { colima.sshConfigFile(vm.name) }.getOrNull(),
            hostPid = vm.hostPid ?: pid,
        )
    }

    private fun pidFrom(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()?.takeIf { it > 0 }

    /** Starts the refresh and probe loops. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            while (isActive) {
                runCatching { refresh() }
                delay(settings().backgroundIntervalSeconds.seconds)
            }
        }
        while (isActive) {
            delay(1_000)
            for (vm in state.value.vms) {
                if (vm.status != VmStatus.RUNNING && vm.status != VmStatus.UNRESPONSIVE) continue
                val target = vm.ref.targetId
                val p = probes[target]
                if (p != null && clock() - p.lastProbe < interval(p)) continue
                if (!probing.add(target)) continue
                launch(Dispatchers.IO) {
                    try {
                        probe(vm)
                    } finally {
                        probing.remove(target)
                    }
                }
            }
        }
    }

    /** Background interval while healthy; backoff up to 60 s while a VM keeps failing. */
    private fun interval(p: ProbeState): Long {
        val base = settings().backgroundIntervalSeconds * 1000L
        return if (p.ok) base else minOf(60_000L, 5_000L shl minOf(p.failures - 1, 4))
    }

    suspend fun probe(vm: Vm) {
        val timeout = settings().probeTimeoutSeconds.seconds
        val target = vm.ref.targetId
        val started = System.nanoTime()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                when (vm.source) {
                    VmSource.LIMA -> backends.lima(vm.home)?.probeTimed(vm.name, timeout)?.ok ?: false
                    VmSource.COLIMA -> backends.colima().probe(vm.name, timeout)
                }
            }.getOrDefault(false)
        }
        val latency = (System.nanoTime() - started) / 1e6
        val now = clock()
        val previous = probes[target]
        val next = when {
            ok -> ProbeState(true, if (previous?.ok == true) previous.since else now, now, latency, 0)
            previous != null && !previous.ok -> previous.copy(lastProbe = now, failures = previous.failures + 1, latencyMs = null)
            else -> ProbeState(false, now, now, null, 1)
        }
        probes[target] = next
        if (previous?.ok != next.ok) {
            // State flipped: re-merge without waiting for the next CLI refresh.
            val vms = state.value.vms.map { if (it.ref.targetId == target && (it.status == VmStatus.RUNNING || it.status == VmStatus.UNRESPONSIVE)) applyProbe(it.copy(status = VmStatus.RUNNING, unresponsiveSinceMillis = null, message = null), now) else it }
            val old = state.value.vms.associateBy { it.ref.targetId }
            state.value = state.value.copy(vms = vms)
            notifyChanges(old, vms.associateBy { it.ref.targetId })
        }
    }

    companion object {
        /** Reads the tail of the host agent log; the last lines decide, so an old message after a restart does not count. */
        fun hostAgentSaysDead(log: File): Boolean {
            if (!log.isFile) return false
            val tail = tailText(log, 4096).lines().filter { it.isNotBlank() }.takeLast(5)
            return tail.any { it.contains("no longer live", ignoreCase = true) }
        }

        fun tailText(file: File, bytes: Int): String = RandomAccessFile(file, "r").use { raf ->
            val start = maxOf(0L, raf.length() - bytes)
            raf.seek(start)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.readFully(buf)
            buf.decodeToString()
        }
    }
}

/** Containers per Colima profile, cached briefly; emits CONTAINER_STATE events on changes. */
class ContainerInventory(
    private val backends: Backends,
    private val events: EventBus,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nerdctl: NerdctlProfiles? = null,
) {
    private val cache = ConcurrentHashMap<String, Pair<Long, ContainerList>>()
    private val listeners = mutableListOf<(Container?, Container?) -> Unit>()

    fun onChange(listener: (old: Container?, new: Container?) -> Unit) = synchronized(listeners) { listeners += listener }

    fun docker(profile: String): DockerProvider? =
        if (profile.startsWith(Names.NERDCTL_PREFIX)) nerdctl?.docker(profile) else backends.docker(profile)

    /** Colima profiles, then Lima VMs whose guest has nerdctl. */
    suspend fun profiles(): List<String> = backends.colimaProfiles() + nerdctl?.profiles().orEmpty()

    suspend fun list(profile: String, maxAgeMillis: Long = 2_000): ContainerList {
        cache[profile]?.let { (at, value) -> if (clock() - at <= maxAgeMillis) return value }
        val docker = docker(profile)
        val result = if (docker == null) {
            val reason = if (profile.startsWith(Names.NERDCTL_PREFIX)) {
                "nerdctl is not available in Lima VM ${profile.removePrefix(Names.NERDCTL_PREFIX)} (is it running?)"
            } else {
                "Docker socket not found at ${backends.dockerSocket(profile)} (is the Colima profile running?)"
            }
            ContainerList(profile, emptyList(), reason, unavailable = true)
        } else {
            withContext(Dispatchers.IO) {
                runCatching { ContainerList(profile, docker.containers()) }
                    .getOrElse { ContainerList(profile, emptyList(), it.message ?: "Docker API failed", unavailable = true) }
            }
        }
        val before = cache.put(profile, clock() to result)?.second
        if (before != null && !result.unavailable && !before.unavailable) diff(before.containers, result.containers)
        return result
    }

    /** All profiles with an available socket. */
    suspend fun all(maxAgeMillis: Long = 2_000): List<ContainerList> = profiles().map { list(it, maxAgeMillis) }

    fun cached(): List<Container> = cache.values.flatMap { it.second.containers }

    fun invalidate(profile: String) {
        cache.remove(profile)
    }

    private fun diff(old: List<Container>, new: List<Container>) {
        val before = old.associateBy { it.id }
        val ls = synchronized(listeners) { listeners.toList() }
        for (c in new) {
            val b = before[c.id]
            if (b != null && b.state != c.state) {
                events.emit(ServerEvent(EventType.CONTAINER_STATE, clock(), c.targetId, "${c.name}: ${b.state} → ${c.state}"))
                ls.forEach { runCatching { it(b, c) } }
            } else if (b == null) {
                ls.forEach { runCatching { it(null, c) } }
            }
        }
        val ids = new.map { it.id }.toSet()
        old.filter { it.id !in ids }.forEach { gone -> ls.forEach { runCatching { it(gone, null) } } }
    }
}
