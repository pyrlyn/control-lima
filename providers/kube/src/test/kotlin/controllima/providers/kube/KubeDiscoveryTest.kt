package controllima.providers.kube

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubeDiscoveryTest {
    private fun tempHome(): Path = Files.createTempDirectory("kube-home")

    @Test
    fun `finds Colima contexts in the home kubeconfig`() {
        val home = tempHome()
        home.resolve(".kube/ca").createDirectories()
        home.resolve(".kube/config").writeText(fixture("colima-kube-config.yaml"))
        home.resolve(".kube/ca/work-ca.crt").writeText("work-ca")

        val targets = KubeDiscovery(home).discover(listOf("default", "work", "missing"), emptyList())
        val (default, work, missing) = targets.map { it.cluster }

        assertEquals("colima/default", default.id)
        assertEquals("colima", default.context)
        assertEquals("https://127.0.0.1:6443", default.server)
        assertTrue(default.available)
        assertNull(default.error)

        assertEquals("colima/work", work.id)
        assertEquals("colima-work", work.context)
        assertEquals("Colima work", work.label)
        assertTrue(work.available)
        assertContentEquals("work-ca".toByteArray(), targets[1].access().caPem)

        assertEquals("colima-missing", missing.context)
        assertFalse(missing.available)
        assertTrue(missing.error!!.contains("not found"))
    }

    @Test
    fun `Colima without a kubeconfig is listed as unavailable`() {
        val cluster = KubeDiscovery(tempHome()).discover(listOf("default"), emptyList()).single().cluster
        assertFalse(cluster.available)
        assertTrue(cluster.error!!.contains("not found"))
    }

    @Test
    fun `finds Lima VMs with a copied kubeconfig and skips the rest`() {
        val lima = tempHome().resolve(".lima")
        lima.resolve("k3s/copied-from-guest").createDirectories()
        lima.resolve("k3s/copied-from-guest/kubeconfig.yaml").writeText(fixture("k3s.yaml"))
        lima.resolve("plain").createDirectories()
        lima.resolve("broken/copied-from-guest").createDirectories()
        lima.resolve("broken/copied-from-guest/kubeconfig.yaml").writeText("current-context: x\n")

        val targets = KubeDiscovery(tempHome()).discover(emptyList(), listOf(lima.resolve("k3s"), lima.resolve("plain"), lima.resolve("broken")))
        assertEquals(listOf("lima/k3s", "lima/broken"), targets.map { it.cluster.id })

        val k3s = targets[0].cluster
        assertEquals("lima", k3s.source)
        assertEquals("k3s", k3s.vmName)
        assertEquals("default", k3s.context)
        assertTrue(k3s.available)
        assertEquals("https://127.0.0.1:6443", targets[0].access().server)

        assertFalse(targets[1].cluster.available)
        assertTrue(targets[1].cluster.error!!.isNotBlank())
    }
}
