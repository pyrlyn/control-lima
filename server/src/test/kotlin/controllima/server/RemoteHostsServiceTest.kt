package controllima.server

import controllima.providers.process.CommandResult
import controllima.providers.process.ScriptedRunner
import controllima.shared.RemoteHost
import controllima.shared.Settings
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteHostsServiceTest {
    private fun ok(out: String) = CommandResult(0, out, "", false)
    private fun fail(code: Int, err: String) = CommandResult(code, "", err, false)

    private val limaLine = """{"name":"infra-test","status":"Running","dir":"/x","arch":"aarch64","cpus":2,"memory":1,"disk":1}"""
    private val colimaInLima = """{"name":"colima","status":"Running","dir":"/y","arch":"aarch64","cpus":2,"memory":1,"disk":1}"""
    private val colimaLine = """{"name":"default","status":"Running","arch":"aarch64","cpus":2,"memory":1,"disk":1,"runtime":"docker"}"""

    @Test
    fun `remote hosts are listed read only with colima taking over its own lima instance`() = runBlocking {
        val runner = ScriptedRunner(listOf(
            "box-a ${RemoteHostsService.LIMA_LIST}" to ok("$limaLine\n$colimaInLima\n"),
            "box-a ${RemoteHostsService.COLIMA_LIST}" to ok("$colimaLine\n"),
            "me@box-b ${RemoteHostsService.LIMA_LIST}" to ok("$limaLine\n"),
            "me@box-b ${RemoteHostsService.COLIMA_LIST}" to fail(127, "colima: command not found"),
            "dead ${RemoteHostsService.LIMA_LIST}" to fail(255, "ssh: connect to host dead port 22: Connection refused\n"),
        ))
        var now = 1_000L
        val settings = Settings(remoteHosts = listOf(RemoteHost("a", "box-a"), RemoteHost("b", "me@box-b"), RemoteHost("d", "dead")))
        val service = RemoteHostsService(runner, { settings }, { now })
        val (a, b, d) = service.list()

        assertTrue(a.reachable)
        assertNull(a.error)
        assertEquals(listOf("default" to VmSource.COLIMA, "infra-test" to VmSource.LIMA), a.vms.map { it.name to it.source })
        assertEquals(VmStatus.RUNNING, a.vms.last().status)
        assertTrue(a.vms.last().protected, "protected names apply to remote VMs too")
        assertTrue(b.reachable)
        assertNull(b.error, "a host without colima is not an error")
        assertFalse(d.reachable)
        assertEquals("ssh: connect to host dead port 22: Connection refused", d.error)
        assertEquals(1, runner.calls.count { "dead" in it }, "an unreachable host is not asked twice")
        runner.calls.forEach { c ->
            assertEquals(listOf("ssh", "-o", "BatchMode=yes"), c.take(3))
            assertEquals("--", c[c.size - 3], "ssh options end before the target")
            assertTrue(c.last() == RemoteHostsService.LIMA_LIST || c.last() == RemoteHostsService.COLIMA_LIST)
        }

        val calls = runner.calls.size
        now += 10_000
        service.list()
        assertEquals(calls, runner.calls.size, "cached for 30 s")
        now += RemoteHostsService.CACHE_MILLIS
        service.list()
        assertEquals(calls * 2, runner.calls.size)
    }

    @Test
    fun `settings reject ssh targets that ssh would read as options`() {
        val bad = listOf("-oProxyCommand=sh", "a b", "box;rm", "", "user@-x")
        bad.forEach { t ->
            assertFailsWith<IllegalArgumentException>(t) { SettingsStore.validate(Settings(remoteHosts = listOf(RemoteHost("x", t)))) }
        }
        assertFailsWith<IllegalArgumentException> {
            SettingsStore.validate(Settings(remoteHosts = listOf(RemoteHost("x", "a"), RemoteHost("x", "b"))))
        }
        SettingsStore.validate(Settings(remoteHosts = listOf(RemoteHost("mini", "me@mac-mini.local"), RemoteHost("box", "build_box"))))
    }
}
