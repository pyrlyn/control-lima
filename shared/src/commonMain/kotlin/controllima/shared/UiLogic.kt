package controllima.shared

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Formatting with explicit units (docs/plan/i18n-accessibility.md). Lives in `shared` so it is
 * tested on the JVM and reused by the web UI and the CLI.
 */
object Format {
    fun bytes(value: Long?, units: SizeUnits = SizeUnits.BINARY): String {
        if (value == null) return "—"
        val base = if (units == SizeUnits.BINARY) 1024.0 else 1000.0
        val names = if (units == SizeUnits.BINARY) listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB") else listOf("B", "kB", "MB", "GB", "TB", "PB")
        var v = abs(value.toDouble())
        var i = 0
        while (v >= base && i < names.lastIndex) { v /= base; i++ }
        val sign = if (value < 0) "-" else ""
        return if (i == 0) "$sign${abs(value)} B" else "$sign${oneDecimal(v)} ${names[i]}"
    }

    fun rate(value: Double?, units: SizeUnits = SizeUnits.BINARY): String =
        if (value == null) "—" else bytes(value.roundToLong(), units) + "/s"

    fun percent(value: Double?): String = if (value == null) "—" else "${oneDecimal(value)} %"

    fun millis(value: Double?): String = if (value == null) "—" else "${oneDecimal(value)} ms"

    fun metric(metric: String, value: Double?, units: SizeUnits = SizeUnits.BINARY): String = when (Metric.unit(metric)) {
        Metric.Unit.PERCENT -> percent(value)
        Metric.Unit.BYTES -> bytes(value?.roundToLong(), units)
        Metric.Unit.BYTES_PER_SEC -> rate(value, units)
        Metric.Unit.MILLIS -> millis(value)
        Metric.Unit.NONE -> value?.let(::twoDecimals) ?: "—"
    }

    fun duration(millis: Long): String {
        val s = millis / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86400}d ${(s % 86400) / 3600}h"
        }
    }

    /** `yyyy-MM-dd HH:mm:ss` in UTC; the browser clock zone is not reliable in wasm. */
    fun timestamp(epochMillis: Long?): String {
        if (epochMillis == null) return "—"
        val totalSeconds = floor(epochMillis / 1000.0).toLong()
        val days = floor(totalSeconds / 86400.0).toLong()
        val secOfDay = totalSeconds - days * 86400
        val (y, m, d) = civilFromDays(days)
        return "${y}-${pad(m)}-${pad(d)} ${pad((secOfDay / 3600).toInt())}:${pad(((secOfDay % 3600) / 60).toInt())}:${pad((secOfDay % 60).toInt())} UTC"
    }

    fun clock(epochMillis: Long): String = timestamp(epochMillis).substring(11, 16)

    fun status(status: VmStatus): String = when (status) {
        VmStatus.RUNNING -> "Running"
        VmStatus.STOPPED -> "Stopped"
        VmStatus.BROKEN -> "Broken"
        VmStatus.UNRESPONSIVE -> "Unresponsive"
        VmStatus.UNKNOWN -> "Unknown"
    }

    /** Text icon so status is never shown by color alone. */
    fun statusIcon(status: VmStatus): String = when (status) {
        VmStatus.RUNNING -> "●"
        VmStatus.STOPPED -> "○"
        VmStatus.BROKEN -> "✕"
        VmStatus.UNRESPONSIVE -> "⚠"
        VmStatus.UNKNOWN -> "?"
    }

    fun oneDecimal(v: Double): String {
        val r = (v * 10).roundToLong()
        return "${r / 10}.${abs(r % 10)}".let { if (v < 0 && r / 10 == 0L && r != 0L) "-$it" else it }
    }

    private fun twoDecimals(v: Double): String {
        val r = (v * 100).roundToLong()
        val frac = abs(r % 100).toString().padStart(2, '0')
        return "${r / 100}.$frac"
    }

    private fun pad(v: Int) = v.toString().padStart(2, '0')

    // Howard Hinnant's days-to-civil algorithm, so formatting needs no date library.
    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}

/** Type-to-confirm rule shared by the dialog and the server. */
object Confirm {
    fun matches(typed: String, name: String): Boolean = typed == name && name.isNotEmpty()

    fun canDelete(name: String, protectedNames: Collection<String>): Boolean = name !in protectedNames
}

/** Line diff (LCS) for "diff before restore". Configs are small, so O(n·m) is fine. */
object LineDiff {
    fun diff(old: String, new: String): List<DiffLine> {
        val a = old.lines()
        val b = new.lines()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else max(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffKind.SAME, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine(DiffKind.REMOVED, a[i]); i++ }
                else -> { out += DiffLine(DiffKind.ADDED, b[j]); j++ }
            }
        }
        while (i < a.size) out += DiffLine(DiffKind.REMOVED, a[i++])
        while (j < b.size) out += DiffLine(DiffKind.ADDED, b[j++])
        return out
    }
}

