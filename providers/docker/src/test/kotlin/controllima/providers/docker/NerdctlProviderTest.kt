package controllima.providers.docker

import controllima.shared.LogLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class NerdctlProviderTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    private class FakeGuest(private val answer: (String) -> GuestResult) : GuestCommands {
        val commands = mutableListOf<String>()
        override fun run(command: String, timeout: Duration): GuestResult {
            commands += command
            return answer(command)
        }

        override fun stream(command: String, onLine: (String) -> Unit, onExit: () -> Unit): Closeable {
            commands += "STREAM $command"
            onLine("2026-11-20T09:15:04Z hello")
            onExit()
            return Closeable { }
        }
    }

    @Test
    fun listsContainersFromDockerCompatibleInspect() {
        val guest = FakeGuest { GuestResult(true, fixture("nerdctl-inspect.json"), "") }
        val list = NerdctlProvider(guest, "lima:dev").containers()
        assertEquals(listOf("web", "job"), list.map { it.name })
        val web = list.first()
        assertEquals("running", web.state)
        assertEquals("docker.io/library/nginx:alpine", web.image)
        assertEquals(8080, web.ports.single().publicPort)
        assertEquals(listOf("10.4.0.12"), web.ipAddresses)
        assertEquals("container:lima:dev/${web.id}", web.targetId)
        assertEquals("exited", list[1].state)
        assertEquals(listOf(NerdctlProvider.LIST), guest.commands)
    }

    @Test
    fun inspectKeepsExitCodeAndRestartPolicy() {
        val guest = FakeGuest { GuestResult(true, "[${Json.parseToJsonElement(fixture("nerdctl-inspect.json")).jsonArray[1]}]", "") }
        val detail = NerdctlProvider(guest, "lima:dev").inspect("job")
        assertEquals(3, detail.exitCode)
        assertEquals("nerdctl inspect --mode=dockercompat --type=container 'job'", guest.commands.single())
    }

    @Test
    fun refusesUnsafeReferencesBeforeRunningAnything() {
        val guest = FakeGuest { GuestResult(true, "", "") }
        val nerdctl = NerdctlProvider(guest, "lima:dev")
        assertFailsWith<DockerException> { nerdctl.stop("x; rm -rf /") }
        assertFailsWith<DockerException> { nerdctl.rename("web", "a b") }
        assertTrue(guest.commands.isEmpty())
        nerdctl.remove("web", force = true)
        assertEquals("nerdctl rm -f 'web'", guest.commands.single())
    }

    @Test
    fun failuresCarryTheGuestError() {
        val guest = FakeGuest { GuestResult(false, "", "nerdctl: no such container\n") }
        val e = assertFailsWith<DockerException> { NerdctlProvider(guest, "lima:dev").start("web") }
        assertEquals("nerdctl: no such container", e.message)
    }

    @Test
    fun logsSplitTimestampsAndFollowStreams() {
        val guest = FakeGuest { GuestResult(true, "2026-11-20T09:15:03.5Z started\n\n2026-11-20T09:15:04Z ready\n", "") }
        val nerdctl = NerdctlProvider(guest, "lima:dev")
        val lines = mutableListOf<LogLine>()
        var ended = 0
        nerdctl.logs("web", 100, follow = false, sinceSeconds = 60, onEnd = { ended++ }) { lines += it }
        assertEquals(listOf("started", "ready"), lines.map { it.line })
        assertTrue(lines.all { it.ts != null })
        assertEquals("nerdctl logs --timestamps --tail 100 --since 60s 'web' 2>&1", guest.commands.single())
        nerdctl.logs("web", 10, follow = true, sinceSeconds = null, onEnd = { ended++ }) { lines += it }
        assertEquals("STREAM nerdctl logs --timestamps --tail 10 --follow 'web' 2>&1", guest.commands.last())
        assertEquals(2, ended)
        assertEquals("hello", lines.last().line)
    }
}
