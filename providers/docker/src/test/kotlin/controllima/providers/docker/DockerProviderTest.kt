package controllima.providers.docker

import controllima.shared.LogLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class DockerProviderTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    private class FakeTransport(private val routes: (String, String) -> DockerResponse) : DockerTransport {
        val calls = mutableListOf<String>()
        var streamBody: ByteArray = ByteArray(0)
        override fun request(method: String, path: String, body: String?, timeout: Duration): DockerResponse {
            calls += "$method $path"
            return routes(method, path)
        }

        override fun stream(method: String, path: String, body: String?): DockerStream {
            calls += "STREAM $method $path"
            return object : DockerStream {
                override val status = 200
                override val input: InputStream = ByteArrayInputStream(streamBody)
                override fun close() {}
            }
        }
    }

    private fun transport() = FakeTransport { method, path ->
        when {
            method == "GET" && path.startsWith("/containers/json") -> DockerResponse(200, fixture("containers.json"))
            method == "GET" && path.startsWith("/images/json") -> DockerResponse(200, fixture("images.json"))
            method == "GET" && path.endsWith("/stats?stream=false&one-shot=true") -> DockerResponse(200, fixture("stats-1.json"))
            method == "GET" && path.startsWith("/containers/") && path.endsWith("/json") -> DockerResponse(200, fixture("inspect.json"))
            method == "GET" && path == "/system/df" -> DockerResponse(200, fixture("system-df.json"))
            method == "GET" && path == "/networks" -> DockerResponse(200, fixture("networks.json"))
            method == "GET" && path.endsWith("/history") -> DockerResponse(200, fixture("image-history.json"))
            method == "GET" && path.startsWith("/images/") -> DockerResponse(200, fixture("image-inspect.json"))
            method == "POST" && path.contains("/rename") -> DockerResponse(409, """{"message":"name already in use"}""")
            method == "POST" && path.startsWith("/images/prune") -> DockerResponse(200, """{"ImagesDeleted":[{"Deleted":"sha256:bbb"},{"Untagged":"x:1"}],"SpaceReclaimed":1000}""")
            else -> DockerResponse(204, "")
        }
    }

    @Test
    fun parsesContainers() {
        val containers = DockerEngineProvider(transport(), "work").containers()
        assertEquals(listOf("sentry-web", "redis"), containers.map { it.name })
        assertEquals(2, containers[0].ports.size, "IPv4 and IPv6 bindings of one port are merged")
        assertEquals(9000, containers[0].ports[0].publicPort)
        assertEquals(listOf("172.17.0.2"), containers[0].ipAddresses)
        assertEquals(emptyList(), containers[1].ipAddresses)
        assertEquals("work", containers[0].profile)
        assertEquals("container:work/8dfafdbc3a40aaaaaaaaaaaaaaaa", containers[0].targetId)
        assertEquals("sentry", containers[0].labels["com.docker.compose.project"])
    }

    @Test
    fun parsesImagesWithUsage() {
        val images = DockerEngineProvider(transport()).images()
        assertEquals(listOf("getsentry/sentry:24.1.0"), images[0].tags)
        assertEquals(listOf("sentry-web"), images[0].usedBy)
        assertEquals(emptyList(), images[1].tags)
        assertTrue(images[1].dangling)
        assertEquals(null, images[1].containers)
        assertEquals(emptyList(), images[2].tags)
    }

    @Test
    fun parsesInspect() {
        val d = DockerEngineProvider(transport()).inspect("8dfafdbc3a40")
        assertEquals("sentry-web", d.container.name)
        assertEquals(listOf("/entrypoint.sh", "run", "web"), d.command)
        assertEquals("unless-stopped", d.restartPolicy)
        assertEquals(2, d.restartCount)
        assertEquals(listOf(9000, null), d.container.ports.map { it.publicPort })
        assertEquals("/data", d.mounts.single().destination)
        assertEquals(1732540000L, d.container.createdEpochSeconds)
    }

    @Test
    fun statsSampleUsesDockerCpuFormula() {
        val a = DockerJson.parseStats(fixture("stats-1.json"))
        val b = DockerJson.parseStats(fixture("stats-2.json"))
        val s = DockerEngineProvider.sample("c", a, b, 2_000, 0)
        // 0.5e9 / 2e9 * 4 cpus = 100 %
        assertEquals(100.0, s.cpuPercent!!, 0.001)
        assertEquals(220200960L - 10485760L, s.memoryUsedBytes)
        assertEquals(1500.0, s.netRxBytesPerSec)
        assertEquals(0.0, s.netTxBytesPerSec)
        assertEquals(2048.0, s.diskReadBytesPerSec)
        assertNull(DockerEngineProvider.sample("c", null, a, 0, 0).cpuPercent)
    }

    @Test
    fun actionsAndErrors() {
        val t = transport()
        val docker = DockerEngineProvider(t)
        docker.start("8dfafdbc3a40")
        docker.remove("8dfafdbc3a40", force = true)
        assertTrue("POST /containers/8dfafdbc3a40/start" in t.calls)
        assertTrue("DELETE /containers/8dfafdbc3a40?force=true" in t.calls)
        val e = assertFailsWith<DockerException> { docker.rename("8dfafdbc3a40", "taken") }
        assertTrue(e.message!!.contains("name already in use"))
        assertFailsWith<DockerException> { docker.rename("8dfafdbc3a40", "bad name/") }
        assertFailsWith<DockerException> { docker.start("../../images") }
    }

    @Test
    fun pruneHasDryRunAndRefusesNothingInUse() {
        val docker = DockerEngineProvider(transport())
        val preview = docker.pruneImages(dryRun = true)
        assertTrue(preview.dryRun)
        assertEquals(listOf("sha256:bbb", "sha256:ccc"), preview.images)
        val done = docker.pruneImages(dryRun = false)
        assertEquals(listOf("sha256:bbb", "x:1"), done.images)
        assertEquals(1000, done.bytes)
        assertFailsWith<DockerException> { docker.deleteImage("sha256:aaa") }
    }

    @Test
    fun imageDetailAndNetworksAndDf() {
        val docker = DockerEngineProvider(transport())
        val detail = docker.image("sha256:aaa")
        assertEquals("arm64", detail.architecture)
        assertEquals(2, detail.layers.size)
        assertEquals(listOf("bridge", "host"), docker.networks().map { it.name })
        assertEquals("172.17.0.0/16", docker.networks()[0].subnet)
        val df = docker.systemDf("default")
        assertEquals(800, df.containersBytes)
        assertEquals(1000 + 300 + 2048 + 7000L, df.reclaimableBytes)
    }

    @Test
    fun decodesMultiplexedLogs() {
        fun frame(stream: Int, text: String): ByteArray {
            val payload = text.toByteArray()
            val header = byteArrayOf(stream.toByte(), 0, 0, 0, 0, 0, (payload.size shr 8).toByte(), payload.size.toByte())
            return header + payload
        }
        val out = ByteArrayOutputStream()
        out.write(frame(1, "2024-11-25T13:06:40.5Z hello\n2024-11-25T13:06:41Z wor"))
        out.write(frame(1, "ld\n"))
        out.write(frame(2, "2024-11-25T13:06:42Z oops\n"))
        val lines = mutableListOf<LogLine>()
        DockerLogDecoder.decode(ByteArrayInputStream(out.toByteArray()), tty = false) { lines += it }
        assertEquals(listOf("hello", "world", "oops"), lines.map { it.line })
        assertEquals(listOf("stdout", "stdout", "stderr"), lines.map { it.stream })
        assertEquals(1732540000500L, lines[0].ts)
    }

    @Test
    fun pullReportsProgressAndErrors() {
        val t = transport()
        t.streamBody = """{"status":"Pulling from library/redis","id":"7"}
{"status":"Downloading","progress":"[==>  ] 1MB/5MB","id":"abc"}
""".toByteArray()
        val lines = mutableListOf<String>()
        DockerEngineProvider(t).pull("redis:7", lines::add)
        assertEquals(listOf("7: Pulling from library/redis", "abc: Downloading [==>  ] 1MB/5MB"), lines)
        assertTrue(t.calls.last().contains("fromImage=redis&tag=7"))
        t.streamBody = """{"error":"manifest unknown"}""".toByteArray()
        assertFailsWith<DockerException> { DockerEngineProvider(t).pull("redis:nope") {} }
        assertFailsWith<DockerException> { DockerEngineProvider(t).pull("redis 7") {} }
        assertEquals("localhost:5000/app" to "1", DockerEngineProvider.splitRef("localhost:5000/app:1"))
        assertEquals("localhost:5000/app" to "latest", DockerEngineProvider.splitRef("localhost:5000/app"))
    }

    @Test
    fun httpBodyAndErrors() {
        assertEquals("[]", HttpResponse.body("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n[]", "/x"))
        assertFailsWith<DockerException> { HttpResponse.body("HTTP/1.0 500 Oops\r\n\r\n{}", "/x") }
        val input = ByteArrayInputStream("HTTP/1.0 200 OK\r\nX: y\r\n\r\nbody".toByteArray())
        assertEquals(200, HttpResponse.readHead(input, "/x"))
        assertEquals("body", input.readBytes().decodeToString())
    }

    @Test
    fun missingSocketFailsFast() {
        assertFailsWith<DockerException> { UnixSocketTransport("/nonexistent/docker.sock").get("/containers/json") }
    }
}
