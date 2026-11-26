package controllima.providers.colima

import controllima.providers.process.ScriptedRunner
import controllima.shared.VmSource
import controllima.shared.VmStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ColimaProviderTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun parsesFixture() {
        val vms = ColimaJson.parseList(fixture("colima-list.ndjson"))
        assertEquals(2, vms.size)
        assertEquals(VmSource.COLIMA, vms[0].source)
        assertEquals(VmStatus.RUNNING, vms[0].status)
        assertEquals(listOf("192.168.106.2"), vms[0].ipAddresses)
        assertEquals("docker", vms[0].runtime)
        assertEquals(VmStatus.STOPPED, vms[1].status)
        assertEquals(emptyList(), vms[1].ipAddresses)
    }

    @Test
    fun parsesStatus() {
        val status = ColimaJson.parseStatus(fixture("colima-status.json"))
        assertEquals("/Users/me/.colima/default/docker.sock", status.dockerSocket)
        assertEquals(false, status.kubernetes)
    }

    @Test
    fun failureBecomesException() {
        assertFailsWith<ColimaException> { ColimaCliProvider(ScriptedRunner(fallback = ScriptedRunner.fail())).list() }
    }

    @Test
    fun dockerSocketPath() {
        assertEquals("/h/.colima/default/docker.sock", ColimaCliProvider(ScriptedRunner(), home = "/h").dockerSocket("default"))
    }

    @Test
    fun actionsUseProfileFlag() {
        val runner = ScriptedRunner()
        val colima = ColimaCliProvider(runner, extraArgs = listOf("--verbose"))
        colima.start("work")
        colima.stop("work", force = true)
        colima.delete("work")
        colima.probe("work")
        assertEquals(
            listOf(
                listOf("colima", "--verbose", "start", "--profile", "work"),
                listOf("colima", "--verbose", "stop", "--force", "--profile", "work"),
                listOf("colima", "--verbose", "delete", "--force", "--profile", "work"),
                listOf("colima", "--verbose", "ssh", "--profile", "work", "--", "true"),
            ),
            runner.calls,
        )
        assertFailsWith<ColimaException> { colima.delete("../x") }
    }

    @Test
    fun discoversProfilesAndLimaInstances() {
        val home = Files.createTempDirectory("colima-home").toFile()
        listOf("default", "work").forEach { p -> home.resolve(".colima/$p").mkdirs(); home.resolve(".colima/$p/colima.yaml").writeText("cpu: 2\n") }
        home.resolve(".colima/_lima/colima-work").mkdirs()
        home.resolve(".colima/_lima/colima-work/ssh.config").writeText("Host lima-colima-work\n")
        val colima = ColimaCliProvider(ScriptedRunner(), home = home.path)
        assertEquals(listOf("default", "work"), colima.discoverProfiles())
        assertEquals("colima", colima.limaInstance("default"))
        assertTrue(colima.sshConfigFile("work")!!.endsWith("_lima/colima-work/ssh.config"))
        assertEquals(null, colima.sshConfigFile("default"))
        home.deleteRecursively()
    }
}
