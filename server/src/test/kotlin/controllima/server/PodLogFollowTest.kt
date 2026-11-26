package controllima.server

import controllima.providers.kube.KubeApiException
import controllima.providers.process.ScriptedRunner
import controllima.shared.LogLine
import controllima.shared.Role
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [LogService.follow] for a pod: lines stream from the cluster, its errors reach the caller, closing stops the request. */
class PodLogFollowTest {
    private val home = limaKubeHome()
    private val calls = mutableListOf<String>()
    private val fake = FakeKube(calls)
    private val kube = KubernetesService(home, { listOf(Vm("dev", VmSource.LIMA, VmStatus.RUNNING, dir = home.resolve("lima/dev").toString())) }, { fake })
    private val server = ControlLimaServer(
        ServerConfig(port = 8787, token = "launch-token-0123456789abcdef"),
        DataPaths(home.resolve("cl/config").toFile(), home.resolve("cl/data").toFile()),
        runner = ScriptedRunner(emptyList()),
        homeDir = home.toString(),
    ).also { it.paths.ensure() }
    private val logs = server.services.let { LogService(it.paths, it.backends, it.inventory, it.containers, kube) { it.settings.current } }
    private val source = "kube:lima/dev/default/web-1#pod:app"

    @AfterTest
    fun cleanup() {
        server.close()
        home.toFile().deleteRecursively()
    }

    @Test
    fun `lines stream until the cluster ends the log`() = runBlocking {
        fake.follow = { onLine ->
            onLine(LogLine(1L, "stdout", "first"))
            onLine(LogLine(2L, "stdout", "second"))
        }
        val lines = mutableListOf<String>()
        withTimeout(5_000) { logs.follow(source, 50, Role.VIEWER) { lines += it.line } }
        assertEquals(listOf("first", "second"), lines)
        assertEquals(listOf("follow default/web-1:app tail=50"), calls)
    }

    @Test
    fun `an API error reaches the caller`() = runBlocking {
        fake.follow = { throw KubeApiException(404, "pods \"web-1\" not found") }
        val error = assertFailsWith<KubeApiException> { withTimeout(5_000) { logs.follow(source, 50, Role.VIEWER) {} } }
        assertEquals("pods \"web-1\" not found", error.message)
    }

    @Test
    fun `closing the view stops the request`() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val firstLine = CompletableDeferred<Unit>()
        fake.follow = { onLine ->
            try {
                onLine(LogLine(null, "stdout", "hello"))
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val view = launch { logs.follow(source, 0, Role.VIEWER) { firstLine.complete(Unit) } }
        withTimeout(5_000) { firstLine.await() }
        view.cancel()
        withTimeout(5_000) { stopped.await() }
        assertTrue(view.isCancelled)
    }
}
