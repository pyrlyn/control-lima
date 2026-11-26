package controllima.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import controllima.shared.ApiException
import controllima.shared.ChartMath
import controllima.shared.Confirm
import controllima.shared.Format
import controllima.shared.Metric
import controllima.shared.MetricPoint
import controllima.shared.Role
import controllima.shared.SizeUnits
import controllima.shared.TimeRange
import controllima.shared.VmStatus
import kotlinx.coroutines.launch

// ---------- layout ----------

@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
            content()
        }
    }
}

enum class BannerKind { ERROR, WARNING, INFO }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.ERROR, action: Pair<String, () -> Unit>? = null) {
    val (bg, fg, icon) = when (kind) {
        BannerKind.ERROR -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Glyph.CROSS)
        BannerKind.WARNING -> Triple(Palette.warningContainer, Palette.onWarningContainer, Glyph.TRIANGLE)
        BannerKind.INFO -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Glyph.RING)
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(icon, fg)
        Text(text, color = fg, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (action != null) TextButton(onClick = action.second) { Text(action.first, color = fg) }
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (action != null) Button(onClick = action.second, modifier = Modifier.padding(top = 16.dp)) { Text(action.first) }
    }
}

/** Grey placeholder bars while the first response is pending (no animation: reduced motion). */
@Composable
fun Skeleton(rows: Int = 4) {
    Column(Modifier.fillMaxWidth()) {
        repeat(rows) {
            Box(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun Labeled(label: String, value: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(160.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", fontFamily = if (value != null && value.length > 30) FontFamily.Monospace else null)
    }
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        Mono(text)
    }
}

// ---------- status and badges ----------

/**
 * Status marks drawn as shapes: the browser font lacks symbols such as ⚠ and ●, and fetching a
 * fallback font from the internet is blocked by our CSP (and would leak usage to a third party).
 */
enum class Glyph { DOT, RING, SQUARE, TRIANGLE, CROSS, BARS, DOWN, UP }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w / 6)
        when (glyph) {
            Glyph.DOT -> drawCircle(color)
            Glyph.RING -> drawCircle(color, radius = w / 2 - stroke.width / 2, style = stroke)
            Glyph.SQUARE -> drawRect(color, topLeft = Offset(w * 0.1f, w * 0.1f), size = Size(w * 0.8f, w * 0.8f))
            Glyph.TRIANGLE, Glyph.DOWN, Glyph.UP -> {
                val path = Path().apply {
                    if (glyph == Glyph.TRIANGLE) {
                        moveTo(w / 2, 0f); lineTo(w, w); lineTo(0f, w)
                    } else if (glyph == Glyph.UP) {
                        moveTo(0f, w * 0.75f); lineTo(w, w * 0.75f); lineTo(w / 2, w * 0.2f)
                    } else {
                        moveTo(0f, w * 0.25f); lineTo(w, w * 0.25f); lineTo(w / 2, w * 0.8f)
                    }
                    close()
                }
                drawPath(path, color)
            }
            Glyph.CROSS -> {
                drawLine(color, Offset(0f, 0f), Offset(w, w), stroke.width * 1.4f)
                drawLine(color, Offset(w, 0f), Offset(0f, w), stroke.width * 1.4f)
            }
            Glyph.BARS -> {
                drawRect(color, topLeft = Offset(w * 0.15f, 0f), size = Size(w * 0.25f, w))
                drawRect(color, topLeft = Offset(w * 0.6f, 0f), size = Size(w * 0.25f, w))
            }
        }
    }
}

