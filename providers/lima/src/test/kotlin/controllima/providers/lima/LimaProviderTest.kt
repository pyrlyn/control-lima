package controllima.providers.lima

import controllima.providers.process.ScriptedRunner
import controllima.shared.VmStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LimaProviderTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private val list = fixture("limactl-list.ndjson")

    @Test
    fun parsesFixture() {
        val vms = LimaJson.parseList(list, home = "/tmp/lima")
        assertEquals(listOf("infra-test", "tc-dev-1", "broken-one", "lima-protected"), vms.map { it.name })
        val infra = vms[0]
        assertEquals(VmStatus.RUNNING, infra.status)
        assertEquals(4, infra.cpus)
        assertEquals(60022, infra.sshPort)
        assertEquals(listOf("192.168.104.3"), infra.ipAddresses)
        assertEquals(listOf(8443, 8080), infra.portForwards.map { it.hostPort })
        assertEquals("/tmp/lima/infra-test/ssh.config", infra.sshConfigFile)
        assertEquals("/tmp/lima", infra.home)
        assertEquals(1, infra.mounts.size)
        assertTrue(infra.protected)
        assertEquals(VmStatus.STOPPED, vms[1].status)
        assertEquals(null, vms[1].sshPort)
        assertEquals(VmStatus.BROKEN, vms[2].status)
        assertTrue(vms[3].protected, "Lima's own protect flag counts too")
        assertEquals(emptyList(), vms[3].ipAddresses)
    }

    @Test
    fun listUsesJsonFlagPassthroughArgsAndHome() {
        val runner = ScriptedRunner(fallback = ScriptedRunner.ok(list))
        LimactlProvider(runner, extraArgs = listOf("--debug")).list()
        assertEquals(listOf("limactl", "--debug", "list", "--json"), runner.calls.single())
    }

    @Test
    fun timeoutBecomesProviderException() {
        assertFailsWith<ProviderException> { LimactlProvider(ScriptedRunner(fallback = ScriptedRunner.timeout)).list() }
    }

    @Test
    fun probeFailsWhenGuestDoesNotAnswer() {
        assertEquals(false, LimactlProvider(ScriptedRunner(fallback = ScriptedRunner.timeout)).probe("infra-test"))
    }

    @Test
    fun actionsBuildArgumentLists() {
        val runner = ScriptedRunner()
        val lima = LimactlProvider(runner)
        lima.start("dev")
        lima.stop("dev", force = true)
        lima.delete("dev")
        lima.rename("dev", "dev2")
        lima.clone("dev", "dev3")
        assertEquals(
            listOf(
                listOf("limactl", "start", "--tty=false", "dev"),
                listOf("limactl", "stop", "--force", "dev"),
                listOf("limactl", "delete", "--tty=false", "dev"),
                listOf("limactl", "rename", "dev", "dev2"),
                listOf("limactl", "clone", "--tty=false", "dev", "dev3"),
            ),
            runner.calls,
        )
    }

    @Test
    fun rejectsInjectedNames() {
        val runner = ScriptedRunner()
        assertFailsWith<ProviderException> { LimactlProvider(runner).delete("x; rm -rf ~") }
        assertFailsWith<ProviderException> { LimactlProvider(runner).start("--help") }
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun failedActionCarriesStderr() {
        val e = assertFailsWith<ProviderException> { LimactlProvider(ScriptedRunner(fallback = ScriptedRunner.fail("instance is running"))).rename("a", "b") }
        assertTrue(e.message!!.contains("instance is running"))
    }

    @Test
    fun createCommandValidatesFlags() {
        val lima = LimactlProvider(ScriptedRunner())
        val cmd = lima.createCommand("new", "template://docker", CreateFlags(cpus = 2, memoryGiB = 4.0, mounts = listOf("/Users/me:w"), portForwards = listOf("8080:80")))
        assertEquals(
            listOf("limactl", "create", "--tty=false", "--name=new", "--cpus=2", "--memory=4.0", "--mount=/Users/me:w", "--port-forward=8080:80", "template://docker"),
            cmd,
        )
        assertFailsWith<IllegalArgumentException> { lima.createCommand("new", "template://docker", CreateFlags(portForwards = listOf("8080:80;reboot"))) }
        assertFailsWith<ProviderException> { lima.createCommand("new", "https://evil/x.yaml", CreateFlags()) }
    }

    @Test
    fun parsesInfo() {
        val info = LimaJson.parseInfo(fixture("limactl-info.json"))
        assertEquals("2.2.0", info.version)
        assertEquals(listOf("docker", "k3s", "_images/ubuntu-24.04"), info.templates.map { it.name })
        assertEquals("template://_images/ubuntu-24.04", info.templates[2].location)
    }

    @Test
    fun parsesGuestIps() {
        assertEquals(listOf("192.168.5.15 (eth0)", "192.168.64.7 (lima0)"), LimaJson.parseIpAddr(fixture("ip-addr.json")))
        assertEquals(emptyList(), LimaJson.parseIpAddr("not json"))
    }

    @Test
    fun guestStatsFromTwoSnapshots() {
        val snapshots = mutableListOf<GuestSnapshot>()
        val parser = GuestStatsParser { snapshots += it }
        (fixture("guest-stats-1.txt") + fixture("guest-stats-2.txt")).lines().forEach(parser::accept)
        assertEquals(2, snapshots.size)
        val first = GuestStatsParser.sample("vm:lima/a", null, snapshots[0], 0, 1)
        assertNull(first.cpuPercent, "no rate without a previous snapshot")
        assertEquals(1_000_000L * 1024, first.memoryUsedBytes)
        val s = GuestStatsParser.sample("vm:lima/a", snapshots[0], snapshots[1], 2_000, 2)
        // total delta 300+100+500 = 900, idle delta 500 → 400/900
        assertEquals(400.0 * 100 / 900, s.cpuPercent!!, 0.001)
        assertEquals(2_000_000L * 1024, s.memoryUsedBytes)
        assertEquals(100_000.0, s.netRxBytesPerSec)
        assertEquals(50_000.0, s.netTxBytesPerSec)
        assertEquals(400 * 512 / 2.0, s.diskReadBytesPerSec)
        assertEquals(11_000_000L * 4096, s.diskUsedBytes)
        assertEquals(25_000_000L * 4096, s.diskTotalBytes)
        assertEquals(1.25, s.load1)
    }

    @Test
    fun guestShellUsesLimaSshConfig() {
        val runner = ScriptedRunner()
        val shell = GuestShell(runner)
        shell.statsStream("/tmp/lima/dev/ssh.config", "dev", 2, {}, {})
        val cmd = runner.streams.single()
        assertEquals(listOf("ssh", "-F", "/tmp/lima/dev/ssh.config"), cmd.take(3))
        assertTrue("lima-dev" in cmd)
        assertTrue(cmd.last().contains("/proc/stat"))
        assertFailsWith<ProviderException> { shell.command("relative/ssh.config", "dev", "true") }
        assertFalse(runner.calls.any())
        assertNotNull(GuestStatsParser.script(2))
    }

    @Test
    fun snapshotsListIsOneTagPerLine() {
        val lima = LimactlProvider(ScriptedRunner(listOf("snapshot list" to ScriptedRunner.ok("before-upgrade\nclean\n"))))
        assertEquals(listOf("before-upgrade", "clean"), lima.snapshots("dev"))
    }

    @Test
    fun snapshotCommandsPassTheTagAsOneArgument() {
        val runner = ScriptedRunner()
        val lima = LimactlProvider(runner)
        lima.snapshotCreate("dev", "before-upgrade")
        lima.snapshotApply("dev", "before-upgrade")
        lima.snapshotDelete("dev", "before-upgrade")
        assertEquals(
            listOf("create", "apply", "delete").map { listOf("limactl", "snapshot", it, "dev", "--tag", "before-upgrade") },
            runner.calls,
        )
        assertFailsWith<ProviderException> { lima.snapshotCreate("dev", "--tag=x") }
        assertFailsWith<ProviderException> { lima.snapshotApply("dev", "a b") }
    }

    @Test
    fun validateUsesJsonLogsAndAbsolutePaths() {
        val runner = ScriptedRunner()
        LimactlProvider(runner).validate("/tmp/x/dev.yaml")
        assertEquals(listOf("limactl", "--log-format=json", "validate", "/tmp/x/dev.yaml"), runner.calls.single())
        assertFailsWith<ProviderException> { LimactlProvider(runner).validate("dev.yaml") }
    }

    @Test
    fun logMessagesKeepWarningsAndAboveAndRawLines() {
        val stderr = """
            {"level":"info","msg":"`dev.yaml`: OK","time":"t"}
            {"level":"fatal","msg":"vmType `nope` is not a registered driver","time":"t"}
            {"level":"error","msg":"failed","error":"bad cpus","time":"t"}
            plain text from an older limactl
        """.trimIndent()
        assertEquals(
            listOf("vmType `nope` is not a registered driver", "failed: bad cpus", "plain text from an older limactl"),
            LimaJson.parseLogMessages(stderr),
        )
    }
}
