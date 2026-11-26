package controllima.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import controllima.shared.ApiException
import controllima.shared.Format
import controllima.shared.KubeOverview
import controllima.shared.KubePod
import controllima.shared.KubePortForward
import controllima.shared.KubeRoutes
import controllima.shared.PortForwardKind
import controllima.shared.PortForwardRequest
import controllima.shared.Role
import kotlinx.coroutines.launch

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
    var forwarding by remember { mutableStateOf<ForwardTarget?>(null) }
    var forwardsNonce by remember { mutableStateOf(0) }
    val canForward = m.can(Role.OPERATOR)
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
        else TableHeader(listOf(Col("Namespace", 1.6f), Col("Name", 3f), Col("Status", 1.6f), Col("Ready", 0.8f), Col("Restarts", 0.9f), Col("Node", 1.4f), Col("CPU", 1f), Col("Memory", 1.2f), Col("", 3f)))
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
                Cell(3f) {
                    Row {
                        SmallAction(Strings.LOGS) { m.navigate(Screen.Logs(KubeRoutes.podTarget(o.cluster.id, p.namespace, p.name))) }
                        SmallAction(Strings.FORWARD, canForward && p.phase == "Running") {
                            forwarding = ForwardTarget(PortForwardKind.POD, p.namespace, p.name, p.ports.filter { it.protocol == "TCP" }.map { it.containerPort }.distinct())
                        }
                        SmallAction(Strings.DELETE, m.can(Role.ADMIN), danger = true) { deleting = p }
                    }
                }
            }
        }
    }

    Section("Services") {
        val services = o.services.filter { namespace == "all" || it.namespace == namespace }
        if (services.isEmpty()) EmptyState("No services in this namespace")
        else TableHeader(listOf(Col("Namespace", 1.6f), Col("Name", 2.4f), Col("Type", 1.4f), Col("Cluster IP", 1.6f), Col("Ports", 3f), Col("", 1.2f)))
        services.forEach { s ->
            TableRow {
                TextCell(1.6f, s.namespace)
                TextCell(2.4f, s.name)
                TextCell(1.4f, s.type)
                TextCell(1.6f, s.clusterIp)
                TextCell(3f, s.ports.joinToString("\n") { p ->
                    "${p.port}" + (p.targetPort?.let { "→$it" } ?: "") + "/${p.protocol.lowercase()}" + (p.nodePort?.let { " (node port $it)" } ?: "")
                })
                val tcp = s.ports.filter { it.protocol == "TCP" }.map { it.port }.distinct()
                Cell(1.2f) {
                    // Without a selector there is no pod to forward to.
                    if (tcp.isNotEmpty() && s.selector.isNotEmpty()) {
                        SmallAction(Strings.FORWARD, canForward) { forwarding = ForwardTarget(PortForwardKind.SERVICE, s.namespace, s.name, tcp) }
                    }
                }
            }
        }
    }

    PortForwards(m, o.cluster.id, forwardsNonce)

    forwarding?.let { t ->
        ForwardDialog(m, o.cluster.id, t, onDismiss = { forwarding = null }) { f ->
            m.toast("Forwarding 127.0.0.1:${f.localPort} to ${t.kind.name.lowercase()} ${t.name}:${f.port}")
            forwardsNonce++
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

// ---------- port forwards (v2.1) ----------

private class ForwardTarget(val kind: PortForwardKind, val namespace: String, val name: String, val ports: List<Int>)

@Composable
private fun PortForwards(m: AppModel, clusterId: String, nonce: Int) {
    val loaded = load(nonce, everyMillis = 5_000) { m.api.portForwards() }
    val forwards = loaded.value?.filter { it.clusterId == clusterId } ?: return
    val scope = rememberCoroutineScope()
    Section("Port forwards") {
        if (forwards.isEmpty()) {
            Text("None. Forward on a pod or service opens a port on 127.0.0.1 that reaches it through the Kubernetes API.")
            return@Section
        }
        TableHeader(listOf(Col("Local", 1.6f), Col("Target", 3f), Col("Connections", 1.6f), Col("Traffic", 1.8f), Col("Last error", 2.4f), Col("", 2.4f)))
        forwards.forEach { f ->
            TableRow(highlight = f.lastError != null) {
                TextCell(1.6f, "127.0.0.1:${f.localPort}", mono = true)
                TextCell(3f, "${f.kind.name.lowercase()} ${f.namespace}/${f.name}:${f.port}", mono = true)
                TextCell(1.6f, "${f.activeConnections} open, ${f.totalConnections} total")
                TextCell(1.8f, "↑ ${Format.bytes(f.bytesOut, m.units)} ↓ ${Format.bytes(f.bytesIn, m.units)}")
                TextCell(2.4f, f.lastError)
                Cell(2.4f) {
                    Row {
                        SmallAction(Strings.OPEN) { openExternal("http://127.0.0.1:${f.localPort}") }
                        SmallAction(Strings.COPY) { if (copyToClipboard("127.0.0.1:${f.localPort}")) m.toast("Copied") }
                        SmallAction(Strings.STOP, m.can(Role.OPERATOR)) {
                            scope.launch {
                                try {
                                    m.api.stopPortForward(f.id)
                                } catch (e: ApiException) {
                                    m.toast(e.message ?: "Could not stop the forward")
                                }
                                loaded.reload()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardDialog(m: AppModel, clusterId: String, t: ForwardTarget, onDismiss: () -> Unit, onStarted: (KubePortForward) -> Unit) {
    val first = t.ports.firstOrNull()
    var port by remember { mutableStateOf(first?.toString().orEmpty()) }
    // Same local port as the remote one, like kubectl, unless it is privileged.
    var local by remember { mutableStateOf(first?.takeIf { it >= PortForwardRequest.MIN_LOCAL_PORT }?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val request = port.toIntOrNull()?.let { p -> PortForwardRequest(t.kind, t.namespace, t.name, p, if (local.isBlank()) 0 else local.toIntOrNull() ?: -1) }
    val problem = when {
        port.isBlank() -> null
        request == null -> "Ports are numbers"
        else -> request.problem()
    }
    val what = if (t.kind == PortForwardKind.SERVICE) "service" else "pod"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Forward $what ${t.name}") },
        text = {
            Column {
                Text(
                    "Opens a port on 127.0.0.1 of this machine. Connections reach the " +
                        (if (t.kind == PortForwardKind.SERVICE) "service's target port on a ready pod behind it" else "pod's port") +
                        " through the Kubernetes API. The forward runs until you stop it or ControlLima exits.",
                    Modifier.padding(bottom = 8.dp),
                )
                if (t.ports.size > 1) {
                    ChipRow(t.ports.map { it.toString() }, port, { it }) {
                        port = it
                        local = it.toInt().takeIf { p -> p >= PortForwardRequest.MIN_LOCAL_PORT }?.toString().orEmpty()
                        error = null
                    }
                }
                OutlinedTextField(port, { port = it.trim(); error = null }, label = { Text(if (t.kind == PortForwardKind.SERVICE) "Service port" else "Pod port") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(local, { local = it.trim(); error = null }, label = { Text("Local port (empty: any free port)") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                (error ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val r = request ?: return@Button
                busy = true
                scope.launch {
                    try {
                        onStarted(m.api.startPortForward(clusterId, r))
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy && request != null && problem == null) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(Strings.FORWARD)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(Strings.CANCEL) } },
    )
}
