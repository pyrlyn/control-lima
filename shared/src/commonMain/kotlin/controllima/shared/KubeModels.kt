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
    /** Ports the containers declare; a pod can still listen on others. */
    val ports: List<KubeContainerPort> = emptyList(),
    val createdEpochSeconds: Long? = null,
    val cpuMillicores: Long? = null,
    val memoryBytes: Long? = null,
)

@Serializable
data class KubeContainerPort(val name: String? = null, val containerPort: Int, val protocol: String = "TCP")

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
    /** Labels that pick the service's pods; empty for services without a selector. */
    val selector: Map<String, String> = emptyMap(),
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

@Serializable
enum class PortForwardKind { POD, SERVICE }

/** Start a port forward; [localPort] 0 lets the server pick a free port. */
@Serializable
data class PortForwardRequest(
    val kind: PortForwardKind,
    val namespace: String,
    val name: String,
    /** The pod's port, or the service's port (which the server maps to its target port). */
    val port: Int,
    val localPort: Int = 0,
) {
    /** Null when the request is acceptable; the same check runs in the UI and on the server. */
    fun problem(): String? = when {
        port !in 1..65535 -> "Port must be between 1 and 65535"
        localPort != 0 && localPort !in MIN_LOCAL_PORT..65535 -> "Local port must be empty or between $MIN_LOCAL_PORT and 65535"
        else -> null
    }

    companion object {
        /** Privileged ports would need root and would shadow system services. */
        const val MIN_LOCAL_PORT = 1024
    }
}

/** A running port forward on 127.0.0.1; it lives as long as the server process. */
@Serializable
data class KubePortForward(
    val id: String,
    val clusterId: String,
    val kind: PortForwardKind,
    val namespace: String,
    val name: String,
    val port: Int,
    val localPort: Int,
    val startedBy: String,
    val startedAtMillis: Long,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0,
    /** Bytes sent to the cluster and received from it. */
    val bytesOut: Long = 0,
    val bytesIn: Long = 0,
    /** The most recent connection failure, e.g. the pod refusing the port. */
    val lastError: String? = null,
) {
    val label get() = "${kind.name.lowercase()}/$namespace/$name:$port"
}

object KubeRoutes {
    const val CLUSTERS = "/api/kube/clusters"
    const val FORWARDS = "/api/kube/forwards"

    fun forwards(clusterId: String) = "${cluster(clusterId)}/forwards"
    fun forward(id: String) = "$FORWARDS/${ApiRoutes.encode(id)}"

    /** Cluster ids are `<source>/<vm>`, which maps onto two path segments. */
    fun cluster(id: String) = "$CLUSTERS/${id.split('/').joinToString("/") { ApiRoutes.encode(it) }}"
    fun pod(clusterId: String, namespace: String, pod: String) =
        "${cluster(clusterId)}/pods/${ApiRoutes.encode(namespace)}/${ApiRoutes.encode(pod)}"

    /** Log target of a pod: `kube:<source>/<vm>/<namespace>/<pod>`; the log source adds `#pod[:<container>]`. */
    fun podTarget(clusterId: String, namespace: String, pod: String) = "kube:$clusterId/$namespace/$pod"

    /** (clusterId, namespace, pod) of a [podTarget], or null. */
    fun parsePodTarget(target: String): Triple<String, String, String>? {
        if (!target.startsWith("kube:")) return null
        val parts = target.removePrefix("kube:").split('/')
        if (parts.size != 4 || parts.any { it.isEmpty() }) return null
        return Triple("${parts[0]}/${parts[1]}", parts[2], parts[3])
    }
}
