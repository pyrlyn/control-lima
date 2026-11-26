package controllima.shared

import kotlinx.serialization.Serializable

/** Where a VM comes from. [slug] is the path segment used in the API. */
@Serializable
enum class VmSource(val slug: String) {
    LIMA("lima"), COLIMA("colima");

    companion object {
        fun fromSlug(slug: String): VmSource? = entries.firstOrNull { it.slug == slug.lowercase() }
    }
}

/**
 * VM state as ControlLima sees it. [UNRESPONSIVE] is ours: the CLI reports Running,
 * but the guest does not answer a probe within its timeout.
 */
@Serializable
enum class VmStatus { RUNNING, STOPPED, BROKEN, UNRESPONSIVE, UNKNOWN }

@Serializable
data class PortForward(
    val guestPort: Int,
    val hostPort: Int,
    val hostIp: String = "127.0.0.1",
    val proto: String = "tcp",
)

@Serializable
data class Vm(
    val name: String,
    val source: VmSource,
    val status: VmStatus,
    val arch: String? = null,
    val vmType: String? = null,
    val cpus: Int? = null,
    val memoryBytes: Long? = null,
    val diskBytes: Long? = null,
    val sshPort: Int? = null,
    val ipAddresses: List<String> = emptyList(),
    val portForwards: List<PortForward> = emptyList(),
    val runtime: String? = null,
    val protected: Boolean = false,
    /** `LIMA_HOME` for Lima VMs; null means the default home. */
    val home: String? = null,
    /** Instance directory (`$LIMA_HOME/<name>`), used for logs and configs. */
    val dir: String? = null,
    val sshConfigFile: String? = null,
    /** When the VM was first seen as UNRESPONSIVE, for "Unresponsive since". */
    val unresponsiveSinceMillis: Long? = null,
    val mounts: List<String> = emptyList(),
    val message: String? = null,
    /** Host process of the VM (VZ host agent or QEMU), for host CPU/RSS and port ownership. */
    val hostPid: Long? = null,
) {
    val ref: VmRef get() = VmRef(source, name, home)
}

/** Identity of a VM: (source, home, name). See docs/plan/risks.md. */
@Serializable
data class VmRef(val source: VmSource, val name: String, val home: String? = null) {
    val targetId: String get() = Targets.vm(this)
}

@Serializable
data class ContainerPort(
    val privatePort: Int,
    val publicPort: Int? = null,
    val ip: String? = null,
    val type: String = "tcp",
)

@Serializable
data class Container(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: List<ContainerPort> = emptyList(),
    val ipAddresses: List<String> = emptyList(),
    val createdEpochSeconds: Long? = null,
    val profile: String = "default",
    val labels: Map<String, String> = emptyMap(),
    val imageId: String? = null,
) {
    val targetId: String get() = Targets.container(profile, id)
    val running: Boolean get() = state == "running"
}

@Serializable
data class Mount(val source: String, val destination: String, val readWrite: Boolean = true)

/** `GET /api/containers/{id}`: inspect summary. Env values are masked unless revealed by an admin. */
@Serializable
data class ContainerDetail(
    val container: Container,
    val command: List<String> = emptyList(),
    val env: List<String> = emptyList(),
    val mounts: List<Mount> = emptyList(),
    val networks: Map<String, String> = emptyMap(),
    val restartPolicy: String? = null,
    val restartCount: Int = 0,
    val exitCode: Int? = null,
    val startedAt: String? = null,
)

@Serializable
data class Image(
    val id: String,
    val tags: List<String>,
    val sizeBytes: Long,
    val createdEpochSeconds: Long? = null,
    val containers: Int? = null,
    /** Names of containers using this image. */
    val usedBy: List<String> = emptyList(),
    val profile: String = "default",
) {
    val dangling: Boolean get() = tags.isEmpty()
}

@Serializable
data class ImageLayer(val id: String, val createdBy: String, val sizeBytes: Long, val createdEpochSeconds: Long? = null)

@Serializable
data class ImageDetail(
    val image: Image,
    val architecture: String? = null,
    val os: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val layers: List<ImageLayer> = emptyList(),
)

/** One point of live load for a VM or a container. */
@Serializable
data class StatsSample(
    val targetId: String,
    val epochMillis: Long,
    val cpuPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val netRxBytesPerSec: Double? = null,
    val netTxBytesPerSec: Double? = null,
    val diskReadBytesPerSec: Double? = null,
    val diskWriteBytesPerSec: Double? = null,
    val diskUsedBytes: Long? = null,
    val diskTotalBytes: Long? = null,
    val load1: Double? = null,
    val probeLatencyMs: Double? = null,
    val hostCpuPercent: Double? = null,
    val hostRssBytes: Long? = null,
) {
    val memoryPercent: Double? get() = percent(memoryUsedBytes, memoryTotalBytes)
    val diskPercent: Double? get() = percent(diskUsedBytes, diskTotalBytes)

    private fun percent(used: Long?, total: Long?): Double? =
        if (used == null || total == null || total <= 0) null else used * 100.0 / total
}

@Serializable
data class Health(val status: String, val version: String)

@Serializable
data class ApiError(val code: String, val message: String)

/** Per-source error in a list response, so the other source still shows (overview "error" state). */
@Serializable
data class SourceError(val source: String, val message: String)

@Serializable
data class VmList(val vms: List<Vm>, val errors: List<SourceError> = emptyList())

@Serializable
data class ContainerList(
    val profile: String,
    val containers: List<Container>,
    /** Non-null when the Docker socket is missing or the API failed. */
    val error: String? = null,
    val unavailable: Boolean = false,
)

@Serializable
data class ImageList(
    val profile: String,
    val images: List<Image>,
    val totalBytes: Long = 0,
    val reclaimableBytes: Long = 0,
    val error: String? = null,
)

@Serializable
data class VmDetail(
    val vm: Vm,
    val containers: List<Container> = emptyList(),
    val config: String? = null,
    val sshCommand: String? = null,
    val guestIps: List<String> = emptyList(),
    val snapshots: List<String> = emptyList(),
    val limaVersion: String? = null,
)
