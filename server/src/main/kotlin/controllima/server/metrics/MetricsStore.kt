package controllima.server.metrics

import controllima.server.privateDir
import controllima.server.security.Secrets
import controllima.shared.Metric
import controllima.shared.MetricPoint
import controllima.shared.MetricSeries
import controllima.shared.StatsSample
import controllima.shared.TimeRange
import controllima.shared.Trend
import controllima.shared.TrendsReport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Metrics history (docs/plan/metrics.md). The interface keeps the storage engine replaceable. */
interface MetricsStore {
    fun append(sample: StatsSample)
    fun markGap(target: String, fromMillis: Long, toMillis: Long)
    fun series(target: String, metric: String, fromMillis: Long, toMillis: Long, stepMillis: Long? = null): MetricSeries
    fun trends(target: String? = null): TrendsReport
    fun targets(): List<String>
    fun forget(target: String)
    fun sizeBytes(): Long
    /** Closes finished buckets, flushes, and (hourly) purges by retention. */
    fun maintain()
    fun close()
}

data class Retention(val rawHours: Int = 24, val m1Days: Int = 7, val m15Days: Int = 30, val h1Days: Int = 365)

enum class Tier(val fileName: String, val resolutionMillis: Long) {
    RAW("raw.bin", 0), M1("1m.bin", 60_000), M15("15m.bin", 900_000), H1("1h.bin", 3_600_000);

    fun retentionMillis(r: Retention): Long = when (this) {
        RAW -> r.rawHours * 3_600_000L
        M1 -> r.m1Days * 86_400_000L
        M15 -> r.m15Days * 86_400_000L
        H1 -> r.h1Days * 86_400_000L
    }
}

/** One stored point: bucket start, metric index in [Metric.all], avg, max, p95 (NaN when unknown). */
class MetricRecord(val t: Long, val metric: Int, val avg: Double, val max: Double, val p95: Double)

/**
 * Downsampling, querying and trends shared by every storage engine. Downsampling is streaming
 * (raw → 1 min → 15 min → 1 h) with open buckets kept in memory and closed by [maintain]; subclasses
 * only persist and read records.
 */
