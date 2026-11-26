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

/** Metrics history (docs/plan/metrics.md). The interface keeps the storage engine replaceable (SQLite later). */
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

/**
 * The fallback store without new dependencies: per target a directory with one append-only binary file
 * per tier. Records are fixed 33 bytes: time, metric index, avg, max, p95 (NaN when unknown). Downsampling
 * is streaming (raw → 1 min → 15 min → 1 h) with buckets closed by [maintain]; purge rewrites files.
 */
class FileMetricsStore(
    private val dir: File,
    private val retention: () -> Retention = { Retention() },
    private val clock: () -> Long = System::currentTimeMillis,
) : MetricsStore {
    private class Bucket(val start: Long) {
        var sum = 0.0
        var count = 0
        var max = Double.NEGATIVE_INFINITY
        val values = ArrayList<Double>()
    }

    private inner class TargetData(val target: String, val path: File) {
        val writers = HashMap<Tier, DataOutputStream>()
        /** Open bucket per (tier, metric) for M1, M15, H1. */
        val open = HashMap<Pair<Tier, Int>, Bucket>()

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
    @Volatile private var lastPurge = 0L
    @Volatile private var trendsCache: Pair<Long, Map<String, Trend>>? = null

    init {
        privateDir(dir)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val name = runCatching { File(d, "target.txt").readText().trim() }.getOrNull() ?: return@forEach
            targetsMap[name] = TargetData(name, d)
        }
    }

    private fun data(target: String): TargetData = targetsMap.getOrPut(target) {
        val d = File(dir, Secrets.sha256(target).take(20))
        privateDir(d)
        File(d, "target.txt").writeText(target)
        TargetData(target, d)
    }

    override fun append(sample: StatsSample) {
        val values = Metric.valuesOf(sample)
        if (values.isEmpty()) return
        val td = data(sample.targetId)
        synchronized(td) {
            val raw = td.writer(Tier.RAW)
            for ((metric, value) in values) {
                val index = Metric.all.indexOf(metric)
                if (index < 0 || value.isNaN()) continue
                write(raw, sample.epochMillis, index, value, value, Double.NaN)
                feed(td, Tier.M1, index, sample.epochMillis, value, value)
            }
        }
    }

    /** Adds a value to the open bucket of [tier], closing it (and cascading) when time moved past it. */
    private fun feed(td: TargetData, tier: Tier, metric: Int, t: Long, avg: Double, max: Double, rawValues: List<Double>? = null) {
        val start = t - Math.floorMod(t, tier.resolutionMillis)
        val key = tier to metric
        val bucket = td.open[key]
        if (bucket != null && bucket.start != start) {
            close(td, tier, metric, bucket)
            td.open.remove(key)
        }
        val b = td.open.getOrPut(key) { Bucket(start) }
        b.sum += avg
        b.count++
        if (max > b.max) b.max = max
        // Only the 1 h tier keeps p95. It is computed over the hour's 1-minute averages, which the lower
        // buckets pass up, because raw points are not kept in memory for a whole hour.
        if (tier == Tier.H1 && rawValues != null) b.values.addAll(rawValues) else if (tier != Tier.M1) b.values.add(avg)
    }

    private fun close(td: TargetData, tier: Tier, metric: Int, b: Bucket) {
        if (b.count == 0) return
        val avg = b.sum / b.count
        val p95 = if (tier == Tier.H1) percentile(b.values, 0.95) else Double.NaN
        write(td.writer(tier), b.start, metric, avg, b.max, p95)
        when (tier) {
            Tier.M1 -> feed(td, Tier.M15, metric, b.start, avg, b.max)
            Tier.M15 -> feed(td, Tier.H1, metric, b.start, avg, b.max, rawValues = b.values.toList())
            else -> Unit
        }
    }

    private fun write(out: DataOutputStream, t: Long, metric: Int, avg: Double, max: Double, p95: Double) {
        out.writeLong(t)
        out.writeByte(metric)
        out.writeDouble(avg)
        out.writeDouble(max)
        out.writeDouble(p95)
    }

    private class Rec(val t: Long, val metric: Int, val avg: Double, val max: Double, val p95: Double)

    private fun read(file: File, onRecord: (Rec) -> Unit) {
        if (!file.isFile) return
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            while (true) {
                val rec = try {
                    Rec(input.readLong(), input.readUnsignedByte(), input.readDouble(), input.readDouble(), input.readDouble())
                } catch (_: EOFException) {
                    break
                }
                onRecord(rec)
            }
        }
    }

    override fun markGap(target: String, fromMillis: Long, toMillis: Long) {
        if (toMillis <= fromMillis) return
        val td = data(target)
        synchronized(td) {
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(td.path, GAPS), true))).use {
                it.writeLong(fromMillis)
                it.writeLong(toMillis)
            }
        }
    }

    private fun gaps(td: TargetData, from: Long, to: Long): List<TimeRange> {
        val file = File(td.path, GAPS)
        if (!file.isFile) return emptyList()
        val out = mutableListOf<TimeRange>()
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            while (true) {
                val a = try { input.readLong() } catch (_: EOFException) { break }
                val b = try { input.readLong() } catch (_: EOFException) { break }
                if (b >= from && a <= to) out += TimeRange(a, b)
            }
        }
        return out
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
        val td = targetsMap[target] ?: return MetricSeries(target, metric, step, emptyList())
        val buckets = sortedMapOf<Long, Bucket>()
        synchronized(td) {
            td.flush()
            read(File(td.path, tier.fileName)) { r ->
                if (r.metric != index || r.t < fromMillis || r.t > toMillis) return@read
                val start = r.t - Math.floorMod(r.t - fromMillis, step)
                val b = buckets.getOrPut(start) { Bucket(start) }
                b.sum += r.avg
                b.count++
                if (r.max > b.max) b.max = r.max
                if (!r.p95.isNaN()) b.values += r.p95
            }
            // The open bucket of the current period is not on disk yet; include it so recent data shows.
            if (tier != Tier.RAW) td.open[tier to index]?.takeIf { it.count > 0 && it.start in fromMillis..toMillis }?.let { open ->
                val start = open.start - Math.floorMod(open.start - fromMillis, step)
                val b = buckets.getOrPut(start) { Bucket(start) }
                b.sum += open.sum / open.count
                b.count++
                if (open.max > b.max) b.max = open.max
            }
        }
        val points = buckets.values.map { b -> MetricPoint(b.start, b.sum / b.count, b.max, b.values.maxOrNull()) }
        return MetricSeries(target, metric, step, points, gaps(td, fromMillis, toMillis))
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

    override fun targets(): List<String> = targetsMap.keys.sorted()

    override fun forget(target: String) {
        val td = targetsMap.remove(target) ?: return
        synchronized(td) {
            td.closeWriters()
            td.path.deleteRecursively()
        }
        trendsCache = null
    }

    override fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    override fun maintain() {
        val now = clock()
        for (td in targetsMap.values) synchronized(td) {
            // Close buckets whose period ended (a VM that stopped sends no more samples to close them).
            for (tier in listOf(Tier.M1, Tier.M15, Tier.H1)) {
                for ((key, bucket) in td.open.entries.filter { it.key.first == tier }) {
                    if (now >= bucket.start + tier.resolutionMillis + GRACE_MILLIS) {
                        td.open.remove(key)
                        close(td, tier, key.second, bucket)
                    }
                }
            }
            td.flush()
        }
        if (now - lastPurge >= 3_600_000L) {
            lastPurge = now
            purge(now)
        }
    }

    private fun purge(now: Long) {
        val r = retention()
        for (td in targetsMap.values.toList()) synchronized(td) {
            td.closeWriters()
            var anyLeft = false
            for (tier in Tier.entries) {
                val file = File(td.path, tier.fileName)
                if (!file.isFile) continue
                val cutoff = now - tier.retentionMillis(r)
                val tmp = File(td.path, tier.fileName + ".tmp")
                var kept = 0
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                    read(file) { rec -> if (rec.t >= cutoff) { write(out, rec.t, rec.metric, rec.avg, rec.max, rec.p95); kept++ } }
                }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                if (kept > 0) anyLeft = true
            }
            // A deleted VM keeps its history until retention removes the last record.
            if (!anyLeft && td.open.isEmpty()) {
                targetsMap.remove(td.target)
                td.path.deleteRecursively()
            }
        }
    }

    override fun close() {
        for (td in targetsMap.values) synchronized(td) { td.closeWriters() }
    }

    companion object {
        private const val GAPS = "gaps.bin"
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
