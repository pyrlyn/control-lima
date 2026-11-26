package controllima.server

import controllima.providers.kube.KubeNames
import controllima.server.http.badRequest
import controllima.shared.ConfirmRequest
import controllima.shared.KubeCluster
import controllima.shared.KubeOverview
import controllima.shared.KubePortForward
import controllima.shared.PortForwardKind
import controllima.shared.PortForwardRequest
import controllima.shared.KubeRoutes
import controllima.shared.Role

/** Kubernetes clusters of Colima and Lima VMs (docs/plan/kubernetes.md). Pod logs go through the log routes. */
internal fun Api.kubeRoutes() {
    val cluster = "${KubeRoutes.CLUSTERS}/{source}/{name}"

    get(KubeRoutes.CLUSTERS) { list(s.kube.clusters(), KubeCluster.serializer()) }
    get(cluster) { json(s.kube.overview(clusterId()), KubeOverview.serializer()) }
    // Deleting a pod is how Kubernetes restarts it, but a bare pod without a controller is gone for good.
    delete("$cluster/pods/{namespace}/{pod}", Role.ADMIN) {
        val id = clusterId()
        val namespace = request.path("namespace").also { if (!KubeNames.isLabel(it)) badRequest("Invalid namespace") }
        val pod = request.path("pod").also { if (!KubeNames.isSubdomain(it)) badRequest("Invalid pod name") }
        requireConfirm(decode(request, ConfirmRequest.serializer()).confirmName, pod)
        val target = KubeRoutes.podTarget(id, namespace, pod)
        accepted(s.actions.launch("kube.pod_delete", target, principal, mapOf("cluster" to id, "namespace" to namespace)) { _ ->
            s.kube.deletePod(id, namespace, pod)
            "Pod $namespace/$pod deleted"
        })
    }

    // Forwards open a port on 127.0.0.1 only; the write audit records who started and stopped each one.
    get(KubeRoutes.FORWARDS) { list(s.forwards.list(), KubePortForward.serializer()) }
    post("$cluster/forwards", Role.OPERATOR) {
        val id = clusterId()
        val body = decode(request, PortForwardRequest.serializer())
        if (!KubeNames.isLabel(body.namespace)) badRequest("Invalid namespace")
        val validName = if (body.kind == PortForwardKind.POD) KubeNames.isSubdomain(body.name) else KubeNames.isLabel(body.name)
        if (!validName) badRequest("Invalid ${body.kind.name.lowercase()} name")
        json(s.forwards.start(id, body, actor), KubePortForward.serializer(), 201)
    }
    delete("${KubeRoutes.FORWARDS}/{id}", Role.OPERATOR) {
        val id = request.path("id").also { if (!FORWARD_ID.matches(it)) badRequest("Invalid forward id") }
        s.forwards.stop(id)
        ok()
    }
}

private val FORWARD_ID = Regex("[0-9]{1,9}")

private fun Api.Ctx.clusterId(): String {
    val source = request.path("source")
    val name = request.path("name")
    if (source != "colima" && source != "lima") badRequest("Unknown cluster source")
    if (!controllima.shared.Names.isValidVmName(name)) badRequest("Invalid cluster name")
    return "$source/$name"
}