abstract class TieredMetricsStore(
    private val retention: () -> Retention,
    private val clock: () -> Long,
) : MetricsStore {
    private class Bucket(val start: Long) {
        var sum = 0.0
        var count = 0
        var max = Double.NEGATIVE_INFINITY
        val values = ArrayList<Double>()
    }

    /** Open bucket per target and (tier, metric) for M1, M15, H1. */
    private val open = ConcurrentHashMap<String, HashMap<Pair<Tier, Int>, Bucket>>()
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var lastPurge = 0L
    @Volatile private var trendsCache: Pair<Long, Map<String, Trend>>? = null

    protected abstract fun store(target: String, tier: Tier, records: List<MetricRecord>)
    protected abstract fun load(target: String, tier: Tier, metric: Int, fromMillis: Long, toMillis: Long, onRecord: (MetricRecord) -> Unit)
    protected abstract fun storeGap(target: String, fromMillis: Long, toMillis: Long)
    protected abstract fun loadGaps(target: String, fromMillis: Long, toMillis: Long): List<TimeRange>
    protected abstract fun storedTargets(): Set<String>
    protected abstract fun drop(target: String)
    /** Deletes records older than each tier's cutoff and returns the targets with nothing left. */
    protected abstract fun purge(cutoffs: Map<Tier, Long>): Set<String>
    /** Makes buffered writes of [target] readable; called under that target's lock. */
    protected open fun flush(target: String) {}

    protected fun lock(target: String): Any = locks.getOrPut(target) { Any() }

    override fun append(sample: StatsSample) {
        val values = Metric.valuesOf(sample)
        if (values.isEmpty()) return
        val target = sample.targetId
        synchronized(lock(target)) {
            val out = Writes()
            for ((metric, value) in values) {
                val index = Metric.all.indexOf(metric)
                if (index < 0 || value.isNaN()) continue
                out.add(Tier.RAW, MetricRecord(sample.epochMillis, index, value, value, Double.NaN))
                feed(target, out, Tier.M1, index, sample.epochMillis, value, value)
            }
            out.flushTo(target)
        }
    }

    private class Writes {
        val byTier = LinkedHashMap<Tier, MutableList<MetricRecord>>()
        fun add(tier: Tier, r: MetricRecord) = byTier.getOrPut(tier) { ArrayList() }.add(r)
    }

    private fun Writes.flushTo(target: String) = byTier.forEach { (tier, records) -> if (records.isNotEmpty()) store(target, tier, records) }

    /** Adds a value to the open bucket of [tier], closing it (and cascading) when time moved past it. */
    private fun feed(target: String, out: Writes, tier: Tier, metric: Int, t: Long, avg: Double, max: Double, rawValues: List<Double>? = null) {
        val buckets = open.getOrPut(target) { HashMap() }
        val start = t - Math.floorMod(t, tier.resolutionMillis)
        val key = tier to metric
        val bucket = buckets[key]
        if (bucket != null && bucket.start != start) {
            buckets.remove(key)
            close(target, out, tier, metric, bucket)
        }
        val b = buckets.getOrPut(key) { Bucket(start) }
        b.sum += avg
        b.count++
        if (max > b.max) b.max = max
        // Only the 1 h tier keeps p95. It is computed over the hour's 1-minute averages, which the lower
        // buckets pass up, because raw points are not kept in memory for a whole hour.
        if (tier == Tier.H1 && rawValues != null) b.values.addAll(rawValues) else if (tier != Tier.M1) b.values.add(avg)
    }

    private fun close(target: String, out: Writes, tier: Tier, metric: Int, b: Bucket) {
        if (b.count == 0) return
        val avg = b.sum / b.count
        val p95 = if (tier == Tier.H1) percentile(b.values, 0.95) else Double.NaN
        out.add(tier, MetricRecord(b.start, metric, avg, b.max, p95))
        when (tier) {
            Tier.M1 -> feed(target, out, Tier.M15, metric, b.start, avg, b.max)
            Tier.M15 -> feed(target, out, Tier.H1, metric, b.start, avg, b.max, rawValues = b.values.toList())
            else -> Unit
        }
    }

    override fun markGap(target: String, fromMillis: Long, toMillis: Long) {
        if (toMillis <= fromMillis) return
        synchronized(lock(target)) { storeGap(target, fromMillis, toMillis) }
    }

    /** Picks the finest tier whose retention still covers [fromMillis]. */
    fun tierFor(fromMillis: Long): Tier {
        val age = clock() - fromMillis
        val r = retention()
        return Tier.entries.first { it == Tier.H1 || age <= it.retentionMillis(r) }
    }

    override fun series(target: String, metric: String, fromMillis: Long, toMillis: Long, stepMillis: Long?): MetricSeries {
        require(toMillis > fromMillis) { "to must be after from" }
        val index = Metric.all.indexOf(metric)
        require(index >= 0) { "Unknown metric $metric" }
        val tier = tierFor(fromMillis)
        val span = toMillis - fromMillis
        val step = maxOf(stepMillis ?: (span / MAX_POINTS), tier.resolutionMillis, 1_000L, span / MAX_POINTS)
        if (!open.containsKey(target) && target !in storedTargets()) return MetricSeries(target, metric, step, emptyList())
        val buckets = sortedMapOf<Long, Bucket>()
        val gaps = synchronized(lock(target)) {
            flush(target)
            load(target, tier, index, fromMillis, toMillis) { r ->
                val start = r.t - Math.floorMod(r.t - fromMillis, step)
                val b = buckets.getOrPut(start) { Bucket(start) }
                b.sum += r.avg
                b.count++
                if (r.max > b.max) b.max = r.max
                if (!r.p95.isNaN()) b.values += r.p95
            }
            // The open bucket of the current period is not stored yet; include it so recent data shows.
            if (tier != Tier.RAW) open[target]?.get(tier to index)?.takeIf { it.count > 0 && it.start in fromMillis..toMillis }?.let { o ->
                val start = o.start - Math.floorMod(o.start - fromMillis, step)
                val b = buckets.getOrPut(start) { Bucket(start) }
                b.sum += o.sum / o.count
                b.count++
                if (o.max > b.max) b.max = o.max
            }
            loadGaps(target, fromMillis, toMillis)
        }
        val points = buckets.values.map { b -> MetricPoint(b.start, b.sum / b.count, b.max, b.values.maxOrNull()) }
        return MetricSeries(target, metric, step, points, gaps)
    }

    override fun trends(target: String?): TrendsReport {
        val now = clock()
        val cached = trendsCache?.takeIf { now - it.first < 300_000 }?.second
        val all = cached ?: targets().associateWith { computeTrend(it, now) }.also { trendsCache = now to it }
        val selected = if (target == null) all.values.toList() else listOfNotNull(all[target] ?: computeTrend(target, now))
        val top = all.values.filter { it.avgCpuWeek != null }.sortedByDescending { it.avgCpuWeek }.take(5).map { it.target }
        return TrendsReport(selected, top, sizeBytes())
    }

    private fun computeTrend(target: String, now: Long): Trend {
        val week = now - 7 * 86_400_000L
        val day = now - 86_400_000L
        val used = series(target, Metric.DISK_USED, week, now, 900_000).points
        val total = series(target, Metric.DISK_TOTAL, week, now, 900_000).points.lastOrNull()?.avg
        val slopePerMs = slope(used.map { it.t.toDouble() to it.avg })
        val growthPerDay = slopePerMs?.times(86_400_000.0)
        val fullIn = if (growthPerDay != null && growthPerDay > 0 && total != null && used.isNotEmpty()) (total - used.last().avg) / growthPerDay else null
        val mem = series(target, Metric.MEMORY_PERCENT, day, now, 60_000).points
        val pressure = if (mem.isEmpty()) null else mem.count { it.avg >= 90.0 }.toDouble() / mem.size
        val cpu = series(target, Metric.CPU, week, now, 900_000).points
        return Trend(target, growthPerDay, fullIn?.coerceAtLeast(0.0), pressure, cpu.takeIf { it.isNotEmpty() }?.map { it.avg }?.average())
    }

    override fun targets(): List<String> = (storedTargets() + open.keys).sorted()

    override fun forget(target: String) {
        synchronized(lock(target)) {
            open.remove(target)
            drop(target)
        }
        trendsCache = null
    }

    override fun maintain() {
        val now = clock()
        for (target in open.keys.toList()) synchronized(lock(target)) {
            val buckets = open[target] ?: return@synchronized
            val out = Writes()
            // Close buckets whose period ended (a VM that stopped sends no more samples to close them).
            for (tier in listOf(Tier.M1, Tier.M15, Tier.H1)) {
                for ((key, bucket) in buckets.entries.filter { it.key.first == tier }) {
                    if (now >= bucket.start + tier.resolutionMillis + GRACE_MILLIS) {
                        buckets.remove(key)
                        close(target, out, tier, key.second, bucket)
                    }
                }
            }
            out.flushTo(target)
            flush(target)
        }
        if (now - lastPurge >= 3_600_000L) {
            lastPurge = now
            val r = retention()
            val emptied = purge(Tier.entries.associateWith { now - it.retentionMillis(r) })
            // A deleted VM keeps its history until retention removes the last record.
            for (target in emptied) synchronized(lock(target)) { if (open[target].isNullOrEmpty()) { open.remove(target); drop(target) } }
        }
    }

    companion object {
        private const val GRACE_MILLIS = 5_000L
        const val MAX_POINTS = 500L

        fun percentile(values: List<Double>, q: Double): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
        }

        /** Least-squares slope of (x, y), or null with fewer than two distinct x. */
        fun slope(points: List<Pair<Double, Double>>): Double? {
            if (points.size < 2) return null
            val mx = points.sumOf { it.first } / points.size
            val my = points.sumOf { it.second } / points.size
            val den = points.sumOf { (it.first - mx) * (it.first - mx) }
            if (den == 0.0) return null
            return points.sumOf { (it.first - mx) * (it.second - my) } / den
        }
    }
}

