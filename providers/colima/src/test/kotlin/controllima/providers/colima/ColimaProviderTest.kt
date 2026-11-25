package controllima.providers.colima

import controllima.providers.process.CommandResult
import controllima.providers.process.CommandRunner
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class ColimaProviderTest {
    private val fixture = javaClass.getResource("/colima-list.ndjson")!!.readText()

    private class FakeRunner(private val result: CommandResult) : CommandRunner {
        override fun run(command: List<String>, timeout: Duration, env: Map<String, String>) = result
    }

    @Test
    fun parsesFixture() {
        val vms = ColimaJson.parseList(fixture)
        assertEquals(2, vms.size)
        assertEquals(VmSource.COLIMA, vms[0].source)
        assertEquals(VmStatus.RUNNING, vms[0].status)
        assertEquals(listOf("192.168.106.2"), vms[0].ipAddresses)
        assertEquals("docker", vms[0].runtime)
        assertEquals(VmStatus.STOPPED, vms[1].status)
        assertEquals(emptyList(), vms[1].ipAddresses)
    }

    @Test
    fun failureBecomesException() {
        val provider = ColimaCliProvider(FakeRunner(CommandResult(1, "", "boom", false)))
        assertFailsWith<ColimaException> { provider.list() }
    }

    @Test
    fun dockerSocketPath() {
        assertEquals("/h/.colima/default/docker.sock", ColimaCliProvider(FakeRunner(CommandResult(0, "", "", false)), home = "/h").dockerSocket("default"))
    }
}
