package controllima.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
    }
}
