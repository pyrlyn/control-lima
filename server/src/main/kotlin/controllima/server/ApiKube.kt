package controllima.server

import controllima.providers.kube.KubeNames
import controllima.server.http.badRequest
import controllima.shared.ConfirmRequest
import controllima.shared.KubeCluster
import controllima.shared.KubeOverview
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
}

private fun Api.Ctx.clusterId(): String {
    val source = request.path("source")
    val name = request.path("name")
    if (source != "colima" && source != "lima") badRequest("Unknown cluster source")
    if (!controllima.shared.Names.isValidVmName(name)) badRequest("Invalid cluster name")
    return "$source/$name"
}
