package controllima.providers.kube

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubeQuantityTest {
    @Test
    fun `cpu quantities become millicores`() {
        assertEquals(250, KubeQuantity.millicores("250m"))
        assertEquals(1000, KubeQuantity.millicores("1"))
        assertEquals(1500, KubeQuantity.millicores("1.5"))
        assertEquals(188, KubeQuantity.millicores("187654321n"))
        assertEquals(3, KubeQuantity.millicores("2500u"))
        assertNull(KubeQuantity.millicores(null))
    }

    @Test
    fun `memory quantities become bytes`() {
        assertEquals(128L * 1024 * 1024, KubeQuantity.bytes("128Mi"))
        assertEquals(1L shl 30, KubeQuantity.bytes("1Gi"))
        assertEquals(12345L * 1024, KubeQuantity.bytes("12345Ki"))
        assertEquals(1000, KubeQuantity.bytes("1e3"))
        assertEquals(1000, KubeQuantity.bytes("1E3"))
        assertEquals(2_000_000_000, KubeQuantity.bytes("2G"))
        assertEquals(1500, KubeQuantity.bytes("1.5k"))
        assertEquals(BigDecimal.TEN.pow(18), KubeQuantity.parse("1E"))
    }

    @Test
    fun `junk is not a quantity`() {
        listOf("", "abc", "1Zi", "1.2.3", "Mi", "1 Mi").forEach { assertNull(KubeQuantity.parse(it), it) }
    }

    @Test
    fun `names follow DNS-1123`() {
        assertTrue(KubeNames.isLabel("kube-system"))
        assertTrue(KubeNames.isSubdomain("coredns-7f496c8d7d-x2x9k"))
        assertTrue(KubeNames.isSubdomain("a.b-c.d"))
        assertFalse(KubeNames.isLabel("a.b"))
        assertFalse(KubeNames.isLabel("a".repeat(64)))
        listOf("", "-a", "a-", "A", "a_b", "a/b", "..", "a/../b", "a?b", "a b", "a%2f").forEach {
            assertFalse(KubeNames.isSubdomain(it), it)
            assertFalse(KubeNames.isLabel(it), it)
        }
        assertFailsWith<KubeException> { KubeNames.namespace("../x") }
        assertFailsWith<KubeException> { KubeNames.pod("x/log") }
    }
}
