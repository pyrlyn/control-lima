package controllima.providers.kube

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubeconfigTest {
    @Test
    fun `k3s config uses current-context and inline data`() {
        val access = Kubeconfig.parse(fixture("k3s.yaml"), null)
        assertEquals("default", access.context)
        assertEquals("https://127.0.0.1:6443", access.server)
        assertContentEquals("k3s-ca".toByteArray(), access.caPem)
        assertContentEquals("k3s-client-cert".toByteArray(), access.clientCertPem)
        assertContentEquals("k3s-client-key".toByteArray(), access.clientKeyPem)
        assertNull(access.token)
        assertTrue("k3s-client-key" !in access.toString())
    }

    @Test
    fun `file references resolve against the kubeconfig directory`() {
        val read = mutableListOf<Path>()
        val access = Kubeconfig.parse(fixture("colima-kube-config.yaml"), "colima-work", Path.of("/home/u/.kube")) {
            read.add(it)
            "work-ca".toByteArray()
        }
        assertEquals(listOf(Path.of("/home/u/.kube/ca/work-ca.crt")), read)
        assertEquals("https://127.0.0.1:16443", access.server)
        assertContentEquals("work-ca".toByteArray(), access.caPem)
        assertEquals("abcA", access.token)
        assertNull(access.clientCertPem)
    }

    @Test
    fun `absolute client certificate paths are read as they are`() {
        val text = """
            clusters:
            - name: c
              cluster:
                server: https://10.0.0.1:6443
                certificate-authority-data: Y2E=
            users:
            - name: u
              user:
                client-certificate: /etc/k/cert.pem
                client-key: /etc/k/key.pem
            contexts:
            - name: x
              context: {cluster: c, user: u}
        """.trimIndent()
        // Flow mappings are outside the supported subset, so this must fail loudly rather than guess.
        assertFailsWith<KubeException> { Kubeconfig.parse(text, "x") }
        val block = text.replace("  context: {cluster: c, user: u}", "  context:\n    cluster: c\n    user: u")
        val files = mapOf("/etc/k/cert.pem" to "cert", "/etc/k/key.pem" to "key")
        val access = Kubeconfig.parse(block, "x", Path.of("/ignored")) { files.getValue(it.toString()).toByteArray() }
        assertContentEquals("cert".toByteArray(), access.clientCertPem)
        assertContentEquals("key".toByteArray(), access.clientKeyPem)
    }

    @Test
    fun `refuses insecure-skip-tls-verify`() {
        val e = assertFailsWith<KubeException> { Kubeconfig.parse(fixture("colima-kube-config.yaml"), "docker-desktop") }
        assertTrue("insecure-skip-tls-verify" in e.message!!)
    }

    @Test
    fun `reports missing pieces`() {
        val config = fixture("colima-kube-config.yaml")
        assertFailsWith<KubeException> { Kubeconfig.parse(config, "nope") }
        assertFailsWith<KubeException> { Kubeconfig.parse(config.replace("https://127.0.0.1:6443", "http://127.0.0.1:6443"), "colima") }
        assertFailsWith<KubeException> { Kubeconfig.parse(config.replace("    client-key-data:", "    other:"), "colima") }
        assertFailsWith<KubeException> { Kubeconfig.parse(config.replace("      certificate-authority-data:", "      other:"), "colima") }
        assertFailsWith<KubeException> { Kubeconfig.parse("a: [x]", null) }
    }

    @Test
    fun `lists contexts`() {
        val config = fixture("colima-kube-config.yaml")
        assertEquals(listOf("colima", "colima-work", "docker-desktop"), Kubeconfig.contextNames(config))
        assertEquals("colima", Kubeconfig.currentContext(config))
    }
}