/** Axis and scaling math for Canvas charts; no chart library (docs/plan/metrics.md). */
object ChartMath {
    data class Scale(val min: Double, val max: Double, val ticks: List<Double>)

    /** "Nice" y axis covering [values], starting at zero for non-negative data. */
    fun niceScale(values: List<Double>, tickCount: Int = 4, fixedMax: Double? = null): Scale {
        val hi = fixedMax ?: (values.maxOrNull() ?: 0.0)
        val lo = minOf(0.0, values.minOrNull() ?: 0.0)
        if (hi <= lo) return Scale(lo, lo + 1, listOf(lo, lo + 1))
        val rawStep = (hi - lo) / tickCount
        val magnitude = 10.0.pow(floor(log10(rawStep)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rawStep }
        val top = ceil(hi / step) * step
        val ticks = generateSequence(lo) { it + step }.takeWhile { it <= top + step / 2 }.toList()
        return Scale(lo, top, ticks)
    }

    /** Splits points into segments wherever the gap between samples exceeds [maxGapMillis]. */
    fun segments(points: List<MetricPoint>, maxGapMillis: Long): List<List<MetricPoint>> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf(mutableListOf(points.first()))
        for (k in 1 until points.size) {
            if (points[k].t - points[k - 1].t > maxGapMillis) out += mutableListOf<MetricPoint>()
            out.last() += points[k]
        }
        return out
    }

    fun x(t: Long, from: Long, to: Long, width: Float): Float =
        if (to <= from) 0f else ((t - from).toDouble() / (to - from) * width).toFloat()

    fun y(v: Double, scale: Scale, height: Float): Float =
        (height - (v - scale.min) / (scale.max - scale.min) * height).toFloat()

    /** Closest point to time [t], for the crosshair tooltip. */
    fun nearest(points: List<MetricPoint>, t: Long): MetricPoint? = points.minByOrNull { abs(it.t - t) }

    fun csv(series: List<MetricSeries>): String = buildString {
        appendLine("target,metric,time_ms,avg,max,p95")
        series.forEach { s -> s.points.forEach { p -> appendLine("${s.target},${s.metric},${p.t},${p.avg},${p.max},${p.p95 ?: ""}") } }
    }
}

/** VM table filtering and sorting (docs/pages/vms.md). */
object VmTable {
    enum class SortKey { STATUS, NAME, SOURCE, CPUS, MEMORY, DISK }

    fun filter(vms: List<Vm>, query: String, source: VmSource?, status: VmStatus?): List<Vm> =
        vms.filter { vm ->
            (source == null || vm.source == source) &&
                (status == null || vm.status == status) &&
                (query.isBlank() || vm.name.contains(query.trim(), ignoreCase = true) || vm.ipAddresses.any { it.contains(query.trim()) })
        }

    fun sort(vms: List<Vm>, key: SortKey, descending: Boolean = false): List<Vm> {
        val sorted = when (key) {
            SortKey.STATUS -> vms.sortedBy { it.status.ordinal }
            SortKey.NAME -> vms.sortedBy { it.name.lowercase() }
            SortKey.SOURCE -> vms.sortedBy { it.source.ordinal }
            SortKey.CPUS -> vms.sortedBy { it.cpus ?: -1 }
            SortKey.MEMORY -> vms.sortedBy { it.memoryBytes ?: -1 }
            SortKey.DISK -> vms.sortedBy { it.diskBytes ?: -1 }
        }
        return if (descending) sorted.reversed() else sorted
    }

    data class Counts(val running: Int, val stopped: Int, val unresponsive: Int, val broken: Int)

    fun counts(vms: List<Vm>) = Counts(
        running = vms.count { it.status == VmStatus.RUNNING },
        stopped = vms.count { it.status == VmStatus.STOPPED },
        unresponsive = vms.count { it.status == VmStatus.UNRESPONSIVE },
        broken = vms.count { it.status == VmStatus.BROKEN || it.status == VmStatus.UNKNOWN },
    )

    /** Allowed actions per status; an unresponsive VM gets force stop and logs only. */
    fun canStart(vm: Vm) = vm.status == VmStatus.STOPPED || vm.status == VmStatus.BROKEN
    fun canStop(vm: Vm) = vm.status == VmStatus.RUNNING
    fun canForceStop(vm: Vm) = vm.status == VmStatus.RUNNING || vm.status == VmStatus.UNRESPONSIVE
}

/** Rolling window of live samples per target, used by sparklines and the MVP ring buffer. */
class SampleWindow(private val capacity: Int = 300) {
    private val buffer = ArrayDeque<StatsSample>()

    fun add(sample: StatsSample) {
        buffer.addLast(sample)
        while (buffer.size > capacity) buffer.removeFirst()
    }

    fun samples(): List<StatsSample> = buffer.toList()
    fun latest(): StatsSample? = buffer.lastOrNull()
    fun clear() = buffer.clear()
}
