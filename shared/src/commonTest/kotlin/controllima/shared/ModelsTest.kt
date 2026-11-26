package controllima.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun vmRoundTripsThroughJson() {
        val vm = Vm(name = "dev", source = VmSource.LIMA, status = VmStatus.UNRESPONSIVE, cpus = 4)
        val json = Json.encodeToString(Vm.serializer(), vm)
        assertEquals(vm, Json.decodeFromString(Vm.serializer(), json))
    }

    @Test
    fun infraTestIsProtectedByDefault() {
        assertEquals(true, "infra-test" in ProtectedNames.defaults)
        assertFalse(Confirm.canDelete("infra-test", ProtectedNames.defaults))
    }

    @Test
    fun targetsRoundTrip() {
        val ref = VmRef(VmSource.LIMA, "dev", "/Users/me/.lima2")
        assertEquals("vm:lima/dev@/Users/me/.lima2", ref.targetId)
        assertEquals(ref, Targets.parseVm(ref.targetId))
        assertEquals(VmRef(VmSource.COLIMA, "default"), Targets.parseVm("vm:colima/default"))
        assertEquals("default" to "abc123", Targets.parseContainer("container:default/abc123"))
        assertNull(Targets.parseVm("vm:nope/x"))
        assertNull(Targets.parseContainer("container:x"))
    }

    @Test
    fun namesRejectShellMetacharacters() {
        assertTrue(Names.isValidVmName("tc-dev_1.2"))
        assertFalse(Names.isValidVmName("-rf"))
        assertFalse(Names.isValidVmName("a;rm -rf /"))
        assertFalse(Names.isValidVmName(""))
        assertTrue(Names.isValidContainerRef("8dfafdbc3a40"))
    }

    @Test
    fun routesEncodeNamesAndHome() {
        assertEquals("/api/vms/lima/dev", ApiRoutes.vm(VmRef(VmSource.LIMA, "dev")))
        assertEquals("/api/vms/lima/dev/stop?home=%2Ftmp%2Fh", ApiRoutes.vmAction(VmRef(VmSource.LIMA, "dev", "/tmp/h"), "stop"))
        assertEquals("a%20b%C3%A9", ApiRoutes.encode("a bé"))
    }

    @Test
    fun metricValuesIncludeDerivedPercentages() {
        val values = Metric.valuesOf(StatsSample("t", 0, cpuPercent = 12.0, memoryUsedBytes = 50, memoryTotalBytes = 200))
        assertEquals(12.0, values[Metric.CPU])
        assertEquals(25.0, values[Metric.MEMORY_PERCENT])
        assertNull(values[Metric.DISK_PERCENT])
    }
}
