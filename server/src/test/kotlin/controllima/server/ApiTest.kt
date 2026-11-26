package controllima.server

import controllima.providers.process.ScriptedRunner
import controllima.server.http.Request
import controllima.server.http.Response
import controllima.shared.ApiJson
import controllima.shared.ApiRoutes
import controllima.shared.CreatedToken
import controllima.shared.Role
import controllima.shared.Session
import controllima.shared.VmList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val token = "launch-token-0123456789abcdef"
    private val dir = Files.createTempDirectory("cl-api").toFile()
    private val runner = ScriptedRunner(
        listOf(
            "list --json" to ScriptedRunner.ok(javaClass.getResource("/limactl-list.ndjson")!!.readText()),
            "rename tc-dev-1" to ScriptedRunner.fail("rename is not supported"),
            "limactl info" to ScriptedRunner.ok("""{"version":"2.0.0","templates":[]}"""),
        ),
    )
    private val server = ControlLimaServer(
        ServerConfig(port = 8787, token = token),
        DataPaths(dir.resolve("config"), dir.resolve("data")),
        runner = runner,
        homeDir = dir.path,
    ).also { it.paths.ensure() }
    private val api = Api(server.services)

    @AfterTest
    fun cleanup() {
        server.close()
        dir.deleteRecursively()
    }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        auth: String? = token,
        csrf: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): Response = runBlocking {
        val (p, q) = path.substringBefore('?') to path.substringAfter('?', "")
        val query = q.split('&').filter { it.isNotEmpty() }.groupBy({ it.substringBefore('=') }, { java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) })
        val h = buildMap {
            put("Host", "127.0.0.1:8787")
            auth?.let { put(ApiRoutes.TOKEN_HEADER, it) }
            if (csrf && method != "GET" && auth != null) put(ApiRoutes.CSRF_HEADER, csrfFor(auth))
            putAll(headers)
        }
        api.handle(Request(method, p, query, h, body?.encodeToByteArray() ?: ByteArray(0)))
    }

    private fun csrfFor(auth: String): String = runBlocking {
        val r = api.handle(Request("GET", ApiRoutes.SESSION, emptyMap(), mapOf("Host" to "127.0.0.1:8787", ApiRoutes.TOKEN_HEADER to auth)))
        ApiJson.decodeFromString(Session.serializer(), r.body).csrfToken
    }

    private fun namedToken(role: Role): String {
        val r = call("POST", ApiRoutes.TOKENS, """{"name":"t-${role.name.lowercase()}","role":"${role.name}"}""")
        assertEquals(201, r.status, r.body)
        return ApiJson.decodeFromString(CreatedToken.serializer(), r.body).token
    }

    @Test
    fun `health is public and carries the version`() {
        val r = call("GET", "/health", auth = null)
        assertEquals(200, r.status)
        assertTrue("\"status\":\"ok\"" in r.body)
    }

    @Test
    fun `api needs a token, a local host and a local origin`() {
        assertEquals(401, call("GET", ApiRoutes.VMS, auth = null).status)
        assertEquals(401, call("GET", ApiRoutes.VMS, auth = "wrong-token-0123456789").status)
        assertEquals(403, call("GET", ApiRoutes.VMS, headers = mapOf("Host" to "evil.example:8787")).status)
        assertEquals(403, call("GET", ApiRoutes.VMS, headers = mapOf("Origin" to "http://evil.example")).status)
        assertEquals(200, call("GET", ApiRoutes.VMS, headers = mapOf("Origin" to "http://localhost:8787")).status)
    }

    @Test
    fun `query token is not accepted outside websockets`() {
        assertEquals(401, call("GET", "${ApiRoutes.VMS}?token=$token", auth = null).status)
    }

    @Test
    fun `lists vms from limactl json`() {
        val r = call("GET", ApiRoutes.VMS)
        assertEquals(200, r.status)
        val vms = ApiJson.decodeFromString(VmList.serializer(), r.body).vms
        assertEquals(setOf("infra-test", "tc-dev-1", "broken-one", "lima-protected"), vms.map { it.name }.toSet())
        assertEquals("no-store", r.headers["Cache-Control"])
        assertEquals("DENY", r.headers["X-Frame-Options"])
    }

    @Test
    fun `writes need a csrf token`() {
        assertEquals(403, call("POST", "${ApiRoutes.VMS}/lima/tc-dev-1/start", csrf = false).status)
    }

    @Test
    fun `protected vm can never be deleted, even by the owner with the right name`() {
        val r = call("DELETE", "${ApiRoutes.VMS}/lima/infra-test", """{"confirmName":"infra-test"}""")
        assertEquals(409, r.status)
        assertTrue("protected" in r.body)
        assertTrue(runner.calls.none { "delete" in it })
    }

    @Test
    fun `delete needs the typed name and a stopped vm`() {
        assertEquals(400, call("DELETE", "${ApiRoutes.VMS}/lima/tc-dev-1", """{"confirmName":"tc-dev"}""").status)
        val r = call("DELETE", "${ApiRoutes.VMS}/lima/tc-dev-1", """{"confirmName":"tc-dev-1"}""")
        assertEquals(202, r.status, r.body)
        assertTrue("actionId" in r.body)
    }

    @Test
    fun `roles are enforced`() {
        val viewer = namedToken(Role.VIEWER)
        val operator = namedToken(Role.OPERATOR)
        assertEquals(200, call("GET", ApiRoutes.VMS, auth = viewer).status)
        assertEquals(403, call("POST", "${ApiRoutes.VMS}/lima/tc-dev-1/start", auth = viewer).status)
        assertEquals(403, call("DELETE", "${ApiRoutes.VMS}/lima/tc-dev-1", """{"confirmName":"tc-dev-1"}""", auth = operator).status)
        assertEquals(403, call("POST", "${ApiRoutes.VMS}/lima/infra-test/stop?force=true", auth = operator).status)
        assertEquals(403, call("GET", ApiRoutes.AUDIT, auth = operator).status)
        assertEquals(202, call("POST", "${ApiRoutes.VMS}/lima/tc-dev-1/start", auth = operator).status)
    }

    @Test
    fun `state conflicts answer 409`() {
        assertEquals(409, call("POST", "${ApiRoutes.VMS}/lima/infra-test/start").status)
        assertEquals(409, call("POST", "${ApiRoutes.VMS}/lima/tc-dev-1/stop").status)
        assertEquals(409, call("DELETE", "${ApiRoutes.VMS}/lima/infra-test", """{"confirmName":"x"}""").status)
    }

    @Test
    fun `backend failures map to 502 and unknown vms to 404`() {
        val r = call("POST", "${ApiRoutes.VMS}/lima/tc-dev-1/rename", """{"newName":"tc-dev-2","confirmName":"tc-dev-1"}""")
        assertEquals(502, r.status, r.body)
        assertEquals(404, call("GET", "${ApiRoutes.VMS}/lima/nope").status)
        assertEquals(400, call("GET", "${ApiRoutes.VMS}/lima/bad%20name").status)
    }

    @Test
    fun `writes are audited`() {
        call("POST", "${ApiRoutes.VMS}/lima/tc-dev-1/start")
        val audit = call("GET", ApiRoutes.AUDIT)
        assertEquals(200, audit.status)
        assertTrue("/api/vms/lima/tc-dev-1/start" in audit.body, audit.body)
    }

    @Test
    fun `settings reject unsafe binary paths`() {
        val r = call("PUT", ApiRoutes.SETTINGS, """{"limactlPath":"/bin/sh"}""")
        assertEquals(400, r.status, r.body)
    }

    @Test
    fun `backup round trip with settings only`() {
        val created = call("POST", ApiRoutes.BACKUPS, """{"targets":[],"includeSettings":true}""")
        // Nothing to back up yet (no config.json written, fixture VM dirs do not exist).
        assertEquals(400, created.status, created.body)
        call("PUT", ApiRoutes.SETTINGS, """{"liveIntervalSeconds":3}""")
        val ok = call("POST", ApiRoutes.BACKUPS, """{"targets":[],"includeSettings":true}""")
        assertEquals(201, ok.status, ok.body)
        val list = call("GET", ApiRoutes.BACKUPS)
        assertTrue("controllima/config.json" in list.body)
    }

    @Test
    fun `unknown api path is 404 and wrong method is 405`() {
        assertEquals(404, call("GET", "/api/nope").status)
        assertEquals(405, call("PUT", ApiRoutes.VMS, "{}").status)
    }

    @Test
    fun `snapshots are offered only for qemu and apply needs a typed name`() {
        val vz = call("GET", "/api/vms/lima/infra-test/snapshots")
        assertEquals(200, vz.status)
        assertTrue("\"supported\":false" in vz.body, vz.body)
        val apply = call("POST", "/api/vms/lima/tc-dev-1/snapshots/clean/apply", """{"confirmName":"wrong"}""")
        assertEquals(400, apply.status)
        val create = call("POST", "/api/vms/lima/infra-test/snapshots", """{"tag":"clean"}""")
        assertEquals(409, create.status)
        assertEquals(400, call("POST", "/api/vms/lima/tc-dev-1/snapshots", """{"tag":"--force"}""").status)
    }

    @Test
    fun `config is admin only and cannot be saved while running`() {
        val viewer = namedToken(Role.VIEWER)
        assertEquals(403, call("GET", "/api/vms/lima/tc-dev-1/config", auth = viewer).status)
        val running = call("PUT", "/api/vms/lima/infra-test/config", """{"yaml":"cpus: 2","confirmName":"infra-test"}""")
        assertEquals(409, running.status)
    }

    @Test
    fun `validate runs limactl validate on a temp file and reports lima's messages`() {
        val r = call("POST", "/api/lima/validate", """{"yaml":"cpus: 2"}""")
        assertEquals(200, r.status, r.body)
        val call = runner.calls.last { "validate" in it }
        assertEquals(listOf("limactl", "--log-format=json", "validate"), call.take(3))
        assertTrue(call[3].endsWith("/check.yaml"))
        assertTrue("\"ok\":true" in r.body)
    }
}
