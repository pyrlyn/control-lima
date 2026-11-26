package controllima.shared

import kotlinx.serialization.Serializable

// ---------- Kubernetes (k3s in Colima, Lima k3s/k8s templates) ----------

/** A cluster found in a kubeconfig. `id` is `colima/<profile>` or `lima/<vm>`. */
@Serializable
data class KubeCluster(
    val id: String,
    val label: String,
    /** `colima` or `lima`. */
    val source: String,
    val vmName: String,
    val context: String? = null,
    val server: String? = null,
    val available: Boolean,
    val error: String? = null,
)

@Serializable
data class KubeNode(
    val name: String,
    val ready: Boolean,
    val roles: List<String> = emptyList(),
    val kubeletVersion: String = "",
    val osImage: String? = null,
    val internalIp: String? = null,
    val cpuCapacityMillicores: Long? = null,
    val memoryCapacityBytes: Long? = null,
    val cpuUsageMillicores: Long? = null,
    val memoryUsageBytes: Long? = null,
)

@Serializable
data class KubePod(
    val namespace: String,
    val name: String,
    val phase: String,
    /** Ready containers over all containers, e.g. `1/2`. */
    val ready: String,
    val restarts: Int = 0,
    val node: String? = null,
    val podIp: String? = null,
    val containers: List<String> = emptyList(),
    val createdEpochSeconds: Long? = null,
    val cpuMillicores: Long? = null,
    val memoryBytes: Long? = null,
)

@Serializable
data class KubeServicePort(
    val name: String? = null,
    val port: Int,
    /** A number or a named container port. */
    val targetPort: String? = null,
    val nodePort: Int? = null,
    val protocol: String = "TCP",
)

@Serializable
data class KubeService(
    val namespace: String,
    val name: String,
    val type: String,
    val clusterIp: String? = null,
    val ports: List<KubeServicePort> = emptyList(),
)

@Serializable
data class KubeOverview(
    val cluster: KubeCluster,
    val nodes: List<KubeNode> = emptyList(),
    val namespaces: List<String> = emptyList(),
    val pods: List<KubePod> = emptyList(),
    val services: List<KubeService> = emptyList(),
    /** False when metrics-server is not installed; CPU and memory fields are then null. */
    val metricsAvailable: Boolean = false,
    val error: String? = null,
)
