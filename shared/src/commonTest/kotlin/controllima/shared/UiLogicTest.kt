package controllima.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiLogicTest {
    @Test
    fun formatsBytesWithExplicitUnits() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KiB", Format.bytes(1024))
        assertEquals("8.0 GiB", Format.bytes(8L * 1024 * 1024 * 1024))
        assertEquals("1.5 GB", Format.bytes(1_500_000_000, SizeUnits.DECIMAL))
        assertEquals("—", Format.bytes(null))
        assertEquals("12.3 %", Format.percent(12.34))
    }

    @Test
    fun formatsTimestampsInUtc() {
        assertEquals("1970-01-01 00:00:00 UTC", Format.timestamp(0))
        assertEquals("2024-11-25 13:06:40 UTC", Format.timestamp(1_732_540_000_000))
        assertEquals("1h 1m", Format.duration(3_660_000))
    }

    @Test
    fun confirmNeedsExactName() {
        assertTrue(Confirm.matches("dev", "dev"))
        assertFalse(Confirm.matches("Dev", "dev"))
        assertFalse(Confirm.matches("", ""))
    }

    @Test
    fun diffFindsAddedAndRemovedLines() {
        val diff = LineDiff.diff("a\nb\nc", "a\nc\nd")
        assertEquals(
            listOf(DiffLine(DiffKind.SAME, "a"), DiffLine(DiffKind.REMOVED, "b"), DiffLine(DiffKind.SAME, "c"), DiffLine(DiffKind.ADDED, "d")),
            diff,
        )
    }

    @Test
    fun niceScaleCoversData() {
        val scale = ChartMath.niceScale(listOf(3.0, 87.0))
        assertEquals(0.0, scale.min)
        assertTrue(scale.max >= 87.0)
        assertEquals(scale.max, scale.ticks.last())
        assertEquals(100.0, ChartMath.niceScale(listOf(10.0), fixedMax = 100.0).max)
    }

    @Test
    fun segmentsBreakOnGaps() {
        val points = listOf(0L, 2_000L, 4_000L, 60_000L, 62_000L).map { MetricPoint(it, 1.0) }
        assertEquals(listOf(3, 2), ChartMath.segments(points, 10_000).map { it.size })
    }

    @Test
    fun vmTableFiltersSortsAndCounts() {
        val vms = listOf(
            Vm("b", VmSource.LIMA, VmStatus.RUNNING, cpus = 2),
            Vm("a", VmSource.COLIMA, VmStatus.STOPPED, cpus = 4),
            Vm("c", VmSource.LIMA, VmStatus.UNRESPONSIVE, ipAddresses = listOf("192.168.5.15")),
        )
        assertEquals(listOf("b", "c"), VmTable.filter(vms, "", VmSource.LIMA, null).map { it.name })
        assertEquals(listOf("c"), VmTable.filter(vms, "192.168", null, null).map { it.name })
        assertEquals(listOf("a", "b", "c"), VmTable.sort(vms, VmTable.SortKey.NAME).map { it.name })
        assertEquals(listOf("a", "b", "c"), VmTable.sort(vms, VmTable.SortKey.CPUS, descending = true).map { it.name })
        assertEquals(VmTable.Counts(1, 1, 1, 0), VmTable.counts(vms))
        assertFalse(VmTable.canStop(vms[2]))
        assertTrue(VmTable.canForceStop(vms[2]))
    }

    @Test
    fun sampleWindowKeepsCapacity() {
        val window = SampleWindow(3)
        repeat(5) { window.add(StatsSample("t", it.toLong())) }
        assertEquals(listOf(2L, 3L, 4L), window.samples().map { it.epochMillis })
    }

    @Test
    fun csvExport() {
        val csv = ChartMath.csv(listOf(MetricSeries("vm:lima/a", Metric.CPU, 2000, listOf(MetricPoint(1, 2.0, 3.0)))))
        assertEquals("target,metric,time_ms,avg,max,p95\nvm:lima/a,cpu,1,2.0,3.0,\n", csv)
    }

    @Test
    fun csvCellQuotesAndDefusesFormulas() {
        assertEquals("\"a,b\"", Format.csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Format.csvCell("say \"hi\""))
        assertEquals("\"'=cmd()\"", Format.csvCell("=cmd()"))
    }
}
