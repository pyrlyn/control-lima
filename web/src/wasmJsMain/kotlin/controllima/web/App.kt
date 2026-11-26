package controllima.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import controllima.shared.ApiException
import controllima.shared.Theme
import kotlinx.browser.window
import kotlinx.coroutines.launch

/** Every page of the app. Each has docs/pages/<doc>.md; keep them in sync (AGENTS.md). */
enum class Page(val title: String, val doc: String, val key: Key) {
    Overview("Overview", "overview", Key.O),
    Vms("VMs", "vms", Key.V),
    Containers("Containers", "containers", Key.C),
    Images("Images", "images", Key.I),
    Ports("Ports & Network", "ports-network", Key.P),
    Metrics("Metrics", "metrics", Key.M),
    Alerts("Alerts", "alerts", Key.A),
    Logs("Logs", "logs", Key.L),
    Templates("Templates", "templates", Key.T),
    Backups("Backups", "backups", Key.B),
    Settings("Settings & Access", "settings-access", Key.S),
}

@Composable
fun App(m: AppModel) {
    val dark = when (m.theme) {
        Theme.DARK -> true
        Theme.LIGHT -> false
        Theme.SYSTEM -> window.matchMedia("(prefers-color-scheme: dark)").matches
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
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
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@onPreviewKeyEvent false
            if (isTyping()) return@onPreviewKeyEvent false
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
            Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp).verticalScroll(rememberScrollState())) {
                Text(Strings.APP, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                val current = (m.screen as? Screen.Main)?.page ?: when (m.screen) {
                    is Screen.VmDetail -> Page.Vms
                    is Screen.ContainerDetail -> Page.Containers
                    is Screen.Logs -> Page.Logs
                    else -> null
                }
                Page.entries.forEach { p ->
                    val badge = if (p == Page.Alerts && m.firing.isNotEmpty()) " (${m.firing.size})" else ""
                    NavigationDrawerItem(label = { Text(p.title + badge) }, selected = p == current, onClick = { m.navigate(if (p == Page.Logs) Screen.Logs(null) else Screen.Main(p)) })
                }
                Spacer(Modifier.padding(8.dp))
                m.session?.let { s ->
                    Text("${s.actor} · ${s.role.name.lowercase()}", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                    if (s.multiUser) TextButton(onClick = { m.logout() }) { Text(Strings.SIGN_OUT) }
                }
                Text(Strings.SHORTCUTS, Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
                if (m.versionMismatch) Banner(Strings.VERSION_MISMATCH, BannerKind.INFO, Strings.RELOAD to { window.location.reload() })
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

/** Shortcuts must not fire while the user types into a field. */
@JsFun("() => { const e = document.activeElement; return !!e && (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA' || e.isContentEditable) }")
private external fun isTyping(): Boolean
