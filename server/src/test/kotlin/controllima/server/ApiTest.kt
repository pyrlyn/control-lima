package controllima.server

import controllima.providers.lima.LimaProvider
import controllima.providers.lima.ProviderException
import controllima.shared.ApiRoutes
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class ApiTest {
    private val config = ServerConfig(token = "secret")

    private class FakeLima(private val vms: List<Vm>? = null) : LimaProvider {
        override fun list(): List<Vm> = vms ?: throw ProviderException("limactl list timed out")
        override fun probe(name: String, timeout: Duration) = true
    }

    private val vms = listOf(Vm("infra-test", VmSource.LIMA, VmStatus.RUNNING, protected = true))

    @Test
    fun healthIsPublic() = testApplication {
        application { module(Api(config, FakeLima(vms))) }
        val response = client.get(ApiRoutes.HEALTH)
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"ok\""))
    }

    @Test
    fun vmsNeedToken() = testApplication {
        application { module(Api(config, FakeLima(vms))) }
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiRoutes.VMS).status)
        val ok = client.get(ApiRoutes.VMS) { header(ApiRoutes.TOKEN_HEADER, "secret") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("infra-test"))
    }

    @Test
    fun foreignOriginIsRejected() = testApplication {
        application { module(Api(config, FakeLima(vms))) }
        val response = client.get(ApiRoutes.VMS) {
            header(ApiRoutes.TOKEN_HEADER, "secret")
            header("Origin", "http://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun providerFailureIs502() = testApplication {
        application { module(Api(config, FakeLima(null))) }
        val response = client.get(ApiRoutes.VMS) { header(ApiRoutes.TOKEN_HEADER, "secret") }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun refusesNonLocalHost() {
        assertFailsWith<IllegalArgumentException> { ServerConfig(host = "0.0.0.0", token = "x") }
    }
}
