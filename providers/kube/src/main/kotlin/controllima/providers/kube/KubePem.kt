package controllima.providers.kube

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * PEM certificates and private keys with the JDK alone (no BouncyCastle is approved). The JDK reads only
 * PKCS#8 keys, while k3s writes EC keys as SEC1 (`EC PRIVATE KEY`) and older tools write RSA keys as
 * PKCS#1 (`RSA PRIVATE KEY`), so both are wrapped into PKCS#8 with a few lines of DER.
 */
object KubePem {
    private val BLOCK = Regex("-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----")

    private val EC_PUBLIC_KEY_OID = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    private val RSA_ENCRYPTION_ALG = byteArrayOf(
        0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
    )

    fun certificates(pem: ByteArray): List<X509Certificate> {
        val certs = runCatching { CertificateFactory.getInstance("X.509").generateCertificates(pem.inputStream()) }
            .getOrElse { throw KubeException("invalid certificate PEM: ${it.message}", it) }
        return certs.filterIsInstance<X509Certificate>().ifEmpty { throw KubeException("no certificate in PEM") }
    }

    /** Trusts exactly the certificates in [caPem]; the JDK's default trust store is not consulted. */
    fun trustManager(caPem: ByteArray): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certificates(caPem).forEachIndexed { i, cert -> store.setCertificateEntry("ca-$i", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun privateKey(pem: ByteArray): PrivateKey {
        val blocks = BLOCK.findAll(String(pem, Charsets.US_ASCII)).toList()
        val block = blocks.firstOrNull { it.groupValues[1].endsWith("PRIVATE KEY") } ?: throw KubeException("no private key in PEM")
        val label = block.groupValues[1]
        val body = block.groupValues[2]
        if ("ENCRYPTED" in label || "Proc-Type:" in body) throw KubeException("encrypted private keys are not supported")
        val der = runCatching { Base64.getMimeDecoder().decode(body.trim()) }.getOrElse { throw KubeException("invalid base64 in private key PEM") }
        return when (label) {
            "PRIVATE KEY" -> pkcs8(der, "EC", "RSA", "Ed25519")
            "EC PRIVATE KEY" -> pkcs8(sec1ToPkcs8(der), "EC")
            "RSA PRIVATE KEY" -> pkcs8(pkcs1ToPkcs8(der), "RSA")
            else -> throw KubeException("unsupported private key type: $label")
        }
    }

    /** RFC 5915 `ECPrivateKey` → RFC 5208 `PrivateKeyInfo`; the curve comes from the key's own parameters. */
    fun sec1ToPkcs8(sec1: ByteArray): ByteArray {
        val outer = Der.read(sec1, 0)
        if (outer.tag != 0x30) throw KubeException("EC key is not a DER sequence")
        var pos = outer.contentStart
        var curve: ByteArray? = null
        while (pos < outer.end) {
            val el = Der.read(sec1, pos)
            if (el.tag == 0xA0) curve = sec1.copyOfRange(el.contentStart, el.end)
            pos = el.end
        }
        val curveOid = curve?.takeIf { it.firstOrNull() == 0x06.toByte() }
            ?: throw KubeException("EC key has no named curve parameters")
        val algorithm = Der.tlv(0x30, EC_PUBLIC_KEY_OID + curveOid)
        return Der.tlv(0x30, Der.tlv(0x02, byteArrayOf(0)) + algorithm + Der.tlv(0x04, sec1))
    }

    /** RFC 8017 `RSAPrivateKey` → RFC 5208 `PrivateKeyInfo`. */
    fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray =
        Der.tlv(0x30, Der.tlv(0x02, byteArrayOf(0)) + RSA_ENCRYPTION_ALG + Der.tlv(0x04, pkcs1))

    private fun pkcs8(der: ByteArray, vararg algorithms: String): PrivateKey {
        for (algorithm in algorithms) {
            runCatching { return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der)) }
        }
        throw KubeException("unreadable private key")
    }

    /** Just enough DER to walk one sequence and build a few TLVs. */
    private object Der {
        class Element(val tag: Int, val contentStart: Int, val end: Int)

        fun read(der: ByteArray, at: Int): Element {
            if (at + 2 > der.size) throw KubeException("truncated DER")
            val tag = der[at].toInt() and 0xFF
            var pos = at + 1
            val first = der[pos++].toInt() and 0xFF
            val length = if (first < 0x80) first else {
                val count = first and 0x7F
                if (count == 0 || count > 4 || pos + count > der.size) throw KubeException("bad DER length")
                var n = 0
                repeat(count) { n = (n shl 8) or (der[pos++].toInt() and 0xFF) }
                n
            }
            if (length < 0 || pos + length > der.size) throw KubeException("truncated DER")
            return Element(tag, pos, pos + length)
        }

        fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}