/** A glyph followed by its text: state is never shown by color alone (docs/plan/i18n-accessibility.md). */
@Composable
fun Marked(glyph: Glyph, text: String, color: Color, modifier: Modifier = Modifier, fontWeight: FontWeight? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, color)
        Text(text, color = color, fontWeight = fontWeight, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun StatusBadge(status: VmStatus) {
    val color = when (status) {
        VmStatus.RUNNING -> Palette.ok
        VmStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        VmStatus.UNRESPONSIVE, VmStatus.BROKEN -> MaterialTheme.colorScheme.error
        VmStatus.UNKNOWN -> Palette.warning
    }
    val glyph = when (status) {
        VmStatus.RUNNING -> Glyph.DOT
        VmStatus.STOPPED -> Glyph.RING
        VmStatus.BROKEN -> Glyph.CROSS
        VmStatus.UNRESPONSIVE, VmStatus.UNKNOWN -> Glyph.TRIANGLE
    }
    Marked(glyph, Format.status(status), color, fontWeight = FontWeight.Medium)
}

@Composable
fun ContainerState(state: String) {
    val (glyph, color) = when (state) {
        "running" -> Glyph.DOT to Palette.ok
        "exited", "dead" -> Glyph.SQUARE to MaterialTheme.colorScheme.onSurfaceVariant
        "restarting" -> Glyph.RING to Palette.warning
        "paused" -> Glyph.BARS to Palette.warning
        else -> Glyph.RING to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Marked(glyph, state, color)
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(
        text,
        Modifier.padding(start = 6.dp).background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Horizontal usage bar for 0..100 %, with the number next to it. */
@Composable
fun MiniBar(percent: Double?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp).height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
            if (percent != null) {
                val color = if (percent >= 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                Box(Modifier.fillMaxWidth((percent / 100).toFloat().coerceIn(0f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
            }
        }
        Text(Format.percent(percent), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

// ---------- buttons with role checks ----------

/** A button that is disabled, with the reason as its label suffix, when the role is too low. */
@Composable
fun RoleButton(m: AppModel, label: String, required: Role, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val allowed = m.can(required)
    val colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors()
    OutlinedButton(onClick = onClick, enabled = allowed && enabled, colors = colors) {
        Text(label)
    }
}

@Composable
fun SmallAction(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

// ---------- tables ----------

class Col(val title: String, val weight: Float, val sortKey: Any? = null)

@Composable
fun TableHeader(cols: List<Col>, sort: Any? = null, descending: Boolean = false, onSort: (Any) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cols.forEach { c ->
            val mod = Modifier.weight(c.weight).padding(horizontal = 4.dp)
            Row(if (c.sortKey != null) mod.clickable { onSort(c.sortKey) } else mod, verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.sortKey != null && c.sortKey == sort) {
                    Spacer(Modifier.width(4.dp))
                    GlyphIcon(if (descending) Glyph.DOWN else Glyph.UP, MaterialTheme.colorScheme.onSurfaceVariant, 8.dp)
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
fun TableRow(onClick: (() -> Unit)? = null, dim: Boolean = false, highlight: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val base = Modifier.fillMaxWidth().alpha(if (dim) 0.5f else 1f)
    val bg = if (highlight) base.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)) else base
    Row(
        (if (onClick != null) bg.clickable(onClick = onClick) else bg).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
fun RowScope.Cell(weight: Float, content: @Composable () -> Unit) {
    Box(Modifier.weight(weight).padding(horizontal = 4.dp)) { content() }
}

@Composable
fun RowScope.TextCell(weight: Float, text: String?, mono: Boolean = false) {
    Cell(weight) {
        Text(text ?: "—", maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = if (mono) FontFamily.Monospace else null, fontSize = if (mono) 13.sp else 14.sp)
    }
}

// ---------- inputs ----------

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, m: AppModel? = null, placeholder: String = Strings.SEARCH) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (m != null) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            m.searchFocus = focus
            onDispose { if (m.searchFocus === focus) m.searchFocus = null }
        }
    }
    OutlinedTextField(
        value, onChange,
        Modifier.width(260.dp).focusRequester(focus),
        placeholder = { Text("$placeholder  (/)") },
        singleLine = true,
    )
}

@Composable
fun <T> Picker(label: String, options: List<T>, selected: T?, render: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ${selected?.let(render) ?: "—"}")
            Spacer(Modifier.width(6.dp))
            GlyphIcon(Glyph.DOWN, MaterialTheme.colorScheme.primary, 8.dp)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(render(o)) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, render: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(render(o)) }) }
    }
}

@Composable
fun Tabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        titles.forEachIndexed { i, t ->
            val active = i == selected
            Column(Modifier.clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (active) FontWeight.SemiBold else null)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(32.dp).height(2.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
    HorizontalDivider(Modifier.padding(bottom = 12.dp))
}

// ---------- dialogs ----------

/**
 * Shared type-to-confirm dialog (docs/pages/confirm-dialog.md). Confirm is enabled only when the typed
 * text equals [name] exactly; the server checks the same `confirmName` again.
 */
@Composable
fun ConfirmDialog(
    title: String,
    name: String,
    details: List<String>,
    confirmLabel: String = Strings.DELETE,
    isProtected: Boolean = false,
    warning: String? = null,
    onDismiss: () -> Unit,
    onConfirm: suspend (typed: String) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canConfirm = !isProtected && Confirm.matches(typed, name) && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.onKeyEvent { if (it.key == Key.Escape && !busy) { onDismiss(); true } else false },
        title = { Text(title) },
        text = {
            Column {
                details.forEach { Text("• $it", Modifier.padding(bottom = 4.dp)) }
                if (warning != null) Banner(warning, BannerKind.WARNING)
                if (isProtected) {
                    Banner("“$name” is protected. ${Strings.PROTECTED_HINT}", BannerKind.ERROR)
                } else {
                    Text(Strings.format(Strings.TYPE_TO_CONFIRM, "“$name”"), Modifier.padding(top = 8.dp, bottom = 4.dp))
                    OutlinedTextField(typed, { typed = it; error = null }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onConfirm(typed)
                            onDismiss()
                        } catch (e: ApiException) {
                            error = "${e.message} (HTTP ${e.status})"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(Strings.CANCEL) } },
    )
}

/** One text field with validation, for rename, pull and similar. */
@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = Strings.SAVE,
    note: String? = null,
    validate: (String) -> String? = { null },
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val problem = validate(value)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                if (note != null) Text(note, Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value, { value = it; error = null }, label = { Text(label) }, singleLine = true, isError = problem != null && value.isNotEmpty(), modifier = Modifier.fillMaxWidth())
                val shown = error ?: problem?.takeIf { value.isNotEmpty() }
                if (shown != null) Text(shown, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        onConfirm(value)
                        onDismiss()
                    } catch (e: ApiException) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy && problem == null && value.isNotEmpty()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(Strings.CANCEL) } },
    )
}

@Composable
fun InfoDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.CLOSE) } },
    )
}

