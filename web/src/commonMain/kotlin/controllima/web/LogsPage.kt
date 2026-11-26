package controllima.web

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import controllima.shared.ApiJson
import controllima.shared.ApiRoutes
import controllima.shared.Format
import controllima.shared.LogBatch
import controllima.shared.LogLine
import controllima.shared.LogSource
import controllima.shared.Targets

/** Lines kept in the browser; older ones drop off so a chatty container cannot exhaust memory. */
private const val MAX_LINES = 5_000
private const val TAIL = 500

/**
 * Logs page (docs/pages/logs.md). [initial] is a source id (`target#kind`) or a bare target id, which
 * preselects that target's first available source. [embedded] drops the page header for detail tabs.
 */
@Composable
fun LogsPage(m: AppModel, initial: String?, embedded: Boolean = false) {
    // Pod sources exist only when asked for by target: a cluster can have hundreds of pods.
    val kubeTarget = initial?.substringBefore('#')?.takeIf { it.startsWith("kube:") }
    val sources = load(kubeTarget) { m.api.logSources() + (kubeTarget?.let { m.api.logSources(it) }.orEmpty()) }
    var target by remember(initial) { mutableStateOf(initial?.substringBefore('#')) }
    var sourceId by remember(initial) { mutableStateOf(initial?.takeIf { '#' in it }) }
    var follow by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("all") }
    var stream by remember { mutableStateOf("all") }
    var wrap by remember { mutableStateOf(true) }
    if (!embedded) PageHeader(Page.Logs.title)
    val all = sources.value
    if (all == null) {
        if (sources.error != null) Banner(sources.error, action = Strings.RETRY to sources.reload) else Skeleton(3)
        return
    }
    val targets = all.map { it.target }.distinct()
    // Docker sources carry the container name, which the profile's container list may not have loaded yet.
    val names = all.filter { it.kind == "docker" }.associate { it.target to it.label.substringBefore(':') }
    val label = { t: String -> names[t] ?: m.label(t) }
    val forTarget = all.filter { it.target == target }
    if (sourceId == null || forTarget.none { it.id == sourceId }) {
        sourceId = forTarget.firstOrNull { it.available }?.id
    }
    val source = forTarget.firstOrNull { it.id == sourceId }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        if (!embedded) Picker("Target", targets, target, label) { target = it; sourceId = null }
        Picker("Source", forTarget, source, LogSource::label) { sourceId = it.id }
        TextButton(onClick = { follow = !follow }) { Text(if (follow) "Pause" else "Follow") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(wrap, { wrap = it })
            Text("Wrap")
        }
    }
    when {
        target == null -> {
            EmptyState("Pick a target", "Containers, Lima host agents, serial consoles, guest journals and ControlLima itself.")
            return
        }
        source == null -> {
            EmptyState("No log sources for ${label(target!!)}")
            return
        }
        !source.available -> {
            Banner(source.reason ?: "This source is not available.", BannerKind.WARNING)
            // A dead guest cannot serve its journal; the host-side logs still can.
            val fallbacks = forTarget.filter { it.available && (it.kind.startsWith("ha.") || it.kind.startsWith("serial")) }
            if (fallbacks.isNotEmpty()) {
                Row { fallbacks.forEach { f -> SmallAction(f.label) { sourceId = f.id } } }
            }
            return
        }
    }
    LogView(m, source!!, follow, query, { query = it }, level, { level = it }, stream, { stream = it }, wrap, embedded)
}