/**
 * The fallback store without dependencies, used when SQLite cannot be opened: per target a directory
 * with one append-only binary file per tier. Records are fixed 33 bytes: time, metric index, avg, max, p95.
 */
class FileMetricsStore(
    private val dir: File,
    retention: () -> Retention = { Retention() },
    clock: () -> Long = System::currentTimeMillis,
) : TieredMetricsStore(retention, clock) {
    private class TargetData(val path: File) {
        val writers = HashMap<Tier, DataOutputStream>()

        fun writer(tier: Tier): DataOutputStream = writers.getOrPut(tier) {
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(path, tier.fileName), true)))
        }

        fun flush() = writers.values.forEach { runCatching { it.flush() } }

        fun closeWriters() {
            writers.values.forEach { runCatching { it.close() } }
            writers.clear()
        }
    }

    private val targetsMap = ConcurrentHashMap<String, TargetData>()

    init {
        privateDir(dir)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val name = runCatching { File(d, "target.txt").readText().trim() }.getOrNull() ?: return@forEach
            targetsMap[name] = TargetData(d)
        }
    }

    private fun data(target: String): TargetData = targetsMap.getOrPut(target) {
        val d = File(dir, Secrets.sha256(target).take(20))
        privateDir(d)
        File(d, "target.txt").writeText(target)
        TargetData(d)
    }

    override fun store(target: String, tier: Tier, records: List<MetricRecord>) {
        val out = data(target).writer(tier)
        records.forEach { write(out, it) }
    }

    private fun write(out: DataOutputStream, r: MetricRecord) {
        out.writeLong(r.t)
        out.writeByte(r.metric)
        out.writeDouble(r.avg)
        out.writeDouble(r.max)
        out.writeDouble(r.p95)
    }

    private fun read(file: File, onRecord: (MetricRecord) -> Unit) {
        if (!file.isFile) return
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            while (true) {
                val rec = try {
                    MetricRecord(input.readLong(), input.readUnsignedByte(), input.readDouble(), input.readDouble(), input.readDouble())
                } catch (_: EOFException) {
                    break
                }
                onRecord(rec)
            }
        }
    }

    override fun load(target: String, tier: Tier, metric: Int, fromMillis: Long, toMillis: Long, onRecord: (MetricRecord) -> Unit) {
        val td = targetsMap[target] ?: return
        read(File(td.path, tier.fileName)) { r -> if (r.metric == metric && r.t in fromMillis..toMillis) onRecord(r) }
    }

    override fun storeGap(target: String, fromMillis: Long, toMillis: Long) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(File(data(target).path, GAPS), true))).use {
            it.writeLong(fromMillis)
            it.writeLong(toMillis)
        }
    }

    override fun loadGaps(target: String, fromMillis: Long, toMillis: Long): List<TimeRange> {
        val file = File(targetsMap[target]?.path ?: return emptyList(), GAPS)
        if (!file.isFile) return emptyList()
        val out = mutableListOf<TimeRange>()
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            while (true) {
                val a = try { input.readLong() } catch (_: EOFException) { break }
                val b = try { input.readLong() } catch (_: EOFException) { break }
                if (b >= fromMillis && a <= toMillis) out += TimeRange(a, b)
            }
        }
        return out
    }

    override fun storedTargets(): Set<String> = targetsMap.keys

    override fun drop(target: String) {
        val td = targetsMap.remove(target) ?: return
        td.closeWriters()
        td.path.deleteRecursively()
    }

    override fun flush(target: String) {
        targetsMap[target]?.flush()
    }

    override fun purge(cutoffs: Map<Tier, Long>): Set<String> {
        val emptied = mutableSetOf<String>()
        for ((target, td) in targetsMap.entries.toList()) synchronized(lock(target)) {
            td.closeWriters()
            var anyLeft = false
            for (tier in Tier.entries) {
                val file = File(td.path, tier.fileName)
                if (!file.isFile) continue
                val cutoff = cutoffs.getValue(tier)
                val tmp = File(td.path, tier.fileName + ".tmp")
                var kept = 0
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                    read(file) { rec -> if (rec.t >= cutoff) { write(out, rec); kept++ } }
                }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                if (kept > 0) anyLeft = true
            }
            if (!anyLeft) emptied += target
        }
        return emptied
    }

    override fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    override fun close() {
        targetsMap.values.forEach { it.closeWriters() }
    }

    companion object {
        private const val GAPS = "gaps.bin"
    }
}
