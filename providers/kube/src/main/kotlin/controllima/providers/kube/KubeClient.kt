package controllima.providers.kube

import controllima.shared.KubeCluster
import controllima.shared.KubeNode
import controllima.shared.KubeOverview
import controllima.shared.KubePod
import controllima.shared.KubeService
import controllima.shared.LogLine
import java.time.Instant

data class KubeResponse(val status: Int, val body: String)

/** HTTPS to one API server. [path] starts with `/` and is already validated and encoded. */
interface KubeHttp : AutoCloseable {
    fun get(path: String): KubeResponse
    fun delete(path: String): KubeResponse
    override fun close()
}

/** A non-2xx answer; [message] is the API's Status message when there is one. */
class KubeApiException(val status: Int, message: String) : RuntimeException(message)

interface KubeClient : AutoCloseable {
    fun nodes(): List<KubeNode>
    fun namespaces(): List<String>
    fun pods(namespace: String? = null): List<KubePod>
    fun services(namespace: String? = null): List<KubeService>
    /** True when metrics-server serves `metrics.k8s.io`. */
    fun metricsAvailable(): Boolean
    /** The owning controller recreates the pod, which is how a pod is restarted. */
    fun deletePod(namespace: String, name: String)
    fun podLogs(namespace: String, name: String, container: String? = null, tailLines: Int = 200): List<LogLine>
    override fun close()

    /** Everything the cluster page shows; a failure becomes [KubeOverview.error] instead of an exception. */
    fun overview(cluster: KubeCluster): KubeOverview = try {
        KubeOverview(cluster, nodes(), namespaces(), pods(), services(), metricsAvailable())
    } catch (e: Exception) {
        KubeOverview(cluster, error = e.message ?: e.toString())
    }
}

/** [KubeClient] over [KubeHttp]. CPU and memory come from metrics-server when it is installed. */
class HttpKubeClient(private val http: KubeHttp) : KubeClient {
    override fun nodes(): List<KubeNode> {
        val nodes = KubeJson.nodes(getOk("/api/v1/nodes"))
        val usage = metrics("$METRICS/nodes")?.let(KubeJson::nodeMetrics) ?: return nodes
        return nodes.map { n -> usage[n.name]?.let { n.copy(cpuUsageMillicores = it.cpuMillicores, memoryUsageBytes = it.memoryBytes) } ?: n }
    }

    override fun namespaces(): List<String> = KubeJson.namespaces(getOk("/api/v1/namespaces")).sorted()

    override fun pods(namespace: String?): List<KubePod> {
        val scope = namespace?.let { "/namespaces/${KubeNames.namespace(it)}" }.orEmpty()
        val pods = KubeJson.pods(getOk("/api/v1$scope/pods"))
        val usage = metrics("$METRICS$scope/pods")?.let(KubeJson::podMetrics) ?: return pods
        return pods.map { p -> usage["${p.namespace}/${p.name}"]?.let { p.copy(cpuMillicores = it.cpuMillicores, memoryBytes = it.memoryBytes) } ?: p }
    }

    override fun services(namespace: String?): List<KubeService> {
        val scope = namespace?.let { "/namespaces/${KubeNames.namespace(it)}" }.orEmpty()
        return KubeJson.services(getOk("/api/v1$scope/services"))
    }

    override fun metricsAvailable(): Boolean = metrics(METRICS) != null

    override fun deletePod(namespace: String, name: String) {
        checkOk(http.delete(podPath(namespace, name)))
    }

    override fun podLogs(namespace: String, name: String, container: String?, tailLines: Int): List<LogLine> {
        if (tailLines !in 1..MAX_TAIL) throw KubeException("tailLines must be in 1..$MAX_TAIL")
        val query = buildString {
            append("?timestamps=true&tailLines=").append(tailLines)
            container?.let { append("&container=").append(KubeNames.container(it)) }
        }
        return getOk("${podPath(namespace, name)}/log$query").lineSequence().filter { it.isNotEmpty() }.map(::logLine).toList()
    }

    override fun close() = http.close()

    private fun podPath(namespace: String, name: String) =
        "/api/v1/namespaces/${KubeNames.namespace(namespace)}/pods/${KubeNames.pod(name)}"

    private fun getOk(path: String): String = checkOk(http.get(path)).body

    /** Null when metrics-server is absent (404) or its aggregated API is not ready yet (503). */
    private fun metrics(path: String): String? {
        val response = http.get(path)
        if (response.status == 404 || response.status == 503) return null
        return checkOk(response).body
    }

    private fun checkOk(response: KubeResponse): KubeResponse {
        if (response.status in 200..299) return response
        val message = KubeJson.statusMessage(response.body) ?: "Kubernetes API returned HTTP ${response.status}"
        throw KubeApiException(response.status, message)
    }

    private fun logLine(raw: String): LogLine {
        val space = raw.indexOf(' ')
        val ts = if (space > 0) runCatching { Instant.parse(raw.substring(0, space)).toEpochMilli() }.getOrNull() else null
        // The API merges stdout and stderr into one stream.
        return LogLine(ts, "stdout", if (ts != null) raw.substring(space + 1) else raw)
    }

    private companion object {
        const val METRICS = "/apis/metrics.k8s.io/v1beta1"
        const val MAX_TAIL = 10_000
    }
}
