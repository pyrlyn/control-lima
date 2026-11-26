package controllima.cli

import controllima.shared.ContainerList
import controllima.shared.SourceError
import controllima.shared.Vm
import controllima.shared.VmList
import controllima.shared.VmSource
import controllima.shared.VmStatus
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliOptionsTest {
    @Test
    fun defaults() = assertEquals(CliOptions(), CliOptions.parse(emptyList()))

    @Test
    fun flagsAndPassthrough() {
        val options = CliOptions.parse(listOf("--port", "9000", "--lima-home", "/tmp/lima", "--open", "--", "--debug", "--tty=false"))
        assertEquals(9000, options.port)
        assertEquals("/tmp/lima", options.limaHome)
        assertEquals(true, options.open)
        assertEquals(listOf("--debug", "--tty=false"), options.passthrough)
    }

    @Test
    fun unknownFlagFails() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(listOf("--debug")) }
    }

    @Test
    fun badPortFails() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(listOf("--port", "0")) }
    }

    @Test
    fun relativeDataDirFails() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(listOf("--data-dir", "data")) }
    }

    @Test
    fun commandWithArgsAndGlobalFlagsAnywhere() {
        val options = CliOptions.parse(listOf("--data-dir", "/tmp/cl", "token", "create", "--name", "ci", "--port", "9001", "--role", "viewer"))
        assertEquals("token", options.command)
        assertEquals(listOf("create", "--name", "ci", "--role", "viewer"), options.commandArgs)
        assertEquals(9001, options.port)
        assertEquals("/tmp/cl", options.dataDir)
    }

    @Test
    fun unknownCommandFails() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(listOf("launch")) }
    }

    @Test
    fun commandArgs() {
        val args = CommandArgs(listOf("id-1", "--target", "a", "--target", "b", "--json"), setOf("--target"), setOf("--json"))
        assertEquals(listOf("id-1"), args.positionals)
        assertEquals(listOf("a", "b"), args.all("--target"))
        assertTrue("--json" in args.flags)
        assertFailsWith<IllegalArgumentException> { CommandArgs(listOf("--nope"), emptySet()) }
    }
}

class CommandsTest {
    private val dir = Files.createTempDirectory("cl-cli").toFile()

    private fun run(vararg args: String): Pair<Int, String> {
        val bytes = ByteArrayOutputStream()
        val code = Commands(CliOptions.parse(listOf("--data-dir", dir.absolutePath) + args), PrintStream(bytes)).run()
        return code to bytes.toString()
    }

    @Test
    fun `token create, list and revoke`() {
        val (code, secret) = run("token", "create", "--name", "ci", "--role", "operator", "--expires", "30d")
        assertEquals(Exit.OK, code)
        assertTrue(secret.trim().length >= 32)
        val (_, list) = run("token", "list")
        assertTrue(list.lines()[1].contains("ci"))
        assertTrue(list.contains("operator"))
        // Only the hash is on disk.
        assertFalse(dir.resolve("tokens.json").readText().contains(secret.trim()))
        run("token", "revoke", "ci")
        assertEquals(1, run("token", "list").second.trim().lines().size)
    }

    @Test
    fun `token usage errors`() {
        assertFailsWith<UsageError> { run("token", "create", "--name", "x", "--role", "root") }
        assertFailsWith<UsageError> { run("token", "create", "--role", "admin") }
        assertFailsWith<UsageError> { run("token", "create", "--name", "x", "--role", "admin", "--expires", "0d") }
        assertFailsWith<IllegalStateException> { run("token", "revoke", "missing") }
    }

    @Test
    fun `status rendering and exit code`() {
        val vms = VmList(
            listOf(Vm("dev", VmSource.LIMA, VmStatus.RUNNING, cpus = 4, memoryBytes = 4L shl 30)),
            listOf(SourceError("colima", "not running")),
        )
        val text = Commands.renderStatus(Commands.Status(vms, listOf(ContainerList("default", emptyList(), "socket missing", unavailable = true))))
        assertTrue(text.startsWith("VM "))
        assertTrue(text.contains("dev") && text.contains("running") && text.contains("4.0 GiB"))
        assertTrue(text.contains("! colima: not running"))
        assertTrue(text.contains("Colima default: socket missing"))
        assertFalse(Commands.limaUnavailable(vms))
        assertTrue(Commands.limaUnavailable(VmList(emptyList(), listOf(SourceError("lima", "limactl: not found")))))
    }

    @Test
    fun `vm names resolve by name or target id`() {
        val list = VmList(listOf(Vm("dev", VmSource.LIMA, VmStatus.STOPPED), Vm("default", VmSource.COLIMA, VmStatus.RUNNING)))
        assertEquals(VmSource.COLIMA, Commands.resolveVm(list, "default").source)
        assertEquals("dev", Commands.resolveVm(list, "vm:lima/dev").name)
        assertFailsWith<IllegalStateException> { Commands.resolveVm(list, "nope") }
        assertEquals("new", Commands.resolveVm(list, "new", mustExist = false).name)
    }

    @Test
    fun `table pads columns`() {
        assertEquals("A     B\nlong  x\n", Commands.table(listOf("A", "B"), listOf(listOf("long", "x"))))
    }
}
