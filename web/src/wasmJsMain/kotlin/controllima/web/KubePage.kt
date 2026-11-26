package controllima.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import controllima.shared.Format
import controllima.shared.KubeOverview
import controllima.shared.KubePod
import controllima.shared.KubeRoutes
import controllima.shared.Role

// ---------- Kubernetes (docs/pages/kubernetes.md) ----------

@Composable
fun KubernetesPage(m: AppModel) {
    val clusters = load(Unit, everyMillis = 30_000) { m.api.kubeClusters() }
    var selected by remember { mutableStateOf<String?>(null) }
    val list = clusters.value
    PageHeader(Page.Kubernetes.title, list?.let { "${it.count { c -> c.available }} of ${it.size} clusters reachable" }) {
        if (list != null && list.size > 1) Picker("Cluster", list.map { it.id }, selected, { id -> list.first { it.id == id }.label }) { selected = it }
    }
    if (list == null) {
        if (clusters.error != null) Banner(clusters.error, action = Strings.RETRY to clusters.reload) else Skeleton(4)
        return
    }
    if (list.isEmpty()) {
        EmptyState(
            "No Kubernetes clusters",
            "Start Colima with --kubernetes, or create a Lima VM from the k3s or k8s template. ControlLima reads their kubeconfig; it never runs kubectl.",
        )
        return
    }
    val id = selected?.takeIf { s -> list.any { it.id == s } } ?: (list.firstOrNull { it.available } ?: list.first()).id
    val cluster = list.first { it.id == id }
    if (!cluster.available) {
        Banner("${cluster.label}: ${cluster.error ?: "not reachable"}", BannerKind.WARNING)
        return
    }
    var nonce by remember { mutableStateOf(0) }
    val overview = load(id, nonce, everyMillis = 15_000) { m.api.kubeOverview(id) }
    val o = overview.value
    if (o == null) {
        if (overview.error != null) Banner(overview.error, action = Strings.RETRY to overview.reload) else Skeleton(6)
        return
    }
    if (o.error != null) Banner("${cluster.label}: ${o.error}", action = Strings.RETRY to overview.reload)
    ClusterOverview(m, o) { nonce++ }
}

@Composable
private fun ClusterOverview(m: AppModel, o: KubeOverview, onChanged: () -> Unit) {
    var namespace by remember(o.cluster.id) { mutableStateOf("all") }
    var deleting by remember { mutableStateOf<KubePod?>(null) }
    if (!o.metricsAvailable) Text("metrics-server is not installed, so CPU and memory are not shown.", style = MaterialTheme.typography.bodySmall)

    Section("Nodes") {
        TableHeader(listOf(Col("Name", 2f), Col("Ready", 1f), Col("Roles", 1.6f), Col("Version", 1.6f), Col("IP", 1.4f), Col("CPU", 1.6f), Col("Memory", 2f)))
        o.nodes.forEach { n ->
            TableRow {
                TextCell(2f, n.name)
                TextCell(1f, if (n.ready) "Ready" else "NotReady")
                TextCell(1.6f, n.roles.joinToString(", ").ifEmpty { null })
                TextCell(1.6f, n.kubeletVersion)
                TextCell(1.4f, n.internalIp)
                TextCell(1.6f, usage(n.cpuUsageMillicores?.let { "${it}m" }, n.cpuCapacityMillicores?.let { "${it}m" }))
                TextCell(2f, usage(n.memoryUsageBytes?.let { Format.bytes(it, m.units) }, n.memoryCapacityBytes?.let { Format.bytes(it, m.units) }))
            }
        }
    }

    Section("Pods") {
        ChipRow(listOf("all") + o.namespaces, namespace, { it }) { namespace = it }
        val pods = o.pods.filter { namespace == "all" || it.namespace == namespace }
        if (pods.isEmpty()) EmptyState("No pods in this namespace")
        else TableHeader(listOf(Col("Namespace", 1.6f), Col("Name", 3f), Col("Status", 1.6f), Col("Ready", 0.8f), Col("Restarts", 0.9f), Col("Node", 1.4f), Col("CPU", 1f), Col("Memory", 1.2f), Col("", 2.2f)))
        pods.forEach { p ->
            TableRow {
                TextCell(1.6f, p.namespace)
                TextCell(3f, p.name, mono = true)
                TextCell(1.6f, p.phase)
                TextCell(0.8f, p.ready)
                TextCell(0.9f, p.restarts.toString())
                TextCell(1.4f, p.node)
                TextCell(1f, p.cpuMillicores?.let { "${it}m" })
                TextCell(1.2f, p.memoryBytes?.let { Format.bytes(it, m.units) })
                Cell(2.2f) {
                    androidx.compose.foundation.layout.Row {
                        SmallAction(Strings.LOGS) { m.navigate(Screen.Logs(KubeRoutes.podTarget(o.cluster.id, p.namespace, p.name))) }
                        SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { deleting = p }
                    }
                }
            }
        }
    }

    Section("Services") {
        val services = o.services.filter { namespace == "all" || it.namespace == namespace }
        if (services.isEmpty()) EmptyState("No services in this namespace")
        else TableHeader(listOf(Col("Namespace", 1.6f), Col("Name", 2.4f), Col("Type", 1.4f), Col("Cluster IP", 1.6f), Col("Ports", 3f)))
        services.forEach { s ->
            TableRow {
                TextCell(1.6f, s.namespace)
                TextCell(2.4f, s.name)
                TextCell(1.4f, s.type)
                TextCell(1.6f, s.clusterIp)
                TextCell(3f, s.ports.joinToString("\n") { p ->
                    "${p.port}" + (p.targetPort?.let { "→$it" } ?: "") + "/${p.protocol.lowercase()}" + (p.nodePort?.let { " (node port $it)" } ?: "")
                })
            }
        }
    }

    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete pod ${p.name}?",
            name = p.name,
            details = listOf(
                "Kubernetes deletes the pod in ${p.namespace}; its controller (Deployment, StatefulSet, …) starts a new one.",
                "A pod without a controller is not recreated.",
            ),
            onDismiss = { deleting = null },
        ) { typed -> m.track("Delete pod ${p.name}", m.api.deletePod(o.cluster.id, p.namespace, p.name, typed).actionId, onChanged) }
    }
}

private fun usage(used: String?, capacity: String?): String? = when {
    used != null && capacity != null -> "$used / $capacity"
    else -> capacity ?: used
}
