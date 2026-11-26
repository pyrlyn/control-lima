package controllima.server

import controllima.server.backup.Tar
import controllima.server.http.Request
import controllima.server.http.Router
import controllima.server.security.AccessControl
import controllima.server.security.AuditLog
import controllima.server.security.Principal
import controllima.server.security.Redactor
import controllima.server.security.TokenStore
import controllima.server.security.UserStore
import controllima.shared.ApiRoutes
import controllima.shared.Role
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityTest {
    private val dir = Files.createTempDirectory("cl-sec").toFile()
    private val config = ServerConfig(port = 9000, token = "launch-token-0123456789")

    @Test
    fun `config refuses non-local hosts and short tokens`() {
        assertFailsWith<IllegalArgumentException> { ServerConfig(host = "0.0.0.0", token = "launch-token-0123456789") }
        assertFailsWith<IllegalArgumentException> { ServerConfig(token = "short") }
    }

    @Test
    fun `named tokens are stored hashed, verified and revoked`() {
        val store = TokenStore(dir.resolve("tokens.json"))
        val created = store.create("ci", Role.VIEWER, 30)
        assertFalse(created.token in dir.resolve("tokens.json").readText())
        assertEquals(Role.VIEWER, store.verify(created.token)?.role)
        assertNull(store.verify(created.token + "x"))
        assertTrue(store.revoke("ci"))
        assertNull(store.verify(created.token))
    }

    @Test
    fun `expired tokens stop working`() {
        var now = 1_000_000L
        val store = TokenStore(dir.resolve("t2.json")) { now }
        val created = store.create("short", Role.ADMIN, 1)
        assertNotNull(store.verify(created.token))
        now += 2 * 86_400_000L
        assertNull(store.verify(created.token))
    }

    @Test
    fun `host, origin, csrf and websocket-only query token`() {
        val access = AccessControl(config, TokenStore(dir.resolve("t3.json")), UserStore(dir.resolve("u.json")), { false })
        fun req(path: String, headers: Map<String, String>, query: Map<String, List<String>> = emptyMap(), method: String = "GET") =
            Request(method, path, query, headers)
        assertNull(access.checkHostAndOrigin(req("/api/vms", mapOf("Host" to "localhost:9000", "Origin" to "http://127.0.0.1:9000"))))
        assertNotNull(access.checkHostAndOrigin(req("/api/vms", mapOf("Host" to "attacker.test:9000"))))
        assertNotNull(access.checkHostAndOrigin(req("/api/vms", mapOf("Origin" to "http://127.0.0.1:9001"))))

        val q = mapOf("token" to listOf(config.token))
        assertNull(access.authenticate(req("/api/vms", emptyMap(), q)))
        assertEquals(Role.ADMIN, access.authenticate(req("/ws/stats", emptyMap(), q))?.role)

        val owner = access.authenticate(req("/api/vms", mapOf(ApiRoutes.TOKEN_HEADER to config.token)))!!
        val good = req("/api/x", mapOf(ApiRoutes.CSRF_HEADER to access.csrfToken(owner)), method = "POST")
        assertTrue(access.checkCsrf(good, owner))
        val other = Principal("token:x", Role.ADMIN, "token:x", Principal.Via.NAMED_TOKEN)
        assertFalse(access.checkCsrf(good, other))
        assertFalse(access.checkCsrf(req("/api/x", emptyMap(), method = "POST"), owner))
    }

    @Test
    fun `logins lock out after repeated failures`() {
        val users = UserStore(dir.resolve("u2.json"))
        users.create("alice", "correct horse battery", Role.OPERATOR)
        val access = AccessControl(config, TokenStore(dir.resolve("t4.json")), users, { true })
        assertNotNull(access.login("alice", "correct horse battery"))
        repeat(5) { assertNull(access.login("alice", "wrong password!")) }
        assertFailsWith<AccessControl.LockedOut> { access.login("alice", "correct horse battery") }
    }

    @Test
    fun `audit log redacts secret params and filters by actor`() {
        val log = AuditLog(dir.resolve("audit.log"))
        log.record("owner", Role.ADMIN, "token.create", null, mapOf("name" to "ci", "password" to "hunter2hunter2"), "ok", 3)
        log.record("token:ci", Role.VIEWER, "vm.start", "vm:lima/dev", emptyMap(), "ok", 1)
        assertFalse("hunter2" in dir.resolve("audit.log").readText())
        assertEquals(listOf("vm.start"), log.query(actor = "token:ci").map { it.action })
    }

    @Test
    fun `redactor masks values and reports lines without the secret`() {
        val r = Redactor(emptyList())
        val text = "user: me\npassword: s3cretValue\nkey=ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val findings = r.scan(text)
        assertEquals(2, findings.size)
        assertTrue(findings.none { "s3cret" in it })
        val masked = r.redact(text)
        assertFalse("s3cretValue" in masked)
        assertTrue("password: ${Redactor.MASK}" in masked)
        assertFalse("ghp_" in masked)
    }

    @Test
    fun `tar round trip and unsafe names`() {
        val entries = listOf("manifest.json" to "{}".encodeToByteArray(), "lima/default/dev/lima.yaml" to "cpus: 2\n".encodeToByteArray())
        val read = Tar.readGzip(Tar.gzip(entries, 1_700_000_000).inputStream())
        assertEquals("cpus: 2\n", read.getValue("lima/default/dev/lima.yaml").decodeToString())
        assertFailsWith<IllegalArgumentException> { Tar.gzip(listOf("../etc/passwd" to ByteArray(1)), 0) }
        assertFailsWith<IllegalArgumentException> { Tar.readGzip(Tar.gzip(entries, 0).inputStream(), maxBytes = 3) }
    }

    @Test
    fun `router matches params and reports 405`() {
        val router = Router<String>()
        router.add("GET", "/api/vms/{source}/{name}", "vm")
        router.add("DELETE", "/api/vms/{source}/{name}", "delete")
        val m = router.match("GET", "/api/vms/lima/my%2Fvm")!!
        assertEquals("vm", m.value)
        assertEquals("my/vm", m.params["name"])
        assertEquals(false, router.match("POST", "/api/vms/lima/x")!!.methodAllowed)
        assertNull(router.match("GET", "/api/other"))
    }
}
