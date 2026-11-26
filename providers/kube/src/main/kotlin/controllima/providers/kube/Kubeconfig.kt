package controllima.providers.kube

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class KubeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** What is needed to reach one API server. Secrets stay out of [toString]. */
class KubeAccess(
    val context: String,
    val server: String,
    val caPem: ByteArray,
    val clientCertPem: ByteArray? = null,
    val clientKeyPem: ByteArray? = null,
    val token: String? = null,
) {
    override fun toString() = "KubeAccess(context=$context, server=$server, clientCert=${clientCertPem != null}, token=${token != null})"
}

object Kubeconfig {
    /**
     * Resolves [contextName] (or `current-context`) to its cluster and user. Relative file references
     * resolve against [baseDir], the kubeconfig's own directory, as kubectl does.
     */
    fun parse(
        text: String,
        contextName: String?,
        baseDir: Path? = null,
        readFile: (Path) -> ByteArray = Files::readAllBytes,
    ): KubeAccess {
        val root = root(text)
        val name = contextName ?: (root["current-context"] as? String) ?: throw KubeException("kubeconfig has no current-context")
        val context = named(root, "contexts", name, "context") ?: throw KubeException("context '$name' not found")
        val clusterName = context["cluster"] as? String ?: throw KubeException("context '$name' has no cluster")
        val userName = context["user"] as? String ?: throw KubeException("context '$name' has no user")
        val cluster = named(root, "clusters", clusterName, "cluster") ?: throw KubeException("cluster '$clusterName' not found")
        val user = named(root, "users", userName, "user") ?: throw KubeException("user '$userName' not found")

        // Never fall back to an unverified connection, even when the kubeconfig asks for it.
        if ((cluster["insecure-skip-tls-verify"] as? String)?.lowercase() == "true") {
            throw KubeException("cluster '$clusterName' sets insecure-skip-tls-verify; refusing to connect without TLS verification")
        }
        val server = cluster["server"] as? String ?: throw KubeException("cluster '$clusterName' has no server")
        if (!server.startsWith("https://")) throw KubeException("cluster '$clusterName' server is not https: $server")

        fun data(map: Map<String, Any?>, dataKey: String, fileKey: String): ByteArray? {
            (map[dataKey] as? String)?.let {
                return runCatching { Base64.getMimeDecoder().decode(it) }.getOrElse { throw KubeException("$dataKey is not valid base64") }
            }
            val file = map[fileKey] as? String ?: return null
            val path = Path.of(file).let { if (it.isAbsolute || baseDir == null) it else baseDir.resolve(it) }
            return runCatching { readFile(path) }.getOrElse { throw KubeException("cannot read $fileKey $path: ${it.message}", it) }
        }

        val ca = data(cluster, "certificate-authority-data", "certificate-authority")
            ?: throw KubeException("cluster '$clusterName' has no certificate authority")
        if (user["exec"] != null || user["auth-provider"] != null) throw KubeException("user '$userName' uses exec or auth-provider plugins, which are not supported")
        val cert = data(user, "client-certificate-data", "client-certificate")
        val key = data(user, "client-key-data", "client-key")
        val token = user["token"] as? String
        if ((cert == null) != (key == null)) throw KubeException("user '$userName' has a client certificate without a key or a key without a certificate")
        if (cert == null && token == null) throw KubeException("user '$userName' has no client certificate or token")
        return KubeAccess(name, server, ca, cert, key, token)
    }

    fun contextNames(text: String): List<String> =
        (root(text)["contexts"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }

    fun currentContext(text: String): String? = root(text)["current-context"] as? String

    private fun root(text: String): Map<String, Any?> {
        val parsed = try {
            KubeconfigYaml.parse(text)
        } catch (e: KubeconfigYaml.YamlException) {
            throw KubeException("kubeconfig: ${e.message}", e)
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?> ?: throw KubeException("kubeconfig is not a mapping")
    }

    private fun named(root: Map<String, Any?>, listKey: String, name: String, field: String): Map<String, Any?>? {
        val entry = (root[listKey] as? List<*>).orEmpty().firstOrNull { (it as? Map<*, *>)?.get("name") == name } as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        return entry?.get(field) as? Map<String, Any?>
    }
}
