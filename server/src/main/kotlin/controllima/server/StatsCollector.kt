package controllima.server

import controllima.providers.docker.DockerEngineProvider
import controllima.providers.docker.DockerStatsRaw
import controllima.providers.lima.GuestSnapshot
import controllima.providers.lima.GuestStatsParser
import controllima.providers.process.StreamHandle
import controllima.shared.Settings
import controllima.shared.StatsSample
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Live load (docs/plan/metrics.md): one persistent SSH session per running VM reading /proc, and Docker
 * stats polling per running container (at most 50). Targets that someone watches are sampled at the live
 * interval, the rest at the background interval. Samples land in a 300-point ring buffer per target.
 */
class StatsCollector(
    private val backends: Backends,
    private val inventory: Inventory,
    private val containers: ContainerInventory,
    private val settings: () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class VmStream(val intervalSeconds: Int, val startedAt: Long) {
        lateinit var handle: StreamHandle
        @Volatile var prev: GuestSnapshot? = null
        @Volatile var prevAt: Long = 0
    }

    private class ContainerPoll(@Volatile var prev: DockerStatsRaw? = null, @Volatile var prevAt: Long = 0, @Volatile var nextAt: Long = 0)

    private val flow = MutableSharedFlow<StatsSample>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val samples: SharedFlow<StatsSample> get() = flow

    private val buffers = ConcurrentHashMap<String, ArrayDeque<StatsSample>>()
    private val watchers = ConcurrentHashMap<String, Int>()
    private val vmStreams = ConcurrentHashMap<String, VmStream>()
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val polls = ConcurrentHashMap<String, ContainerPoll>()

    fun latest(target: String): StatsSample? = buffers[target]?.let { synchronized(it) { it.lastOrNull() } }

    fun history(target: String): List<StatsSample> = buffers[target]?.let { synchronized(it) { it.toList() } }.orEmpty()

    fun latestAll(): Map<String, StatsSample> = buffers.mapNotNull { (k, v) -> synchronized(v) { v.lastOrNull() }?.let { k to it } }.toMap()

    /** Marks [targets] as watched until the returned handle is closed. */
    fun watch(targets: Collection<String>): Closeable {
        targets.forEach { t -> watchers.merge(t, 1, Int::plus) }
        return Closeable { targets.forEach { t -> watchers.computeIfPresent(t) { _, n -> if (n <= 1) null else n - 1 } } }
    }

    private fun watched(target: String) = (watchers[target] ?: 0) > 0

    fun publish(sample: StatsSample) {
        val buffer = buffers.getOrPut(sample.targetId) { ArrayDeque() }
        synchronized(buffer) {
            buffer.addLast(sample)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
        flow.tryEmit(sample)
    }

    fun forget(target: String) {
        buffers.remove(target)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            while (isActive) {
                runCatching { reconcileVms() }
                delay(2_000)
            }
        }
        while (isActive) {
            runCatching { pollContainers() }
            delay(1_000)
        }
    }

    fun stop() {
        vmStreams.values.forEach { runCatching { it.handle.close() } }
        vmStreams.clear()
    }

    // ---------- VMs ----------

    private fun reconcileVms() {
        val s = settings()
        val running = inventory.snapshot.value.vms.filter { it.status == VmStatus.RUNNING }.associateBy { it.ref.targetId }
        // Close sessions of VMs that stopped or went unresponsive (no samples for those, see metrics.md).
        for ((target, stream) in vmStreams) {
            if (target !in running || !stream.handle.alive) {
                vmStreams.remove(target)?.handle?.close()
            }
        }
        for ((target, vm) in running) {
            val interval = if (watched(target)) s.liveIntervalSeconds else s.backgroundIntervalSeconds
            val existing = vmStreams[target]
            if (existing != null) {
                // Restart with the new interval, but not more often than every 10 s.
                if (existing.intervalSeconds != interval && clock() - existing.startedAt > 10_000) vmStreams.remove(target)?.handle?.close()
                else continue
            }
            val (count, until) = failures[target] ?: (0 to 0L)
            if (clock() < until) continue
            val cfg = vm.sshConfigFile ?: continue
            val instance = instanceName(vm) ?: continue
            val stream = runCatching { openVmStream(target, vm, cfg, instance, interval) }.getOrNull()
            if (stream == null) failures[target] = (count + 1) to clock() + backoff(count + 1)
        }
    }

    private fun instanceName(vm: Vm): String? = when (vm.source) {
        VmSource.LIMA -> vm.name
        VmSource.COLIMA -> runCatching { backends.colima().limaInstance(vm.name) }.getOrNull()
    }

    private fun openVmStream(target: String, vm: Vm, cfg: String, instance: String, interval: Int): VmStream {
        val holder = VmStream(interval, clock())
        holder.handle = backends.guest.statsStream(cfg, instance, interval, onSnapshot = { snap ->
            val now = clock()
            val sample = GuestStatsParser.sample(target, holder.prev, snap, now - holder.prevAt, now)
            holder.prev = snap
            holder.prevAt = now
            failures.remove(target)
            publish(withHost(sample, vm))
        }, onExit = {
            val (count, _) = failures[target] ?: (0 to 0L)
            failures[target] = (count + 1) to clock() + backoff(count + 1)
        })
        vmStreams[target] = holder
        return holder
    }

    private fun backoff(failures: Int): Long = minOf(60_000L, 5_000L shl minOf(failures - 1, 4))

    private fun withHost(sample: StatsSample, vm: Vm): StatsSample {
        val host = vm.hostPid?.let { runCatching { backends.host.processStats(it) }.getOrNull() }
        return sample.copy(
            hostCpuPercent = host?.cpuPercent,
            hostRssBytes = host?.rssBytes,
            probeLatencyMs = inventory.probeLatency(vm.ref.targetId),
        )
    }

    // ---------- containers ----------

    private suspend fun pollContainers() {
        val s = settings()
        val lists = containers.all(maxAgeMillis = s.backgroundIntervalSeconds * 1000L)
        val running = lists.flatMap { it.containers }.filter { it.running }.take(MAX_CONTAINERS)
        val ids = running.map { it.targetId }.toSet()
        polls.keys.retainAll(ids)
        val now = clock()
        for (c in running) {
            val poll = polls.getOrPut(c.targetId) { ContainerPoll() }
            if (now < poll.nextAt) continue
            val interval = (if (watched(c.targetId)) s.liveIntervalSeconds else s.backgroundIntervalSeconds) * 1000L
            poll.nextAt = now + interval
            val docker = containers.docker(c.profile) ?: continue
            val raw = withContext(Dispatchers.IO) { runCatching { docker.stats(c.id) }.getOrNull() } ?: continue
            val at = clock()
            val sample = DockerEngineProvider.sample(c.targetId, poll.prev, raw, at - poll.prevAt, at)
            poll.prev = raw
            poll.prevAt = at
            publish(sample)
        }
    }

    companion object {
        /** 10 minutes at 2 s. */
        const val CAPACITY = 300
        const val MAX_CONTAINERS = 50
    }
}
