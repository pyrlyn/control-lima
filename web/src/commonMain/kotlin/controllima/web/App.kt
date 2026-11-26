package controllima.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import controllima.shared.ApiException
import controllima.shared.Theme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class NavGroup(val title: String) { Inventory("Inventory"), Observe("Observe"), Manage("Manage") }

/** Every page of the app. Each has docs/pages/<doc>.md; keep them in sync (AGENTS.md). */
enum class Page(val title: String, val doc: String, val key: Key, val group: NavGroup, val nav: String = title) {
    Overview("Overview", "overview", Key.O, NavGroup.Inventory),
    Vms("VMs", "vms", Key.V, NavGroup.Inventory),
    Containers("Containers", "containers", Key.C, NavGroup.Inventory),
    Images("Images", "images", Key.I, NavGroup.Inventory),
    Kubernetes("Kubernetes", "kubernetes", Key.K, NavGroup.Inventory),
    Ports("Ports & Network", "ports-network", Key.P, NavGroup.Inventory, "Ports"),
    Metrics("Metrics", "metrics", Key.M, NavGroup.Observe),
    Alerts("Alerts", "alerts", Key.A, NavGroup.Observe),
    Logs("Logs", "logs", Key.L, NavGroup.Observe),
    Templates("Templates", "templates", Key.T, NavGroup.Manage),
    Backups("Backups", "backups", Key.B, NavGroup.Manage),
    Settings("Settings & Access", "settings-access", Key.S, NavGroup.Manage, "Settings"),
}

@Composable
fun App(m: AppModel) {
    val dark = when (m.theme) {
        Theme.DARK -> true
        Theme.LIGHT -> false
        Theme.SYSTEM -> isSystemInDarkTheme()
    }
    LimaTheme(dark) {
        Surface(Modifier.fillMaxSize()) {
            when (m.authProblem) {
                AppModel.AuthProblem.MISSING_TOKEN -> Centered { MissingToken(m) }
                AppModel.AuthProblem.LOGIN_REQUIRED -> Centered { Login(m) }
                null -> if (m.session == null) Centered { Text(Strings.LOADING) } else Shell(m)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 480.dp)) { Column(Modifier.padding(24.dp)) { content() } }
    }
}

@Composable
private fun MissingToken(m: AppModel) {
    Text(Strings.MISSING_TOKEN_TITLE, style = MaterialTheme.typography.titleLarge)
    Text(Strings.MISSING_TOKEN_TEXT, Modifier.padding(top = 12.dp))
    TextButton(onClick = { m.logout() }, Modifier.padding(top = 12.dp)) { Text(Strings.SIGN_IN + " with a username") }
}

