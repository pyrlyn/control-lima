package controllima.server

import controllima.server.alerts.AlertEngine
import controllima.server.alerts.AlertInputs
import controllima.server.alerts.ChannelStore
import controllima.server.alerts.Notifier
import controllima.server.metrics.FileMetricsStore
import controllima.server.metrics.MetricsStore
import controllima.server.metrics.SqliteMetricsStore
import controllima.server.metrics.TieredMetricsStore
import controllima.providers.host.SecretStore
import controllima.providers.kube.KubeException
import controllima.shared.PortForwardKind
import controllima.shared.PortForwardRequest
import controllima.shared.AlertState
import controllima.shared.HostListener
import controllima.shared.Metric
import controllima.shared.NotificationChannel
import controllima.shared.PortForward
import controllima.shared.StatsSample
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServicesTest {

    @Test
    fun `kubernetes clusters come from lima kubeconfigs and stopped vms are never contacted`() = runBlocking {
        val home = limaKubeHome()
        val dir = home.resolve("lima/dev")
        val calls = mutableListOf<String>()
        val fake = FakeKube(calls)
        var status = VmStatus.STOPPED
        val kube = KubernetesService(home, { listOf(Vm("dev", VmSource.LIMA, status, dir = dir.toString())) }, { access ->
            calls += "connect ${access.server}"
            fake
        })
        val stopped = kube.clusters().single()
        assertEquals("lima/dev", stopped.id)
        assertFalse(stopped.available)
        assertNotNull(kube.overview("lima/dev").error)
        assertTrue(calls.isEmpty(), "a stopped VM's cluster is never contacted")

        status = VmStatus.RUNNING
        val overview = kube.overview("lima/dev", maxAgeMillis = 0)
        assertNull(overview.error)
        assertEquals(listOf("web-1"), overview.pods.map { it.name })
        assertEquals(listOf("web"), kube.cachedContainers("lima/dev", "default", "web-1"))
        val ports = PortsService.nodePorts(kube.nodePortServices())
        assertEquals(30080, ports.single().hostPort)
        assertEquals("kube:lima/dev", ports.single().ownerTarget)
        kube.deletePod("lima/dev", "default", "web-1")
        assertEquals(listOf("connect https://127.0.0.1:6443", "delete default/web-1"), calls)

        val pending = PortForwardRequest(PortForwardKind.POD, "default", "job-1", 80)
        assertTrue(assertFailsWith<IllegalStateException> { kube.check("lima/dev", pending) }.message!!.contains("Pending"))
        kube.check("lima/dev", PortForwardRequest(PortForwardKind.SERVICE, "default", "web", 80))
        assertFailsWith<KubeException> { kube.open("lima/dev", PortForwardRequest(PortForwardKind.SERVICE, "default", "web", 80)) }
        assertFailsWith<KubeException> { kube.open("lima/dev", PortForwardRequest(PortForwardKind.POD, "default", "web-1", 9000)) }
        // A service is forwarded to the pod and target port behind it.
        assertEquals(listOf("forward default/web-1:8080", "forward default/web-1:9000"), calls.drop(2))
        home.toFile().deleteRecursively()
    }
    private val dir = Files.createTempDirectory("cl-svc").toFile()

    @Test
    fun `file store downsamples and forgets`() = downsampleAndForget { clock -> FileMetricsStore(dir.resolve("m"), clock = clock) }

    @Test
    fun `sqlite store downsamples and forgets`() = downsampleAndForget { clock -> SqliteMetricsStore(dir.resolve("metrics.db"), clock = clock) }

    private fun downsampleAndForget(open: (() -> Long) -> MetricsStore) {
        val start = 1_700_000_000_000L - Math.floorMod(1_700_000_000_000L, 3_600_000L)
        var now = start
        val store = open { now }
        // Two minutes of 2 s samples at 10 % then 30 % CPU.
        for (i in 0 until 60) {
            now = start + i * 2_000L
            store.append(StatsSample("vm:lima/dev", now, cpuPercent = if (i < 30) 10.0 else 30.0))
        }
        now = start + 3 * 60_000L
        store.append(StatsSample("vm:lima/dev", now, cpuPercent = 50.0))
        store.markGap("vm:lima/dev", start + 1_000, start + 2_000)
        val raw = store.series("vm:lima/dev", Metric.CPU, start, now + 1)
        assertTrue(raw.points.isNotEmpty())
        assertEquals(50.0, raw.points.maxOf { it.max })
        assertEquals(1, raw.gaps.size)
        assertEquals(listOf("vm:lima/dev"), store.targets())

        val older = open { start + 3 * 86_400_000L }
        val m1 = older.series("vm:lima/dev", Metric.CPU, start, start + 3 * 60_000L)
        assertEquals(60_000L, m1.stepMillis)
        assertEquals(listOf(10.0, 30.0), m1.points.take(2).map { it.avg })
        older.close()

        store.forget("vm:lima/dev")
        assertTrue(store.series("vm:lima/dev", Metric.CPU, start, now + 1).points.isEmpty())
        assertTrue(store.targets().isEmpty())
        store.close()
    }

    @Test
    fun `percentile and slope`() {
        assertEquals(95.0, TieredMetricsStore.percentile((1..100).map { it.toDouble() }, 0.95))
        assertEquals(2.0, TieredMetricsStore.slope(listOf(0.0 to 1.0, 1.0 to 3.0, 2.0 to 5.0))!!, 1e-9)
        assertNull(TieredMetricsStore.slope(listOf(0.0 to 1.0)))
    }

    private class MemorySecrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String) = map[key]
        override fun delete(key: String) { map.remove(key) }
    }

    private class RecordingNotifier : Notifier {
        val sent = mutableListOf<String>()
        override suspend fun send(channel: NotificationChannel, title: String, message: String): Result<Unit> {
            sent += "$title: $message"
            return Result.success(Unit)
        }
    }

    private fun inputs(vm: Vm, memoryPercent: Double) = AlertInputs(
        vms = listOf(vm),
        containers = emptyList(),
        samples = mapOf(vm.ref.targetId to StatsSample(vm.ref.targetId, 0, memoryUsedBytes = memoryPercent.toLong(), memoryTotalBytes = 100)),
        hostFree = emptyMap(),
        trends = emptyList(),
        restarts = emptyMap(),
        exited = emptyMap(),
        down = emptySet(),
    )

    @Test
    fun `threshold alert goes pending, firing after its duration, then resolves`() = runBlocking {
        var now = 0L
        val secrets = MemorySecrets()
        val notifier = RecordingNotifier()
        val engine = AlertEngine(dir.resolve("alerts"), ChannelStore(dir.resolve("channels.json"), secrets), notifier, EventBus(), { now })
        val vm = Vm("dev", VmSource.LIMA, VmStatus.RUNNING)
        engine.evaluate(inputs(vm, 99.0))
        assertEquals(AlertState.PENDING, engine.events().firstOrNull { it.ruleId == "builtin.memory" }?.state)
        now = 601_000
        engine.evaluate(inputs(vm, 99.0))
        val firing = engine.firing().firstOrNull { it.ruleId == "builtin.memory" }
        assertNotNull(firing)
        assertTrue(notifier.sent.any { "dev" in it })
        now = 700_000
        engine.evaluate(inputs(vm, 10.0))
        assertTrue(engine.firing().none { it.ruleId == "builtin.memory" })
    }

    @Test
    fun `glob and comparisons`() {
        assertTrue(AlertEngine.glob("tc-*", "tc-dev-1"))
        assertTrue(!AlertEngine.glob("tc-?", "tc-dev"))
        assertTrue(AlertEngine.compare(5.0, controllima.shared.Comparison.LT, 10.0))
    }

    @Test
    fun `ports build flags duplicates and shadowing`() {
        val a = Vm("a", VmSource.LIMA, VmStatus.RUNNING, portForwards = listOf(PortForward(80, 8080)), hostPid = 10)
        val b = Vm("b", VmSource.LIMA, VmStatus.RUNNING, portForwards = listOf(PortForward(80, 8080)), hostPid = 11)
        val entries = PortsService.build(listOf(a, b), emptyList(), listOf(HostListener(99, "nginx", "127.0.0.1", 8080)))
        val forA = entries.first { it.owner == "a" && it.hostPort == 8080 }
        assertNotNull(forA.conflict)
    }

    @Test
    fun `host agent forwards and dead-vm detection come from the log`() {
        // Lima logs "from <guest> to <host>".
        val log = dir.resolve("ha.stderr.log")
        log.writeText(
            """
            {"level":"info","msg":"Forwarding TCP from 127.0.0.1:80 to 127.0.0.1:8080","time":"2026-01-01T00:00:00Z"}
            {"level":"info","msg":"Forwarding TCP from 127.0.0.1:22 to 127.0.0.1:2222","time":"2026-01-01T00:00:00Z"}
            {"level":"info","msg":"Stopping forwarding TCP from 127.0.0.1:22 to 127.0.0.1:2222","time":"2026-01-01T00:00:01Z"}
            """.trimIndent(),
        )
        assertEquals(mapOf(8080 to 80), PortsService.effectiveForwards(log))
        val dead = dir.resolve("dead.log").apply { writeText("""{"level":"error","msg":"virtual machine is no longer live"}""" + "\n") }
        assertTrue(Inventory.hostAgentSaysDead(dead))
    }

    @Test
    fun `log lines parse logrus and journal json`() {
        val l = LogService.logrusLine("""{"level":"warning","msg":"hello","time":"2026-01-01T00:00:00Z","port":22}""", "stderr")
        assertEquals("warning", l.level)
        assertEquals("hello port=22", l.line)
        assertEquals(1767225600000, l.ts)
        val j = LogService.journalLine("""{"MESSAGE":"started","PRIORITY":"3","SYSLOG_IDENTIFIER":"sshd","__REALTIME_TIMESTAMP":"1767225600000000"}""")
        assertEquals("sshd: started", j.line)
        assertEquals("error", j.level)
        val bytes = LogService.journalLine("""{"MESSAGE":[104,105]}""")
        assertEquals("hi", bytes.line)
    }

    @Test
    fun `tail reads only the end of a file`() {
        val f = dir.resolve("big.log")
        f.writeText((1..1000).joinToString("\n") { "line $it" } + "\n")
        assertEquals(listOf("line 999", "line 1000"), LogService.tailLines(f, 2))
    }
}
