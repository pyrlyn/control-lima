package controllima.shared

import kotlinx.serialization.Serializable

/** Where a VM comes from. */
@Serializable
enum class VmSource { LIMA, COLIMA }

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
)

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
)

@Serializable
data class Image(
    val id: String,
    val tags: List<String>,
    val sizeBytes: Long,
    val createdEpochSeconds: Long? = null,
    val containers: Int? = null,
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
)

@Serializable
data class Health(val status: String, val version: String)

@Serializable
data class ApiError(val code: String, val message: String)
