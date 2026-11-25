package controllima.providers.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DockerProviderTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    private val transport = object : DockerTransport {
        override fun get(path: String): String = when {
            path.contains("/containers/json") -> fixture("containers.json")
            path.contains("/images/json") -> fixture("images.json")
            else -> throw DockerException("unexpected $path")
        }
    }

    @Test
    fun parsesContainers() {
        val containers = DockerEngineProvider(transport).containers()
        assertEquals(listOf("sentry-web", "redis"), containers.map { it.name })
        assertEquals(9000, containers[0].ports[0].publicPort)
        assertEquals(null, containers[0].ports[1].publicPort)
        assertEquals(listOf("172.17.0.2"), containers[0].ipAddresses)
        assertEquals(emptyList(), containers[1].ipAddresses)
    }

    @Test
    fun parsesImages() {
        val images = DockerEngineProvider(transport).images()
        assertEquals(listOf("getsentry/sentry:24.1.0"), images[0].tags)
        assertEquals(emptyList(), images[1].tags)
        assertEquals(null, images[1].containers)
        assertEquals(emptyList(), images[2].tags)
    }

    @Test
    fun httpBodyAndErrors() {
        assertEquals("[]", HttpResponse.body("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n[]", "/x"))
        assertFailsWith<DockerException> { HttpResponse.body("HTTP/1.0 500 Oops\r\n\r\n{}", "/x") }
    }

    @Test
    fun missingSocketFailsFast() {
        assertFailsWith<DockerException> { UnixSocketTransport("/nonexistent/docker.sock").get("/v1.43/containers/json") }
    }
}