// ---------- charts ----------

class ChartLine(val label: String, val points: List<MetricPoint>, val step: Long = 2_000)

/** Line chart on a Canvas: nice Y scale, axis labels, shaded gaps, and a hover readout. */
@Composable
fun LineChart(
    lines: List<ChartLine>,
    from: Long,
    to: Long,
    metric: String,
    units: SizeUnits,
    gaps: List<TimeRange> = emptyList(),
    height: Int = 180,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val all = lines.flatMap { l -> l.points.map { it.avg } }
    val fixed = if (Metric.unit(metric) == Metric.Unit.PERCENT) 100.0 else null
    val scale = ChartMath.niceScale(all, fixedMax = fixed)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gapColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    Column(modifier) {
        // Legend with names, so series are not told apart by color alone.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            lines.forEachIndexed { i, l ->
                val t = hoverX?.let { x -> from + ((to - from) * (x / widthPx)).toLong() }
                val value = t?.let { ChartMath.nearest(l.points, it)?.avg }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Palette.series(i), RoundedCornerShape(2.dp)))
                    Text(" ${l.label}" + (value?.let { " · ${Format.metric(metric, it, units)}" } ?: ""), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(height.dp).padding(top = 4.dp)
                .border(1.dp, axis.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            hoverX = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position?.x
                        }
                    }
                },
        ) {
            widthPx = size.width
            val left = 56f
            val w = size.width - left
            val h = size.height - 16f
            for (g in gaps) {
                val x1 = left + ChartMath.x(g.fromMillis.coerceAtLeast(from), from, to, w)
                val x2 = left + ChartMath.x(g.toMillis.coerceAtMost(to), from, to, w)
                if (x2 > x1) drawRect(gapColor, Offset(x1, 0f), Size(x2 - x1, h))
            }
            for (tick in scale.ticks) {
                val y = ChartMath.y(tick, scale, h)
                drawLine(axis.copy(alpha = 0.5f), Offset(left, y), Offset(size.width, y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                drawText(measurer, Format.metric(metric, tick, units), Offset(2f, (y - 7f).coerceAtLeast(0f)), labelStyle)
            }
            drawText(measurer, Format.clock(from), Offset(left, h + 2f), labelStyle)
            drawText(measurer, Format.clock(to), Offset(size.width - 34f, h + 2f), labelStyle)
            lines.forEachIndexed { i, line ->
                // A gap longer than three steps breaks the line instead of drawing across it.
                for (segment in ChartMath.segments(line.points, maxOf(line.step * 3, 6_000))) {
                    if (segment.size == 1) {
                        val p = segment[0]
                        drawCircle(Palette.series(i), 2.5f, Offset(left + ChartMath.x(p.t, from, to, w), ChartMath.y(p.avg, scale, h)))
                        continue
                    }
                    val path = Path()
                    segment.forEachIndexed { j, p ->
                        val x = left + ChartMath.x(p.t, from, to, w)
                        val y = ChartMath.y(p.avg, scale, h)
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Palette.series(i), style = Stroke(width = 1.8f))
                }
            }
            hoverX?.let { x -> if (x >= left) drawLine(labelColor, Offset(x, 0f), Offset(x, h)) }
        }
    }
}

/** Tiny line without axes for tiles and table rows. */
@Composable
fun Sparkline(values: List<Double>, fixedMax: Double? = null, modifier: Modifier = Modifier.width(120.dp).height(28.dp)) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = fixedMax ?: (values.max().takeIf { it > 0 } ?: 1.0)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - (v / max).toFloat().coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.5f))
    }
}

object Palette {
    val ok = Color(0xFF2E7D32)
    val warning = Color(0xFFB26A00)
    val warningContainer = Color(0xFFFFE9C2)
    val onWarningContainer = Color(0xFF4A2C00)
    private val series = listOf(Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00897B))
    fun series(i: Int) = series[i % series.size]
}
