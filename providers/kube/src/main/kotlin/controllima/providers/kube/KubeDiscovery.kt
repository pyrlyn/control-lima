package controllima.providers.kube

import controllima.shared.KubeCluster
import java.nio.file.Files
import java.nio.file.Path

/** A discovered cluster plus the kubeconfig it came from, so a client can be built for it later. */
data class KubeTarget(val cluster: KubeCluster, val kubeconfig: Path) {
    fun access(): KubeAccess = Kubeconfig.parse(Files.readString(kubeconfig), cluster.context, kubeconfig.parent)
}

/**
 * Finds clusters without running kubectl. Colima (`colima start --kubernetes`) merges its k3s cluster into
 * `~/.kube/config` as context `colima` (default profile) or `colima-<profile>`; Lima's k3s/k8s templates copy
 * the guest kubeconfig to `<instanceDir>/copied-from-guest/kubeconfig.yaml`.
 */
class KubeDiscovery(private val home: Path) {
    /**
     * [colimaProfiles] are the Colima profiles that run Kubernetes; [limaInstanceDirs] are Lima instance
     * directories (the directory name is the VM name). A Lima VM without a copied kubeconfig is skipped.
     */
    fun discover(colimaProfiles: List<String>, limaInstanceDirs: List<Path>): List<KubeTarget> =
        colimaProfiles.map(::colima) + limaInstanceDirs.mapNotNull(::lima)

    private fun colima(profile: String): KubeTarget {
        val file = home.resolve(".kube").resolve("config")
        val context = colimaContext(profile)
        val base = KubeCluster(
            id = "colima/$profile",
            label = if (profile == DEFAULT_PROFILE) "Colima" else "Colima $profile",
            source = "colima",
            vmName = profile,
            context = context,
            available = false,
        )
        if (!Files.isRegularFile(file)) return KubeTarget(base.copy(error = "$file not found"), file)
        return KubeTarget(check(base, file, context), file)
    }

    private fun lima(instanceDir: Path): KubeTarget? {
        val file = instanceDir.resolve("copied-from-guest").resolve("kubeconfig.yaml")
        if (!Files.isRegularFile(file)) return null
        val vm = instanceDir.fileName.toString()
        val base = KubeCluster(id = "lima/$vm", label = "Lima $vm", source = "lima", vmName = vm, available = false)
        return KubeTarget(check(base, file, null), file)
    }

    private fun check(base: KubeCluster, file: Path, context: String?): KubeCluster = try {
        val text = Files.readString(file)
        if (context != null && context !in Kubeconfig.contextNames(text)) {
            base.copy(error = "context '$context' not found in $file")
        } else {
            val access = Kubeconfig.parse(text, context, file.parent)
            base.copy(context = access.context, server = access.server, available = true)
        }
    } catch (e: Exception) {
        base.copy(error = e.message ?: e.toString())
    }

    companion object {
        const val DEFAULT_PROFILE = "default"

        fun colimaContext(profile: String) = if (profile == DEFAULT_PROFILE) "colima" else "colima-$profile"
    }
}
