package controllima.providers.kube

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KubePemTest {
    private val p256Oid = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val header = if (len < 0x80) byteArrayOf(tag.toByte(), len.toByte()) else byteArrayOf(tag.toByte(), 0x81.toByte(), len.toByte())
        return header + content
    }

    private fun unsigned(n: BigInteger, size: Int): ByteArray {
        val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(size - raw.size) + raw
    }

    private fun pem(label: String, der: ByteArray) =
        "-----BEGIN $label-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)}\n-----END $label-----\n".toByteArray()

    /** RFC 5915 ECPrivateKey, written by hand the way k3s/openssl do. */
    private fun sec1(private: ECPrivateKey, public: ECPublicKey): ByteArray {
        val point = byteArrayOf(0x04) + unsigned(public.w.affineX, 32) + unsigned(public.w.affineY, 32)
        return tlv(
            0x30,
            tlv(0x02, byteArrayOf(1)) + tlv(0x04, unsigned(private.s, 32)) + tlv(0xA0, p256Oid) + tlv(0xA1, tlv(0x03, byteArrayOf(0) + point)),
        )
    }

    private fun signsAndVerifies(private: java.security.PrivateKey, public: java.security.PublicKey, algorithm: String) {
        val data = "kube".toByteArray()
        val signature = Signature.getInstance(algorithm).apply { initSign(private); update(data) }.sign()
        assertTrue(Signature.getInstance(algorithm).apply { initVerify(public); update(data) }.verify(signature))
    }

    @Test
    fun `SEC1 EC keys convert to PKCS#8 that the JDK accepts`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val private = pair.private as ECPrivateKey
        val sec1 = sec1(private, pair.public as ECPublicKey)

        // An `EC PARAMETERS` block before the key is what `openssl ecparam -genkey` writes.
        val text = pem("EC PARAMETERS", p256Oid) + pem("EC PRIVATE KEY", sec1)
        val key = KubePem.privateKey(text) as ECPrivateKey
        assertEquals(private.s, key.s)
        assertEquals(private.params.curve, key.params.curve)
        signsAndVerifies(key, pair.public, "SHA256withECDSA")
    }

    @Test
    fun `SEC1 keys without curve parameters are refused`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bare = tlv(0x30, tlv(0x02, byteArrayOf(1)) + tlv(0x04, unsigned((pair.private as ECPrivateKey).s, 32)))
        assertFailsWith<KubeException> { KubePem.privateKey(pem("EC PRIVATE KEY", bare)) }
    }

    @Test
    fun `PKCS#1 RSA keys convert to the JDK's own PKCS#8 encoding`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8 = pair.private.encoded
        // The last element of PrivateKeyInfo is an OCTET STRING holding the PKCS#1 key (long-form length).
        val octet = findPkcs1(pkcs8)
        assertContentEquals(pkcs8, KubePem.pkcs1ToPkcs8(octet))
        val key = KubePem.privateKey(pem("RSA PRIVATE KEY", octet)) as RSAPrivateCrtKey
        assertEquals((pair.private as RSAPrivateCrtKey).privateExponent, key.privateExponent)
        signsAndVerifies(key, pair.public, "SHA256withRSA")
    }

    private fun findPkcs1(pkcs8: ByteArray): ByteArray {
        // SEQUENCE(len 82 xx xx) INTEGER(02 01 00) AlgorithmIdentifier(30 0d ...) OCTET STRING(04 82 xx xx ...)
        val octetAt = 4 + 3 + 15
        assertEquals(0x04, pkcs8[octetAt].toInt())
        assertEquals(0x82, pkcs8[octetAt + 1].toInt() and 0xFF)
        val len = ((pkcs8[octetAt + 2].toInt() and 0xFF) shl 8) or (pkcs8[octetAt + 3].toInt() and 0xFF)
        return pkcs8.copyOfRange(octetAt + 4, octetAt + 4 + len)
    }

    @Test
    fun `PKCS#8 keys load as they are`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = KubePem.privateKey(pem("PRIVATE KEY", pair.private.encoded))
        assertContentEquals(pair.private.encoded, key.encoded)
    }

    @Test
    fun `bad keys are refused`() {
        assertFailsWith<KubeException> { KubePem.privateKey("nothing here".toByteArray()) }
        assertFailsWith<KubeException> { KubePem.privateKey(pem("ENCRYPTED PRIVATE KEY", byteArrayOf(0x30, 0))) }
        assertFailsWith<KubeException> { KubePem.privateKey(pem("EC PRIVATE KEY", byteArrayOf(0x30, 0x05, 0x02))) }
        assertFailsWith<KubeException> { KubePem.privateKey(pem("PRIVATE KEY", byteArrayOf(0x30, 0x00))) }
    }

    @Test
    fun `trust manager trusts only the given CA`() {
        val ca = fixture("ca.pem").toByteArray()
        val cert = KubePem.certificates(ca).single()
        assertTrue(cert.subjectX500Principal.name.startsWith("CN=k3s-server-ca"))
        val trust = KubePem.trustManager(ca)
        assertEquals(listOf(cert), trust.acceptedIssuers.toList())
        trust.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertFailsWith<KubeException> { KubePem.certificates("not a cert".toByteArray()) }
    }
}
