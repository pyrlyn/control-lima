package controllima.providers.lima

import controllima.shared.StatsSample

/** Raw counters read from the guest in one go; rates need two snapshots. */
data class GuestSnapshot(
    val cpuTotal: Long,
    val cpuIdle: Long,
    val memTotal: Long?,
    val memAvailable: Long?,
    val netRx: Long,
    val netTx: Long,
    val diskReadSectors: Long,
    val diskWriteSectors: Long,
    val load1: Double?,
    val fsBlockSize: Long?,
    val fsBlocks: Long?,
    val fsFree: Long?,
)

/**
 * Parses the stats block stream produced by [script]: kernel /proc files and a printf-style `stat -f`
 * line, which are machine formats (no human-readable tables).
 */
class GuestStatsParser(private val onSnapshot: (GuestSnapshot) -> Unit) {
    private var section: String? = null
    private val sections = mutableMapOf<String, MutableList<String>>()

    fun accept(line: String) {
        if (line.startsWith(MARK)) {
            val name = line.removePrefix(MARK).trim()
            if (name == "end") {
                parse(sections)?.let(onSnapshot)
                sections.clear()
                section = null
            } else {
                section = name
                sections[name] = mutableListOf()
            }
            return
        }
        section?.let { sections.getValue(it) += line }
    }

    companion object {
        const val MARK = "@@"

        fun script(intervalSeconds: Int): String {
            require(intervalSeconds in 1..3600)
            return "while :; do " +
                "echo @@stat; head -n1 /proc/stat; " +
                "echo @@meminfo; cat /proc/meminfo; " +
                "echo @@netdev; cat /proc/net/dev; " +
                "echo @@diskstats; cat /proc/diskstats; " +
                "echo @@loadavg; cat /proc/loadavg; " +
                "echo @@fs; stat -f -c '%S %b %f' /; " +
                "echo @@end; sleep $intervalSeconds; done"
        }

        private val WHOLE_DISK = Regex("^(vd[a-z]+|sd[a-z]+|xvd[a-z]+|nvme\\d+n\\d+)$")
        private val WS = Regex("\\s+")

        fun parse(s: Map<String, List<String>>): GuestSnapshot? {
            val cpu = s["stat"]?.firstOrNull { it.startsWith("cpu ") }?.trim()?.split(WS)?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
            if (cpu.size < 4) return null
            // idle + iowait count as idle, like top does.
            val idle = cpu[3] + (cpu.getOrNull(4) ?: 0)
            val total = cpu.take(8).sum()
            val mem = s["meminfo"].orEmpty().associate { line ->
                val key = line.substringBefore(':')
                key to line.substringAfter(':').trim().split(WS).firstOrNull()?.toLongOrNull()?.times(1024)
            }
            var rx = 0L
            var tx = 0L
            s["netdev"].orEmpty().filter { it.contains(':') }.forEach { line ->
                val iface = line.substringBefore(':').trim()
                if (iface == "lo") return@forEach
                val f = line.substringAfter(':').trim().split(WS).mapNotNull { it.toLongOrNull() }
                if (f.size >= 9) { rx += f[0]; tx += f[8] }
            }
            var read = 0L
            var write = 0L
            s["diskstats"].orEmpty().forEach { line ->
                val f = line.trim().split(WS)
                if (f.size >= 10 && WHOLE_DISK.matches(f[2])) {
                    read += f[5].toLongOrNull() ?: 0
                    write += f[9].toLongOrNull() ?: 0
                }
            }
            val load = s["loadavg"]?.firstOrNull()?.trim()?.split(WS)?.firstOrNull()?.toDoubleOrNull()
            val fs = s["fs"]?.firstOrNull()?.trim()?.split(WS)?.mapNotNull { it.toLongOrNull() }
            return GuestSnapshot(
                cpuTotal = total,
                cpuIdle = idle,
                memTotal = mem["MemTotal"],
                memAvailable = mem["MemAvailable"],
                netRx = rx,
                netTx = tx,
                diskReadSectors = read,
                diskWriteSectors = write,
                load1 = load,
                fsBlockSize = fs?.getOrNull(0),
                fsBlocks = fs?.getOrNull(1),
                fsFree = fs?.getOrNull(2),
            )
        }

        /** Turns two consecutive snapshots into a [StatsSample]; counters that went backwards (reboot) give null. */
        fun sample(targetId: String, prev: GuestSnapshot?, cur: GuestSnapshot, elapsedMillis: Long, nowMillis: Long): StatsSample {
            val seconds = elapsedMillis / 1000.0
            fun rate(a: Long?, b: Long, scale: Long = 1): Double? =
                if (a == null || seconds <= 0 || b < a) null else (b - a) * scale / seconds
            val cpu = prev?.let {
                val dt = cur.cpuTotal - it.cpuTotal
                val di = cur.cpuIdle - it.cpuIdle
                if (dt > 0 && di >= 0) ((dt - di) * 100.0 / dt).coerceIn(0.0, 100.0) else null
            }
            val used = if (cur.memTotal != null && cur.memAvailable != null) cur.memTotal - cur.memAvailable else null
            val bs = cur.fsBlockSize
            return StatsSample(
                targetId = targetId,
                epochMillis = nowMillis,
                cpuPercent = cpu,
                memoryUsedBytes = used,
                memoryTotalBytes = cur.memTotal,
                netRxBytesPerSec = rate(prev?.netRx, cur.netRx),
                netTxBytesPerSec = rate(prev?.netTx, cur.netTx),
                diskReadBytesPerSec = rate(prev?.diskReadSectors, cur.diskReadSectors, 512),
                diskWriteBytesPerSec = rate(prev?.diskWriteSectors, cur.diskWriteSectors, 512),
                diskUsedBytes = if (bs != null && cur.fsBlocks != null && cur.fsFree != null) (cur.fsBlocks - cur.fsFree) * bs else null,
                diskTotalBytes = if (bs != null && cur.fsBlocks != null) cur.fsBlocks * bs else null,
                load1 = cur.load1,
            )
        }
    }
}