@Composable
private fun Login(m: AppModel) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text(Strings.LOGIN_TITLE, style = MaterialTheme.typography.titleLarge)
    Text("Multi-user mode is on. Without it, open the link printed by control-lima.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(user, { user = it }, label = { Text(Strings.USERNAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pass, { pass = it }, label = { Text(Strings.PASSWORD) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    Button(
        onClick = {
            busy = true
            scope.launch {
                try {
                    m.login(user, pass)
                } catch (e: ApiException) {
                    error = if (e.status == 429) "Too many attempts. Wait a minute and try again." else e.message
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy && user.isNotEmpty() && pass.isNotEmpty(),
        modifier = Modifier.padding(top = 16.dp),
    ) { Text(Strings.SIGN_IN) }
}

@Composable
private fun Shell(m: AppModel) {
    // "g" then a page key, as in docs/plan/i18n-accessibility.md; "/" focuses the page's search.
    var pendingG by remember { mutableStateOf(false) }
    Scaffold(
        // After the focused element, not before: a text field consumes the keys typed into it, so shortcuts
        // only see keys nobody else wanted.
        modifier = Modifier.onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@onKeyEvent false
            when {
                pendingG -> {
                    pendingG = false
                    Page.entries.firstOrNull { it.key == e.key }?.let { m.navigate(Screen.Main(it)); true } ?: false
                }
                e.key == Key.G -> {
                    pendingG = true
                    true
                }
                e.key == Key.Slash -> {
                    m.searchFocus?.let { runCatching { it.requestFocus() }; true } ?: false
                }
                else -> false
            }
        },
        snackbarHost = { SnackbarHost(m.snackbar) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Sidebar(m)
            VerticalDivider(color = Lima.colors.hairline)
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp).verticalScroll(rememberScrollState())) {
                if (m.versionMismatch) Banner(Strings.VERSION_MISMATCH, BannerKind.INFO, Strings.RELOAD to { reloadApp() })
                if (!m.connected) Banner(Strings.DISCONNECTED, BannerKind.WARNING)
                when (val s = m.screen) {
                    is Screen.VmDetail -> VmDetailPage(m, s.ref)
                    is Screen.ContainerDetail -> ContainerDetailPage(m, s.profile, s.id)
                    is Screen.Logs -> LogsPage(m, s.source)
                    is Screen.Main -> when (s.page) {
                        Page.Overview -> OverviewPage(m)
                        Page.Vms -> VmsPage(m)
                        Page.Containers -> ContainersPage(m)
                        Page.Images -> ImagesPage(m)
                        Page.Kubernetes -> KubernetesPage(m)
                        Page.Ports -> PortsPage(m)
                        Page.Metrics -> MetricsPage(m)
                        Page.Alerts -> AlertsPage(m)
                        Page.Logs -> LogsPage(m, null)
                        Page.Templates -> TemplatesPage(m)
                        Page.Backups -> BackupsPage(m)
                        Page.Settings -> SettingsPage(m)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(m: AppModel) {
    val c = Lima.colors
    val current = (m.screen as? Screen.Main)?.page ?: when (m.screen) {
        is Screen.VmDetail -> Page.Vms
        is Screen.ContainerDetail -> Page.Containers
        is Screen.Logs -> Page.Logs
        else -> null
    }
    Column(Modifier.width(220.dp).fillMaxHeight().background(c.sidebar).padding(horizontal = 8.dp, vertical = 14.dp)) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(Strings.APP, style = MaterialTheme.typography.titleSmall)
            Text(serverHost(), style = MaterialTheme.typography.labelSmall, color = c.textGhost)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            NavGroup.entries.forEach { g ->
                Text(
                    g.title.uppercase(),
                    Modifier.padding(start = 8.dp, top = 18.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textGhost,
                )
                Page.entries.filter { it.group == g }.forEach { p ->
                    val badge = if (p == Page.Alerts) m.firing.size else 0
                    NavItem(p, p == current, badge) { m.navigate(if (p == Page.Logs) Screen.Logs(null) else Screen.Main(p)) }
                }
            }
        }
        m.session?.let { s ->
            Text("${s.actor} · ${s.role.name.lowercase()}", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            if (s.multiUser) TextButton(onClick = { m.logout() }) { Text(Strings.SIGN_OUT) }
        }
        Text(Strings.SHORTCUT_HINT, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = c.textGhost)
    }
}

@Composable
private fun NavItem(p: Page, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val c = Lima.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(Lima.radiusMd))
            .background(if (selected) c.selection else Color.Transparent)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(p, if (selected) c.accentInk else c.textSecondary)
        Text(
            p.nav + if (badge > 0) " ($badge)" else "",
            Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else null,
        )
    }
}

/** Line icons drawn on a Canvas, like the status glyphs, so no icon font or library ships with the app. */
@Composable
private fun NavIcon(p: Page, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun at(x: Float, y: Float) = Offset(w * x, w * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, at(x1, y1), at(x2, y2), stroke.width, StrokeCap.Round)
        fun box(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawRoundRect(color, at(x1, y1), Size(w * (x2 - x1), w * (y2 - y1)), CornerRadius(w * 0.12f), style = stroke)
        fun poly(vararg pts: Float, closed: Boolean = true) = drawPath(
            Path().apply {
                moveTo(w * pts[0], w * pts[1])
                for (i in 2 until pts.size step 2) lineTo(w * pts[i], w * pts[i + 1])
                if (closed) close()
            },
            color, style = stroke,
        )
        when (p) {
            Page.Overview -> { box(0.06f, 0.12f, 0.94f, 0.88f); line(0.06f, 0.62f, 0.94f, 0.62f) }
            Page.Vms -> { box(0.08f, 0.1f, 0.92f, 0.45f); box(0.08f, 0.55f, 0.92f, 0.9f) }
            Page.Containers -> { line(0.5f, 0.08f, 0.5f, 0.64f); poly(0.26f, 0.42f, 0.5f, 0.66f, 0.74f, 0.42f, closed = false); line(0.12f, 0.9f, 0.88f, 0.9f) }
            Page.Images -> poly(0.2f, 0.06f, 0.58f, 0.06f, 0.82f, 0.3f, 0.82f, 0.94f, 0.2f, 0.94f)
            Page.Kubernetes -> {
                drawCircle(color, w * 0.12f, at(0.22f, 0.22f), style = stroke)
                drawCircle(color, w * 0.12f, at(0.78f, 0.22f), style = stroke)
                drawCircle(color, w * 0.12f, at(0.22f, 0.8f), style = stroke)
                line(0.22f, 0.34f, 0.22f, 0.68f)
                line(0.72f, 0.33f, 0.32f, 0.72f)
            }
            Page.Ports -> { box(0.06f, 0.18f, 0.94f, 0.82f); line(0.42f, 0.18f, 0.42f, 0.82f) }
            Page.Metrics -> for (y in listOf(0.22f, 0.5f, 0.78f)) { line(0.08f, y, 0.18f, y); line(0.36f, y, 0.92f, y) }
            Page.Alerts -> { poly(0.5f, 0.08f, 0.94f, 0.9f, 0.06f, 0.9f); line(0.5f, 0.4f, 0.5f, 0.6f); line(0.5f, 0.74f, 0.5f, 0.75f) }
            Page.Logs -> { poly(0.1f, 0.22f, 0.38f, 0.5f, 0.1f, 0.78f, closed = false); line(0.5f, 0.82f, 0.9f, 0.82f) }
            Page.Templates -> { line(0.5f, 0.1f, 0.5f, 0.9f); line(0.1f, 0.5f, 0.9f, 0.5f) }
            Page.Backups -> poly(0.06f, 0.18f, 0.38f, 0.18f, 0.48f, 0.32f, 0.94f, 0.32f, 0.94f, 0.86f, 0.06f, 0.86f)
            Page.Settings -> {
                drawCircle(color, w * 0.17f, center, style = stroke)
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4
                    drawLine(color, center + Offset(cos(a), sin(a)) * (w * 0.31f), center + Offset(cos(a), sin(a)) * (w * 0.46f), stroke.width, StrokeCap.Round)
                }
            }
        }
    }
}
