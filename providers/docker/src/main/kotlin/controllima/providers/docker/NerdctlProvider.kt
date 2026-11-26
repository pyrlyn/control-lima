package controllima.providers.docker

import controllima.shared.Container
import controllima.shared.ContainerDetail
import controllima.shared.Image
import controllima.shared.LogLine
import controllima.shared.Names
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class GuestResult(val ok: Boolean, val stdout: String, val stderr: String)

/** A shell inside one guest. The server implements it over Lima's `ssh.config`; tests pass a fake. */
interface GuestCommands {
    fun run(command: String, timeout: Duration): GuestResult
    fun stream(command: String, onLine: (String) -> Unit, onExit: () -> Unit): Closeable
}

/**
 * containerd containers in a Lima VM, through the guest's `nerdctl` (docs/plan/README.md, v2).
 * `nerdctl inspect` prints Docker-compatible JSON, so the Engine API parsers are reused instead of
 * scraping `nerdctl ps` tables. Images, stats and pruning stay Docker-only for now.
 */
class NerdctlProvider(private val guest: GuestCommands, private val profile: String) : DockerProvider {

    override fun containers(): List<Container> =
        DockerJson.parseInspectList(run(LIST), profile).map { it.container }

    override fun images(): List<Image> = throw UnsupportedOperationException("Images are not listed for nerdctl VMs yet")

    override fun inspect(id: String): ContainerDetail =
        DockerJson.parseInspectList(run("nerdctl inspect --mode=dockercompat --type=container ${arg(id)}"), profile).singleOrNull()
            ?: throw DockerException("No such container: $id")

    override fun start(id: String) { run("nerdctl start ${arg(id)}", 1.minutes) }
    override fun stop(id: String) { run("nerdctl stop ${arg(id)}", 1.minutes) }
    override fun restart(id: String) { run("nerdctl restart ${arg(id)}", 1.minutes) }

    override fun rename(id: String, newName: String) {
        if (!Names.isValidContainerName(newName)) throw DockerException("Invalid container name: $newName")
        run("nerdctl rename ${arg(id)} ${arg(newName)}")
    }

    override fun remove(id: String, force: Boolean) { run("nerdctl rm ${if (force) "-f " else ""}${arg(id)}", 1.minutes) }

    override fun logs(id: String, tail: Int, follow: Boolean, sinceSeconds: Long?, onEnd: () -> Unit, onLine: (LogLine) -> Unit): Closeable {
        val command = buildString {
            append("nerdctl logs --timestamps --tail ").append(tail.coerceIn(0, 50_000))
            sinceSeconds?.let { append(" --since ").append(it.coerceAtLeast(0)).append('s') }
            if (follow) append(" --follow")
            // The ssh session carries only stdout back, so the container's stderr is folded into it.
            append(' ').append(arg(id)).append(" 2>&1")
        }
        if (follow) return guest.stream(command, { onLine(DockerLogDecoder.parse(it, "stdout")) }, onEnd)
        try {
            run(command, 30.seconds).lineSequence().filter { it.isNotEmpty() }.forEach { onLine(DockerLogDecoder.parse(it, "stdout")) }
        } finally {
            onEnd()
        }
        return Closeable { }
    }

    private fun run(command: String, timeout: Duration = 15.seconds): String {
        val result = guest.run(command, timeout)
        if (!result.ok) throw DockerException(result.stderr.trim().ifEmpty { "nerdctl failed in $profile" })
        return result.stdout
    }

    /** Valid refs are `[a-zA-Z0-9_.-]` only, so quoting is a second guard rather than the escape. */
    private fun arg(ref: String): String {
        if (!Names.isValidContainerRef(ref)) throw DockerException("Invalid container reference: $ref")
        return "'$ref'"
    }

    companion object {
        /** One round trip: `inspect` all ids, or an empty array when there are none (nerdctl errors on no args). */
        const val LIST = "ids=\$(nerdctl ps -a -q --no-trunc) && if [ -n \"\$ids\" ]; then nerdctl inspect --mode=dockercompat --type=container \$ids; else echo '[]'; fi"

        /** Prints `yes` when nerdctl is installed; used to decide which Lima VMs get a container list. */
        const val DETECT = "command -v nerdctl >/dev/null 2>&1 && echo yes || true"
    }
}
