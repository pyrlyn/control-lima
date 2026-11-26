package controllima.providers.kube

import controllima.shared.KubeServicePort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubeJsonTest {
    @Test
    fun nodes() {
        val (colima, worker) = KubeJson.nodes(fixture("nodes.json"))
        assertEquals("colima", colima.name)
        assertTrue(colima.ready)
        assertEquals(listOf("control-plane", "master"), colima.roles)
        assertEquals("v1.33.4+k3s1", colima.kubeletVersion)
        assertEquals("Ubuntu 24.04.2 LTS", colima.osImage)
        assertEquals("192.168.5.15", colima.internalIp)
        assertEquals(2000, colima.cpuCapacityMillicores)
        assertEquals(4005748L * 1024, colima.memoryCapacityBytes)
        assertFalse(worker.ready)
        assertNull(worker.internalIp)
    }

    @Test
    fun pods() {
        val pods = KubeJson.pods(fixture("pods.json"))
        assertEquals(listOf("Running", "CrashLoopBackOff", "Terminating", "Pending"), pods.map { it.phase })
        val coredns = pods[0]
        assertEquals("kube-system", coredns.namespace)
        assertEquals("1/1", coredns.ready)
        assertEquals(2, coredns.restarts)
        assertEquals("colima", coredns.node)
        assertEquals("10.42.0.3", coredns.podIp)
        assertEquals(listOf("coredns"), coredns.containers)
        assertEquals(1795168800, coredns.createdEpochSeconds)
        assertEquals("1/2", pods[1].ready)
        assertEquals(6, pods[1].restarts)
        assertEquals("0/1", pods[3].ready)
        assertNull(pods[3].createdEpochSeconds)
    }

    @Test
    fun services() {
        val (kubernetes, web, headless) = KubeJson.services(fixture("services.json"))
        assertEquals("10.43.0.1", kubernetes.clusterIp)
        assertEquals(listOf(KubeServicePort("https", 443, "6443", null, "TCP")), kubernetes.ports)
        assertEquals("NodePort", web.type)
        assertEquals(listOf(KubeServicePort(null, 80, "http", 30080, "TCP"), KubeServicePort("dns", 53, null, null, "UDP")), web.ports)
        assertEquals("ClusterIP", headless.type)
        assertEquals("None", headless.clusterIp)
    }

    @Test
    fun `namespaces and metrics`() {
        assertEquals(listOf("kube-system", "default", "kube-public"), KubeJson.namespaces(fixture("namespaces.json")))
        assertEquals(KubeUsage(188, 1L shl 30), KubeJson.nodeMetrics(fixture("node-metrics.json"))["colima"])
        val pods = KubeJson.podMetrics(fixture("pod-metrics.json"))
        assertEquals(KubeUsage(4, 80L shl 20), pods["default/web-5d9c"])
        assertEquals(KubeUsage(3, 20L shl 20), pods["kube-system/coredns-7f496c8d7d-x2x9k"])
    }

    @Test
    fun `status messages`() {
        assertTrue(KubeJson.statusMessage(fixture("status-forbidden.json"))!!.startsWith("pods \"web-5d9c\" is forbidden"))
        assertNull(KubeJson.statusMessage("""{"kind":"PodList","items":[]}"""))
        assertNull(KubeJson.statusMessage("404 page not found"))
        assertFailsWith<KubeException> { KubeJson.pods("<html>") }
    }
}
