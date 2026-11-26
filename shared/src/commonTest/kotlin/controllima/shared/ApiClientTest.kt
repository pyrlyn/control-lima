package controllima.shared

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs a suspend block whose fakes never actually suspend; avoids a coroutines-test dependency. */
private fun <T> runNow(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

class ApiClientTest {
    private class FakeTransport(private val responses: Map<String, HttpResult>) : HttpTransport {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        override suspend fun request(method: String, path: String, body: String?, headers: Map<String, String>): HttpResult {
            calls += Triple(method, path, headers)
            return responses["$method $path"] ?: HttpResult(404, """{"code":"not_found","message":"no $path"}""")
        }
    }

    private val session = ApiJson.encodeToString(Session.serializer(), Session("launch", Role.ADMIN, "csrf1", listOf("infra-test"), BuildInfo.VERSION))

    @Test
    fun readsSendTokenAndWritesFetchCsrfFirst() {
        val transport = FakeTransport(
            mapOf(
                "GET /api/session" to HttpResult(200, session),
                "POST /api/vms/lima/dev/start" to HttpResult(202, """{"actionId":"a1"}"""),
                "GET /api/vms" to HttpResult(200, """{"vms":[],"errors":[{"source":"colima","message":"down"}]}"""),
            ),
        )
        val client = ApiClient(transport, "tok")
        assertEquals("colima", runNow { client.vms() }.errors.single().source)
        assertEquals("a1", runNow { client.startVm(VmRef(VmSource.LIMA, "dev")) }.actionId)
        val (method, path, headers) = transport.calls.last()
        assertEquals("POST", method)
        assertEquals("/api/vms/lima/dev/start", path)
        assertEquals("tok", headers[ApiRoutes.TOKEN_HEADER])
        assertEquals("csrf1", headers[ApiRoutes.CSRF_HEADER])
        assertEquals(null, transport.calls.first().third[ApiRoutes.CSRF_HEADER])
    }

    @Test
    fun errorsBecomeApiException() {
        val client = ApiClient(FakeTransport(mapOf("GET /api/vms" to HttpResult(502, """{"code":"lima_unavailable","message":"timed out"}"""))), "t")
        val e = assertFailsWith<ApiException> { runNow { client.vms() } }
        assertEquals(502, e.status)
        assertEquals("lima_unavailable", e.code)
    }

    @Test
    fun forceStopKeepsHomeQuery() {
        val ref = VmRef(VmSource.LIMA, "dev", "/h")
        val transport = FakeTransport(
            mapOf(
                "GET /api/session" to HttpResult(200, session),
                "POST /api/vms/lima/dev/stop?home=%2Fh&force=true" to HttpResult(202, """{"actionId":"a2"}"""),
            ),
        )
        assertEquals("a2", runNow { ApiClient(transport, "t").stopVm(ref, force = true) }.actionId)
    }
}
