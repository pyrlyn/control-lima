package controllima.providers.kube

import controllima.shared.KubeCluster
import controllima.shared.LogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpKubeClientTest {
    private class FakeHttp(private val routes: Map<String, KubeResponse>) : KubeHttp {
        val gets = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var closed = false

        override fun get(path: String): KubeResponse {
            gets += path
            return routes[path] ?: KubeResponse(404, """{"kind":"Status","message":"the server could not find the requested resource","code":404}""")
        }

        override fun delete(path: String): KubeResponse {
            deletes += path
            return routes["DELETE $path"] ?: KubeResponse(200, "{}")
        }

        val forwards = mutableListOf<String>()

        override suspend fun portForward(path: String): KubePortStream {
            forwards += path
            throw KubeException("no cluster in tests")
        }

        override fun close() { closed = true }
    }

    private val cluster = KubeCluster("colima/default", "Colima", "colima", "default", "colima", "https://127.0.0.1:6443", true)

    private fun ok(name: String) = KubeResponse(200, fixture(name))

    private val withMetrics = mapOf(
        "/api/v1/nodes" to ok("nodes.json"),
        "/api/v1/namespaces" to ok("namespaces.json"),
        "/api/v1/pods" to ok("pods.json"),
        "/api/v1/services" to ok("services.json"),
        "/apis/metrics.k8s.io/v1beta1" to KubeResponse(200, """{"kind":"APIResourceList"}"""),
        "/apis/metrics.k8s.io/v1beta1/nodes" to ok("node-metrics.json"),
        "/apis/metrics.k8s.io/v1beta1/pods" to ok("pod-metrics.json"),
    )

    @Test
    fun `overview merges metrics-server usage`() {
        val overview = HttpKubeClient(FakeHttp(withMetrics)).overview(cluster)
        assertNull(overview.error)
        assertTrue(overview.metricsAvailable)
        assertEquals(listOf("default", "kube-public", "kube-system"), overview.namespaces)
        assertEquals(188, overview.nodes[0].cpuUsageMillicores)
        assertEquals(1L shl 30, overview.nodes[0].memoryUsageBytes)
        assertNull(overview.nodes[1].cpuUsageMillicores)
        val web = overview.pods.single { it.name == "web-5d9c" }
        assertEquals(4, web.cpuMillicores)
        assertEquals(80L shl 20, web.memoryBytes)
        assertNull(overview.pods.single { it.name == "pending" }.cpuMillicores)
        assertEquals(3, overview.services.size)
    }

    @Test
    fun `missing metrics-server is not an error`() {
        val routes = withMetrics.filterKeys { !it.startsWith("/apis/metrics") } +
            ("/apis/metrics.k8s.io/v1beta1/pods" to KubeResponse(503, "service unavailable"))
        val overview = HttpKubeClient(FakeHttp(routes)).overview(cluster)
        assertNull(overview.error)
        assertFalse(overview.metricsAvailable)
        assertTrue(overview.pods.all { it.cpuMillicores == null && it.memoryBytes == null })
        assertTrue(overview.nodes.all { it.cpuUsageMillicores == null })
    }

    @Test
    fun `namespaced lists use namespaced paths`() {
        val http = FakeHttp(
            mapOf(
                "/api/v1/namespaces/default/pods" to ok("pods.json"),
                "/api/v1/namespaces/default/services" to ok("services.json"),
            ),
        )
        val client = HttpKubeClient(http)
        client.pods("default")
        client.services("default")
        assertEquals(
            listOf("/api/v1/namespaces/default/pods", "/apis/metrics.k8s.io/v1beta1/namespaces/default/pods", "/api/v1/namespaces/default/services"),
            http.gets,
        )
        assertFailsWith<KubeException> { client.pods("../../apis") }
        assertEquals(3, http.gets.size)
    }

    @Test
    fun `API errors carry the Status message`() {
        val http = FakeHttp(mapOf("DELETE /api/v1/namespaces/default/pods/web-5d9c" to KubeResponse(403, fixture("status-forbidden.json"))))
        val e = assertFailsWith<KubeApiException> { HttpKubeClient(http).deletePod("default", "web-5d9c") }
        assertEquals(403, e.status)
        assertTrue(e.message!!.startsWith("pods \"web-5d9c\" is forbidden"))

        val plain = assertFailsWith<KubeApiException> { HttpKubeClient(FakeHttp(mapOf("/api/v1/nodes" to KubeResponse(500, "boom")))).nodes() }
        assertEquals("Kubernetes API returned HTTP 500", plain.message)
    }

    @Test
    fun `overview reports failures instead of throwing`() {
        val overview = HttpKubeClient(FakeHttp(mapOf("/api/v1/nodes" to KubeResponse(401, """{"kind":"Status","message":"Unauthorized"}""")))).overview(cluster)
        assertEquals("Unauthorized", overview.error)
        assertEquals(cluster, overview.cluster)
    }

    @Test
    fun `delete pod validates names before building the path`() {
        val http = FakeHttp(emptyMap())
        val client = HttpKubeClient(http)
        client.deletePod("default", "web-5d9c")
        assertEquals(listOf("/api/v1/namespaces/default/pods/web-5d9c"), http.deletes)
        assertFailsWith<KubeException> { client.deletePod("default", "web/../../x") }
        assertFailsWith<KubeException> { client.deletePod("Default", "web") }
        assertEquals(1, http.deletes.size)
    }

    @Test
    fun `pod logs parse timestamps and validate parameters`() {
        val path = "/api/v1/namespaces/default/pods/web-5d9c/log?timestamps=true&tailLines=50&container=app"
        val body = "2026-11-26T10:00:00.123456789Z started\n2026-11-26T10:00:01Z listening on :8080\n\nno timestamp\n"
        val http = FakeHttp(mapOf(path to KubeResponse(200, body)))
        val client = HttpKubeClient(http)
        val lines = client.podLogs("default", "web-5d9c", "app", 50)
        assertEquals(
            listOf(
                LogLine(1795687200123, "stdout", "started"),
                LogLine(1795687201000, "stdout", "listening on :8080"),
                LogLine(null, "stdout", "no timestamp"),
            ),
            lines,
        )
        assertFailsWith<KubeException> { client.podLogs("default", "web-5d9c", "app&follow=true", 50) }
        assertFailsWith<KubeException> { client.podLogs("default", "web-5d9c", null, 0) }
        assertFailsWith<KubeException> { client.podLogs("default", "web-5d9c", null, 1_000_000) }
        assertEquals(listOf(path), http.gets)
    }

    @Test
    fun `close closes the transport`() {
        val http = FakeHttp(emptyMap())
        HttpKubeClient(http).close()
        assertTrue(http.closed)
    }

    private val webService = mapOf(
        "/api/v1/namespaces/default/services/web" to ok("service-web.json"),
        "/api/v1/namespaces/default/pods?labelSelector=app%3Dweb%2Ctier%3Dfront" to ok("pods-web.json"),
    )

    @Test
    fun `service ports lead to a ready pod and its target port`() {
        val client = HttpKubeClient(FakeHttp(webService))
        // web-a sorts first but is not ready.
        assertEquals("web-b" to 8080, client.serviceBackend("default", "web", 80))
        assertEquals("web-b" to 9100, client.serviceBackend("default", "web", 9090))
        assertEquals("web-b" to 8443, client.serviceBackend("default", "web", 8443))
    }

    @Test
    fun `service forwards refuse UDP, unknown ports and services without pods`() {
        val client = HttpKubeClient(FakeHttp(webService))
        assertTrue(assertFailsWith<KubeException> { client.serviceBackend("default", "web", 53) }.message!!.contains("no TCP port 53"))
        assertFailsWith<KubeException> { client.serviceBackend("default", "web", 1234) }
        assertFailsWith<KubeException> { client.serviceBackend("default", "../x", 80) }
        val noPods = HttpKubeClient(FakeHttp(webService + ("/api/v1/namespaces/default/pods?labelSelector=app%3Dweb%2Ctier%3Dfront" to KubeResponse(200, """{"items":[]}"""))))
        assertTrue(assertFailsWith<KubeException> { noPods.serviceBackend("default", "web", 80) }.message!!.contains("no running, ready pod"))
    }

    @Test
    fun `pod port forward uses the portforward subresource`() {
        val http = FakeHttp(emptyMap())
        val client = HttpKubeClient(http)
        assertFailsWith<KubeException> { kotlinx.coroutines.runBlocking { client.portForward("default", "web-b", 8080) } }
        assertFailsWith<KubeException> { kotlinx.coroutines.runBlocking { client.portForward("default", "web-b", 70000) } }
        assertFailsWith<KubeException> { kotlinx.coroutines.runBlocking { client.portForward("default", "Bad_Pod", 80) } }
        assertEquals(listOf("/api/v1/namespaces/default/pods/web-b/portforward?ports=8080"), http.forwards)
    }

    @Test
    fun `pods carry their declared container ports`() {
        val pod = KubeJson.pods(fixture("pods-web.json")).single { it.name == "web-c" }
        assertEquals(listOf(8080, 9100), pod.ports.map { it.containerPort })
        assertEquals("http", pod.ports[0].name)
        assertEquals(mapOf("app" to "web", "tier" to "front"), KubeJson.service(fixture("service-web.json")).selector)
    }
}
