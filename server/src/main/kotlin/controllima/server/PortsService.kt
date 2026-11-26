package controllima.server

import controllima.shared.Container
import controllima.shared.HostListener
import controllima.shared.IpEntry
import controllima.shared.KubeCluster
import controllima.shared.KubePortForward
import controllima.shared.KubeService
import controllima.shared.NetworkInfo
import controllima.shared.PortEntry
import controllima.shared.PortOwnerKind
import controllima.shared.PortsReport
import controllima.shared.Vm
import controllima.shared.VmStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Ports, forwards, IPs and conflicts (docs/plan/ports-network.md). */
class PortsService(
    private val backends: Backends,
    private val inventory: Inventory,
    private val containers: ContainerInventory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kube: KubernetesService? = null,
    private val forwards: () -> List<KubePortForward> = { emptyList() },
) {
    @Volatile private var listenersCache: Triple<Long, List<HostListener>, String?>? = null
    private val guestIps = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** lsof can be slow: 5 s timeout in the provider, cached for 10 s here. */
    suspend fun listeners(): Pair<List<HostListener>, String?> {
        listenersCache?.let { (at, list, warn) -> if (clock() - at < 10_000) return list to warn }
        val (list, warn) = withContext(Dispatchers.IO) {
            runCatching { backends.host.listeners() to null }.getOrElse { emptyList<HostListener>() to "Host listeners unavailable: ${it.message}" }
        }
        listenersCache = Triple(clock(), list, warn)
        return list to warn
    }

    /** Guest IPs via `ip -j addr` over SSH, cached for a minute; empty for VMs that are not running. */
    suspend fun guestIps(vm: Vm): List<String> {
        if (vm.status != VmStatus.RUNNING) return emptyList()
        val target = vm.ref.targetId
        guestIps[target]?.let { (at, ips) -> if (clock() - at < 60_000) return ips }
        val cfg = vm.sshConfigFile ?: return emptyList()
        val instance = backends.limaInstance(vm)
        val ips = withContext(Dispatchers.IO) { runCatching { backends.guest.ipAddresses(cfg, instance) }.getOrDefault(emptyList()) }
        guestIps[target] = clock() to ips
        return ips
    }

    suspend fun report(): PortsReport {
        val vms = inventory.vms().vms
        val lists = containers.all()
        val allContainers = lists.flatMap { it.containers }
        val (listeners, lsofWarning) = listeners()
        val warnings = listOfNotNull(lsofWarning) + lists.filter { it.unavailable }.map { "Containers of profile ${it.profile}: ${it.error}" }
        val services = runCatching { kube?.nodePortServices() }.getOrNull().orEmpty()
        val active = forwards()
        val forwarded = active.map { it.localPort }.toSet()
        // A forward's listener is this process; its own row says more than "java (pid)".
        val ports = build(vms, allContainers, listeners.filterNot { it.port in forwarded && it.hostIp == "127.0.0.1" }) +
            nodePorts(services) + forwardPorts(active)
        val ips = vms.map { vm ->
            IpEntry(vm.name, vm.ref.targetId, (vm.ipAddresses + guestIps(vm)).distinct())
        } + allContainers.filter { it.ipAddresses.isNotEmpty() }.map { IpEntry(it.name, it.targetId, it.ipAddresses) }
        return PortsReport(ports, ips, warnings)
    }

    suspend fun networks(): List<NetworkInfo> {
        val docker = backends.colimaProfiles().flatMap { profile ->
            val provider = containers.docker(profile) ?: return@flatMap emptyList()
            withContext(Dispatchers.IO) { runCatching { provider.networks() }.getOrDefault(emptyList()) }
                .map { it.copy(details = listOfNotNull("profile $profile", it.details).joinToString(", ")) }
        }
        val lima = backends.limaProviders().flatMap { provider ->
            val home = provider.home ?: System.getenv("LIMA_HOME") ?: "${System.getProperty("user.home")}/.lima"
            limaNetworks(File(home, "_config/networks.yaml"))
        }
        return lima + docker
    }

    companion object {
        private val FORWARD = Regex("(Stopping forwarding|Not forwarding|Forwarding) (TCP|UDP) from (\\S+?):(\\d+) to (\\S+?):(\\d+)")
        private val HELPERS = setOf("limactl", "ssh", "com.docker", "docker-proxy", "colima", "vpnkit", "gvproxy", "socket_vmnet", "qemu-system-aarch64", "qemu-system-x86_64", "lima-guestagent")

        /** Effective forwards from the host agent log: host port → guest port, last state wins. */
        fun effectiveForwards(log: File): Map<Int, Int> {
            if (!log.isFile) return emptyMap()
            val out = mutableMapOf<Int, Int>()
            for (line in Inventory.tailText(log, 64 * 1024).lineSequence()) {
                val m = FORWARD.find(line) ?: continue
                val guestPort = m.groupValues[4].toIntOrNull() ?: continue
                val hostPort = m.groupValues[6].toIntOrNull() ?: continue
                if (m.groupValues[1] == "Forwarding") out[hostPort] = guestPort else out.remove(hostPort)
            }
            return out
        }

        /** Network names from Lima's `networks.yaml`: the keys one level under `networks:`. */
        fun limaNetworks(file: File): List<NetworkInfo> {
            if (!file.isFile) return emptyList()
            val out = mutableListOf<NetworkInfo>()
            var inNetworks = false
            for (line in file.readLines()) {
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                if (!line.startsWith(" ")) { inNetworks = line.trim() == "networks:"; continue }
                if (!inNetworks) continue
                val indent = line.length - line.trimStart().length
                if (indent == 2 && line.trim().endsWith(":")) out += NetworkInfo(line.trim().removeSuffix(":"), "lima", details = file.path)
                if (indent == 4 && out.isNotEmpty() && line.trim().startsWith("mode:")) {
                    out[out.lastIndex] = out.last().copy(driver = line.substringAfter("mode:").trim())
                }
            }
            return out
        }

        /**
         * Lima's guest agent forwards Kubernetes node ports to localhost, so each one is listed as a host port.
         * They carry no conflict check: the VM's own forward of the same port is the expected path, not a clash.
         */
        fun nodePorts(services: List<Pair<KubeCluster, KubeService>>): List<PortEntry> = services.flatMap { (cluster, svc) ->
            svc.ports.mapNotNull { p ->
                val nodePort = p.nodePort ?: return@mapNotNull null
                PortEntry("127.0.0.1", nodePort, p.protocol.lowercase(), p.port, PortOwnerKind.SERVICE,
                    "${svc.namespace}/${svc.name} (${cluster.label})", "kube:${cluster.id}", svc.type)
            }
        }.sortedBy { it.hostPort }

        fun forwardPorts(forwards: List<KubePortForward>): List<PortEntry> = forwards.map { f ->
            PortEntry("127.0.0.1", f.localPort, "tcp", f.port, PortOwnerKind.FORWARD,
                "${f.kind.name.lowercase()} ${f.namespace}/${f.name} (${f.clusterId})", "kube:${f.clusterId}",
                "forwarding, ${f.activeConnections} open")
        }

        private fun overlaps(a: String, b: String): Boolean {
            val any = setOf("*", "0.0.0.0", "::", "")
            return a == b || a in any || b in any
        }

        fun build(vms: List<Vm>, containers: List<Container>, listeners: List<HostListener>): List<PortEntry> {
            val entries = mutableListOf<PortEntry>()
            for (vm in vms) {
                val effective = vm.dir?.let { effectiveForwards(File(it, "ha.stderr.log")) }.orEmpty()
                val running = vm.status == VmStatus.RUNNING
                for (f in vm.portForwards) {
                    val state = if (!running) "stopped" else if (f.hostPort in effective) "active" else "configured"
                    entries += PortEntry(f.hostIp, f.hostPort, f.proto, f.guestPort, PortOwnerKind.VM, vm.name, vm.ref.targetId, state)
                }
                if (running) {
                    val configured = vm.portForwards.map { it.hostPort }.toSet()
                    for ((host, guest) in effective) if (host !in configured && host != vm.sshPort) {
                        entries += PortEntry("127.0.0.1", host, "tcp", guest, PortOwnerKind.VM, vm.name, vm.ref.targetId, "auto")
                    }
                }
                vm.sshPort?.takeIf { it > 0 }?.let {
                    entries += PortEntry("127.0.0.1", it, "tcp", 22, PortOwnerKind.VM, vm.name, vm.ref.targetId, if (running) "ssh" else "stopped")
                }
            }
            for (c in containers) for (p in c.ports) {
                val public = p.publicPort ?: continue
                entries += PortEntry(p.ip ?: "0.0.0.0", public, p.type, p.privatePort, PortOwnerKind.CONTAINER, c.name, c.targetId, c.state)
            }
            val pidOwner = vms.mapNotNull { vm -> vm.hostPid?.let { it.toInt() to vm } }.toMap()
            val explained = entries.map { it.hostPort }.toSet()
            for (l in listeners) {
                val vm = pidOwner[l.pid]
                if (l.port in explained) continue
                entries += if (vm != null) PortEntry(l.hostIp, l.port, "tcp", null, PortOwnerKind.VM, vm.name, vm.ref.targetId, "listening")
                else PortEntry(l.hostIp, l.port, "tcp", null, PortOwnerKind.PROCESS, "${l.command} (${l.pid})", null, "listening")
            }
            return entries.map { e -> e.copy(conflict = conflictOf(e, entries, listeners, pidOwner)) }
                .sortedWith(compareBy({ it.hostPort }, { it.owner }))
        }

        private fun conflictOf(e: PortEntry, all: List<PortEntry>, listeners: List<HostListener>, pidOwner: Map<Int, Vm>): String? {
            val reasons = mutableListOf<String>()
            if (e.ownerKind != PortOwnerKind.PROCESS && e.state != "stopped") {
                all.filter { it !== e && it.hostPort == e.hostPort && it.proto == e.proto && it.ownerTarget != e.ownerTarget && it.state != "stopped" && overlaps(it.hostIp, e.hostIp) }
                    .forEach { reasons += "also forwarded by ${it.owner}" }
                listeners.filter { it.port == e.hostPort && overlaps(it.hostIp, e.hostIp) }
                    .filter { l -> pidOwner[l.pid]?.ref?.targetId != e.ownerTarget && HELPERS.none { l.command.startsWith(it) } }
                    .forEach { reasons += "shadowed by ${it.command} (pid ${it.pid})" }
            }
            if (e.hostPort in 1..1023) reasons += "privileged port"
            return reasons.distinct().joinToString("; ").ifEmpty { null }
        }
    }
}