@Composable
private fun LogView(
    m: AppModel,
    source: LogSource,
    follow: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    level: String,
    onLevel: (String) -> Unit,
    stream: String,
    onStream: (String) -> Unit,
    wrap: Boolean,
    embedded: Boolean,
) {
    val lines = remember(source.id) { mutableStateListOf<LogLine>() }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    var loading by remember(source.id) { mutableStateOf(true) }
    var nonce by remember { mutableStateOf(0) }
    if (follow) {
        DisposableEffect(source.id) {
            val path = "${ApiRoutes.LOGS_WS}?source=${ApiRoutes.encode(source.id)}&tail=$TAIL"
            var socket: LiveSocket? = null
            socket = LiveSocket(path, { m.api.token }, onMessage = { text ->
                val batch = runCatching { ApiJson.decodeFromString(LogBatch.serializer(), text) }.getOrNull() ?: return@LiveSocket
                loading = false
                if (batch.error != null) {
                    error = batch.error
                    // The server ends the stream after an error; reconnecting would only repeat it.
                    socket?.close()
                } else {
                    append(lines, batch.lines)
                }
            }, onOpen = {
                // Every (re)connect replays the tail, so start over instead of duplicating it.
                lines.clear()
                error = null
            }).open()
            onDispose { socket?.close() }
        }
    } else {
        LaunchedEffect(source.id, follow, nonce) {
            try {
                lines.clear()
                append(lines, m.api.logs(source.id, TAIL))
                error = null
            } catch (e: controllima.shared.ApiException) {
                error = e.message
            }
            loading = false
        }
    }
    val levels = listOf("all") + lines.mapNotNull { it.level?.lowercase() }.distinct().sorted()
    val streams = listOf("all") + lines.map { it.stream }.distinct().sorted()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        SearchField(query, onQuery, if (embedded) null else m, "Search in lines")
        if (levels.size > 2) Picker("Level", levels, level, { it }, onLevel)
        if (streams.size > 2) Picker("Stream", streams, stream, { it }, onStream)
        TextButton(onClick = {
            val text = lines.joinToString("\n") { l -> (l.ts?.let { Format.timestamp(it) + " " } ?: "") + "[${l.stream}] " + l.line }
            saveText(text, source.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".log")
        }) { Text("Download") }
        if (follow) Marked(Glyph.DOT, "following", Palette.ok) else Marked(Glyph.BARS, "paused", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Banner(it, action = if (follow) null else Strings.RETRY to { loading = true; nonce++ }) }
    val shown = lines.filter { l ->
        (level == "all" || l.level?.lowercase() == level) && (stream == "all" || l.stream == stream) &&
            (query.isBlank() || l.line.contains(query, ignoreCase = true))
    }
    if (shown.isEmpty()) {
        if (loading) Skeleton(6) else if (error == null) EmptyState(Strings.NO_LOG_LINES)
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(shown.size, follow) {
        if (follow) state.scrollToItem(shown.lastIndex)
    }
    // The page itself scrolls, so the list needs a fixed height to stay virtualized.
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(if (embedded) 480.dp else 640.dp)) {
        itemsIndexed(shown) { _, l ->
            val text = highlighted(l, query)
            val base = Modifier.fillMaxWidth().padding(vertical = 1.dp)
            androidx.compose.material3.Text(
                text,
                if (wrap) base else base.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                softWrap = wrap,
                color = when {
                    l.level?.lowercase() in setOf("error", "fatal", "crit", "critical") -> MaterialTheme.colorScheme.error
                    l.stream == "stderr" -> Palette.warning
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
    if (lines.size >= MAX_LINES) Text("Showing the last $MAX_LINES lines.", style = MaterialTheme.typography.bodySmall)
}

private fun append(lines: MutableList<LogLine>, more: List<LogLine>) {
    lines.addAll(more)
    val extra = lines.size - MAX_LINES
    if (extra > 0) lines.subList(0, extra).clear()
}

private fun highlighted(l: LogLine, query: String): AnnotatedString = buildAnnotatedString {
    l.ts?.let { append(Format.timestamp(it).drop(11) + "  ") }
    val text = l.line
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    var from = 0
    while (true) {
        val i = text.indexOf(query, from, ignoreCase = true)
        if (i < 0) break
        append(text.substring(from, i))
        pushStyle(SpanStyle(background = Palette.warningContainer))
        append(text.substring(i, i + query.length))
        pop()
        from = i + query.length
    }
    append(text.substring(from))
}
