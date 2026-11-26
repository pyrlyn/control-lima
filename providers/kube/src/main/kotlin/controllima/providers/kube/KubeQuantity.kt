package controllima.providers.kube

import java.math.BigDecimal
import java.math.RoundingMode

/** Kubernetes resource quantities (`250m`, `1`, `128Mi`, `12345Ki`, `1e3`, `123456n`). */
object KubeQuantity {
    private val SUFFIX = Regex("^([+-]?(?:\\d+\\.?\\d*|\\.\\d+))(Ki|Mi|Gi|Ti|Pi|Ei|n|u|m|k|M|G|T|P|E|[eE][+-]?\\d+)?$")

    private val multipliers: Map<String, BigDecimal> = buildMap {
        put("n", BigDecimal("1e-9")); put("u", BigDecimal("1e-6")); put("m", BigDecimal("1e-3"))
        listOf("k", "M", "G", "T", "P", "E").forEachIndexed { i, s -> put(s, BigDecimal.TEN.pow(3 * (i + 1))) }
        listOf("Ki", "Mi", "Gi", "Ti", "Pi", "Ei").forEachIndexed { i, s -> put(s, BigDecimal(2).pow(10 * (i + 1))) }
    }

    /** The value in base units (cores, bytes), or null when [text] is not a quantity. */
    fun parse(text: String): BigDecimal? {
        val m = SUFFIX.matchEntire(text.trim()) ?: return null
        val number = BigDecimal(m.groupValues[1])
        val suffix = m.groupValues[2]
        return when {
            suffix.isEmpty() -> number
            // A bare `E` is exa; `E` followed by digits is a decimal exponent.
            (suffix[0] == 'e' || suffix[0] == 'E') && suffix.length > 1 -> number.scaleByPowerOfTen(suffix.substring(1).toInt())
            else -> number * multipliers.getValue(suffix)
        }
    }

    fun millicores(text: String?): Long? = text?.let(::parse)?.movePointRight(3)?.setScale(0, RoundingMode.HALF_UP)?.toLong()

    fun bytes(text: String?): Long? = text?.let(::parse)?.setScale(0, RoundingMode.HALF_UP)?.toLong()
}

/** Kubernetes object name rules, checked before a name goes into a URL path. */
object KubeNames {
    private val LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
    private val SUBDOMAIN = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*")

    /** RFC 1123 label: namespaces and container names. */
    fun isLabel(name: String) = name.length <= 63 && LABEL.matches(name)

    /** RFC 1123 subdomain: pod names. */
    fun isSubdomain(name: String) = name.length <= 253 && SUBDOMAIN.matches(name)

    fun namespace(name: String): String = name.also { if (!isLabel(it)) throw KubeException("invalid namespace: $it") }

    fun pod(name: String): String = name.also { if (!isSubdomain(it)) throw KubeException("invalid pod name: $it") }

    fun container(name: String): String = name.also { if (!isLabel(it)) throw KubeException("invalid container name: $it") }
}
