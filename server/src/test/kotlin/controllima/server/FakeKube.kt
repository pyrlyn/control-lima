package controllima.server

import controllima.providers.kube.KubeClient
import controllima.providers.kube.KubeException
import controllima.providers.kube.KubePortStream
import controllima.shared.KubeNode
import controllima.shared.KubePod
import controllima.shared.KubeService
import controllima.shared.KubeServicePort
import controllima.shared.LogLine
import java.nio.file.Files
import java.nio.file.Path

/** A home directory with one Lima VM `dev` whose guest kubeconfig points at https://127.0.0.1:6443. */
fun limaKubeHome(): Path {
    val home = Files.createTempDirectory("cl-kube")
    val dir = home.resolve("lima/dev")
    Files.createDirectories(dir.resolve("copied-from-guest"))
    val ca = java.util.Base64.getEncoder().encodeToString("ca".toByteArray())
    Files.writeString(
        dir.resolve("copied-from-guest/kubeconfig.yaml"),
        """
        apiVersion: v1
        clusters:
        - cluster:
            certificate-authority-data: $ca
            server: https://127.0.0.1:6443
          name: default
        contexts:
        - context:
            cluster: default
            user: default
          name: default
        current-context: default
        users:
        - name: default
          user:
            token: secret
        """.trimIndent(),
    )
    return home
}

/** A cluster with one running pod `default/web-1` behind a NodePort service; [calls] records what was asked. */
class FakeKube(private val calls: MutableList<String> = mutableListOf()) : KubeClient {
    /** What a followed pod log does; by default it has no lines and ends at once. */
    var follow: suspend (onLine: suspend (LogLine) -> Unit) -> Unit = {}

    override fun nodes() = listOf(KubeNode("dev", true))
    override fun namespaces() = listOf("default")
    override fun pods(namespace: String?) = listOf(KubePod("default", "web-1", "Running", "1/1", containers = listOf("web")))
    override fun services(namespace: String?) =
        listOf(KubeService("default", "web", "NodePort", ports = listOf(KubeServicePort(port = 80, nodePort = 30080))))
    override fun metricsAvailable() = false
    override fun deletePod(namespace: String, name: String) { calls += "delete $namespace/$name" }
    override fun podLogs(namespace: String, name: String, container: String?, tailLines: Int) = listOf(LogLine(null, "stdout", "hi"))
    override suspend fun followPodLogs(namespace: String, name: String, container: String?, tailLines: Int, onLine: suspend (LogLine) -> Unit) {
        calls += "follow $namespace/$name:$container tail=$tailLines"
        follow(onLine)
    }
    override fun pod(namespace: String, name: String) = KubePod(namespace, name, if (name == "web-1") "Running" else "Pending", "1/1")
    override fun serviceBackend(namespace: String, service: String, servicePort: Int) = "web-1" to 8080
    override suspend fun portForward(namespace: String, pod: String, port: Int): KubePortStream {
        calls += "forward $namespace/$pod:$port"
        throw KubeException("no cluster in tests")
    }
    override fun close() {}
}
