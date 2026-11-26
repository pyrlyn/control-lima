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
import kotlinx.coroutines.launch
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
            "command -v nerdctl" to ScriptedRunner.ok("yes\n"),
            "nerdctl ps" to ScriptedRunner.ok(
                """[{"Id":"3f6c2a9d1e4b","Name":"web","Image":"docker.io/library/nginx:alpine","State":{"Status":"running"},"Config":{},"NetworkSettings":{}}]""",
            ),
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

    private class FakeSocket(vararg typed: String) : controllima.server.http.WsChannel {
        private val input = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { c -> typed.forEach { c.trySend(it) } }
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var closed: String? = null
        override suspend fun send(text: String) { sent += text }
        override suspend fun receive(): String? = input.receiveCatching().getOrNull()
        override suspend fun close(reason: String) { closed = reason; input.close() }
        fun hangUp() = input.close()
    }

    private fun upgrade(path: String, auth: String): controllima.server.http.Upgrade = runBlocking {
        val (p, q) = path.substringBefore('?') to path.substringAfter('?', "")
        val query = q.split('&').filter { it.isNotEmpty() }.groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
        api.upgrade(Request("GET", p, query, mapOf("Host" to "127.0.0.1:8787", ApiRoutes.TOKEN_HEADER to auth), ByteArray(0)))
    }

    @Test
    fun `web terminal is opt in, admin only and relays a running vm shell`() {
        call("GET", ApiRoutes.VMS)
        val running = "${ApiRoutes.TERMINAL_WS}?target=vm:lima/infra-test&rows=24&cols=80"
        val off = upgrade(running, token) as controllima.server.http.Upgrade.Refused
        assertEquals(403, off.response.status)

        val operator = namedToken(Role.OPERATOR)
        server.services.settings.update(server.services.settings.current.copy(webTerminal = true))
        assertEquals(403, (upgrade(running, operator) as controllima.server.http.Upgrade.Refused).response.status)
        assertEquals(400, (upgrade("${ApiRoutes.TERMINAL_WS}?target=vm:lima/infra-test&rows=24&cols=9999", token) as controllima.server.http.Upgrade.Refused).response.status)

        runner.banner = "$ "
        val socket = FakeSocket("ls\r")
        val session = upgrade(running, token) as controllima.server.http.Upgrade.Accepted
        runBlocking {
            val job = launch { session.session(socket) }
            kotlinx.coroutines.withTimeout(5_000) { while (socket.sent.joinToString("") != "$ ls\r") kotlinx.coroutines.delay(10) }
            socket.hangUp()
            job.join()
        }
        val (cmd, env) = runner.interactives.single()
        assertTrue("-tt" in cmd && "lima-infra-test" in cmd, cmd.toString())
        assertEquals("xterm", env["TERM"])
        val audit = server.services.audit.query().map { it.action }
        assertTrue("terminal.open" in audit && "terminal.close" in audit, audit.toString())

        val stopped = FakeSocket()
        val refused = upgrade("${ApiRoutes.TERMINAL_WS}?target=vm:lima/tc-dev-1&rows=24&cols=80", token) as controllima.server.http.Upgrade.Accepted
        runBlocking { refused.session(stopped) }
        assertTrue(stopped.closed!!.contains("not running"), stopped.closed)
        assertEquals(1, runner.interactives.size, "a stopped VM is never contacted")
    }

    @Test
    fun `query token is not accepted outside websockets`() {
        assertEquals(401, call("GET", "${ApiRoutes.VMS}?token=$token", auth = null).status)
    }

    @Test
    fun `running lima vms with nerdctl become container profiles`() {
        call("GET", ApiRoutes.VMS)
        val profiles = call("GET", ApiRoutes.CONTAINER_PROFILES)
        assertEquals(200, profiles.status)
        assertTrue("\"lima:infra-test\"" in profiles.body, profiles.body)
        assertTrue("lima:tc-dev-1" !in profiles.body, "stopped VMs are not asked")
        val list = call("GET", ApiRoutes.CONTAINERS + "?profile=lima:infra-test")
        assertEquals(200, list.status)
        val web = ApiJson.decodeFromString(controllima.shared.ContainerList.serializer(), list.body).containers.single()
        assertEquals("container:lima:infra-test/3f6c2a9d1e4b", web.targetId)
        assertEquals("docker.io/library/nginx:alpine", web.image)
        assertTrue(runner.calls.any { "lima-infra-test" in it && it.last().startsWith("ids=") })
        assertEquals(400, call("GET", ApiRoutes.CONTAINERS + "?profile=lima:-x").status)
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
