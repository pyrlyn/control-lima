package controllima.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import controllima.shared.ApiException
import controllima.shared.Metric
import controllima.shared.MetricPoint
import kotlinx.coroutines.delay

/** Live stats for [targets] while the calling composable is on screen. */
@Composable
fun WatchStats(m: AppModel, targets: List<String>) {
    DisposableEffect(targets.sorted().joinToString(",")) {
        val release = m.watch(targets)
        onDispose { release() }
    }
}

/** Points of [metric] for [target] from the live window (last 10 minutes). */
fun liveSeries(m: AppModel, target: String, metric: String): List<MetricPoint> {
    @Suppress("UNUSED_VARIABLE") val tick = m.statsTick
    return m.window(target)?.samples().orEmpty().mapNotNull { s -> Metric.valuesOf(s)[metric]?.let { MetricPoint(s.epochMillis, it) } }
}

class Loaded<T>(val value: T?, val error: String?, val status: Int?, val loading: Boolean, val reload: () -> Unit)

/**
 * Loads [block] when [keys] change, and again every [everyMillis] if given. Errors keep the last
 * value so pages can show it greyed with the error (docs/pages "Error" states).
 */
@Composable
fun <T> load(vararg keys: Any?, everyMillis: Long? = null, block: suspend () -> T): Loaded<T> {
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var status by remember(*keys) { mutableStateOf<Int?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var nonce by remember { mutableStateOf(0) }
    LaunchedEffect(*keys, nonce) {
        while (true) {
            try {
                value = block()
                error = null
                status = null
            } catch (e: ApiException) {
                error = e.message
                status = e.status
            }
            loading = false
            if (everyMillis == null) break
            delay(everyMillis)
        }
    }
    return Loaded(value, error, status, loading) { nonce++ }
}
