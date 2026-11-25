package controllima.providers.lima

import controllima.providers.process.CommandResult
import controllima.providers.process.CommandRunner
import controllima.shared.VmStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class LimaProviderTest {
    private val fixture = javaClass.getResource("/limactl-list.ndjson")!!.readText()

    private class FakeRunner(private val result: CommandResult) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration, env: Map<String, String>): CommandResult {
            calls += command
            return result
        }
    }

    @Test
    fun parsesFixture() {
        val vms = LimaJson.parseList(fixture)
        assertEquals(listOf("infra-test", "tc-dev-1", "broken-one"), vms.map { it.name })
        val infra = vms[0]
        assertEquals(VmStatus.RUNNING, infra.status)
        assertEquals(4, infra.cpus)
        assertEquals(60022, infra.sshPort)
        assertEquals(listOf("192.168.104.3"), infra.ipAddresses)
        assertEquals(8443, infra.portForwards[0].hostPort)
        assertEquals(8080, infra.portForwards[1].hostPort)
        assertTrue(infra.protected)
        assertEquals(VmStatus.STOPPED, vms[1].status)
        assertEquals(null, vms[1].sshPort)
        assertEquals(VmStatus.BROKEN, vms[2].status)
    }

    @Test
    fun listUsesJsonFlagAndPassthroughArgs() {
        val runner = FakeRunner(CommandResult(0, fixture, "", false))
        LimactlProvider(runner, extraArgs = listOf("--debug")).list()
        assertEquals(listOf("limactl", "--debug", "list", "--json"), runner.calls.single())
    }

    @Test
    fun timeoutBecomesProviderException() {
        val runner = FakeRunner(CommandResult(null, "", "", timedOut = true))
        assertFailsWith<ProviderException> { LimactlProvider(runner).list() }
    }

    @Test
    fun probeFailsWhenGuestDoesNotAnswer() {
        val runner = FakeRunner(CommandResult(null, "", "", timedOut = true))
        assertEquals(false, LimactlProvider(runner).probe("infra-test"))
    }
}
