package controllima.server

import controllima.providers.kube.HttpKubeClient
import controllima.providers.kube.KtorKubeHttp
import controllima.providers.kube.KubeAccess
import controllima.providers.kube.KubeClient
import controllima.providers.kube.KubeDiscovery
import controllima.providers.kube.KubeTarget
import controllima.shared.KubeCluster
import controllima.shared.KubeOverview
import controllima.shared.KubeService
import controllima.shared.LogLine
import controllima.shared.Vm
import controllima.shared.VmSource
import controllima.shared.VmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import controllima.server.http.notFound
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Kubernetes in Colima and Lima (docs/plan/kubernetes.md). Clusters are found from kubeconfig files only;
 * the API is read over HTTPS through [KubeClient]. Clusters of VMs that are not running are listed but
 * never contacted, so a stopped VM costs no timeout.
 */
class KubernetesService(
    private val home: Path,
    private val vms: () -> List<Vm>,
    private val clientFactory: (KubeAccess) -> KubeClient = { HttpKubeClient(KtorKubeHttp(it)) },
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val clients = ConcurrentHashMap<String, Pair<String, KubeClient>>()
    private val overviews = ConcurrentHashMap<String, Pair<Long, KubeOverview>>()

    fun clusters(): List<KubeCluster> = targets().map { it.cluster }

    suspend fun overview(id: String, maxAgeMillis: Long = 5_000): KubeOverview {
        overviews[id]?.let { (at, o) -> if (clock() - at <= maxAgeMillis) return o }
        val target = target(id)
        val result = if (!target.cluster.available) {
            KubeOverview(target.cluster, error = target.cluster.error)
        } else {
            withContext(Dispatchers.IO) { runCatching { client(target).overview(target.cluster) }.getOrElse { KubeOverview(target.cluster, error = it.message) } }
        }
        overviews[id] = clock() to result
        return result
    }

    /** Node-port services of every reachable cluster, for the Ports page. */
    suspend fun nodePortServices(): List<Pair<KubeCluster, KubeService>> = clusters().filter { it.available }.flatMap { c ->
        overview(c.id, maxAgeMillis = 30_000).services.filter { s -> s.ports.any { it.nodePort != null } }.map { c to it }
    }

    suspend fun podLogs(id: String, namespace: String, pod: String, container: String?, tail: Int): List<LogLine> {
        val target = availableTarget(id)
        return withContext(Dispatchers.IO) { client(target).podLogs(namespace, pod, container, tail) }
    }

    suspend fun deletePod(id: String, namespace: String, pod: String) {
        val target = availableTarget(id)
        withContext(Dispatchers.IO) { client(target).deletePod(namespace, pod) }
        overviews.remove(id)
    }

    /** Containers of a pod from the last overview, for log sources; empty when the cluster was never read. */
    fun cachedContainers(id: String, namespace: String, pod: String): List<String> =
        overviews[id]?.second?.pods?.firstOrNull { it.namespace == namespace && it.name == pod }?.containers.orEmpty()

    override fun close() {
        clients.values.forEach { runCatching { it.second.close() } }
        clients.clear()
    }

    private fun targets(): List<KubeTarget> {
        val all = vms()
        val running = all.filter { it.status == VmStatus.RUNNING }.map { it.source to it.name }.toSet()
        // `colima list` reports the runtime as e.g. `docker+k3s` when Kubernetes is enabled.
        val colima = all.filter { it.source == VmSource.COLIMA && it.runtime?.contains("k3s") == true }.map { it.name }
        val lima = all.filter { it.source == VmSource.LIMA }.mapNotNull { it.dir }.map { Path.of(it) }
        return KubeDiscovery(home).discover(colima, lima).distinctBy { it.cluster.id }.map { t ->
            val source = if (t.cluster.source == "colima") VmSource.COLIMA else VmSource.LIMA
            if (t.cluster.available && (source to t.cluster.vmName) !in running) {
                t.copy(cluster = t.cluster.copy(available = false, error = "${t.cluster.vmName} is not running"))
            } else t
        }
    }

    private fun target(id: String): KubeTarget = targets().firstOrNull { it.cluster.id == id } ?: notFound("No Kubernetes cluster $id")

    private fun availableTarget(id: String): KubeTarget =
        target(id).also { if (!it.cluster.available) throw IllegalStateException(it.cluster.error ?: "Cluster $id is not available") }

    /** One client per cluster, rebuilt when the kubeconfig changes (k3s rotates its client certificate). */
    private fun client(target: KubeTarget): KubeClient {
        val stamp = "${Files.getLastModifiedTime(target.kubeconfig).toMillis()}|${target.cluster.server}|${target.cluster.context}"
        clients[target.cluster.id]?.let { (s, c) -> if (s == stamp) return c else runCatching { c.close() } }
        return clientFactory(target.access()).also { clients[target.cluster.id] = stamp to it }
    }
}
