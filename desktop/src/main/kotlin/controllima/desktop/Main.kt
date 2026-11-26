package controllima.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import controllima.server.ControlLimaServer
import controllima.server.DataPaths
import controllima.server.ServerConfig
import controllima.server.security.Secrets
import controllima.shared.ApiClient
import controllima.shared.Format
import controllima.shared.VmStatus
import controllima.shared.VmTable
import controllima.web.App
import controllima.web.AppModel
import controllima.web.DesktopHost
import controllima.web.JvmTransport
import controllima.web.Page
import controllima.web.Screen
import java.net.InetAddress
import java.net.ServerSocket

fun main() {
    // Same launch token rules as `control-lima serve`; it never leaves this process.
    val token = Secrets.randomHex(24)
    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    val server = ControlLimaServer(ServerConfig(port = port, token = token), DataPaths.default())
    server.start(wait = false)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    DesktopHost.baseUrl = "http://127.0.0.1:$port"
    val api = ApiClient(JvmTransport(), token)

    application {
        val scope = rememberCoroutineScope()
        val model = remember { AppModel(api, scope).also { it.start() } }
        var visible by remember { mutableStateOf(true) }
        val show = { visible = true }
        Tray(
            icon = remember(model.vms, model.firing) { StatusIcon(trayColor(model)) },
            tooltip = "ControlLima",
            onAction = show,
            menu = { TrayMenu(model, show, ::exitApplication) },
        )
        // Closing the window keeps the app in the menu bar; Quit is in the menu.
        Window(onCloseRequest = { visible = false }, visible = visible, title = "ControlLima", state = rememberWindowState(width = 1280.dp, height = 820.dp)) {
            LaunchedEffect(Unit) { DesktopHost.frame = window }
            App(model)
        }
    }
}

private fun trayColor(m: AppModel): Color = when {
    m.firing.isNotEmpty() -> Color(0xFFE5484D)
    m.vms?.vms.orEmpty().any { it.status == VmStatus.RUNNING } -> Color(0xFF30A46C)
    else -> Color(0xFF8B8D98)
}

/**
 * The menu bar item of docs/plan/desktop-app.md: VMs with their status, start and stop, the alert count
 * and the dashboard. It only acts on a click, so it never starts a VM on its own.
 */
@Composable
private fun MenuScope.TrayMenu(m: AppModel, show: () -> Unit, quit: () -> Unit) {
    Item("Open dashboard", onClick = show)
    Separator()
    val vms = m.vms?.vms
    if (vms == null) Item("Loading VMs…", enabled = false, onClick = {})
    else if (vms.isEmpty()) Item("No VMs", enabled = false, onClick = {})
    vms.orEmpty().forEach { vm ->
        Menu("${dot(vm.status)}  ${vm.name} (${vm.source.slug}) · ${Format.status(vm.status)}") {
            if (VmTable.canStart(vm)) Item("Start") { m.runAction("Start ${vm.name}", { m.api.startVm(vm.ref).actionId }) }
            if (VmTable.canStop(vm)) Item("Stop") { m.runAction("Stop ${vm.name}", { m.api.stopVm(vm.ref).actionId }) }
            Item("Details…") {
                m.navigate(Screen.VmDetail(vm.ref))
                show()
            }
        }
    }
    Separator()
    Item(if (m.firing.isEmpty()) "No alerts firing" else "Alerts firing: ${m.firing.size}") {
        m.navigate(Screen.Main(Page.Alerts))
        show()
    }
    Separator()
    Item("Quit ControlLima", onClick = quit)
}

private fun dot(status: VmStatus) = when (status) {
    VmStatus.RUNNING -> "●"
    VmStatus.STOPPED -> "○"
    else -> "▲"
}

/** A plain dot: green with a VM running, grey with none, red while an alert fires. */
private class StatusIcon(private val color: Color) : Painter() {
    override val intrinsicSize = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        val r = size.minDimension * 0.3f
        drawCircle(color, r, Offset(size.width / 2, size.height / 2))
        drawCircle(Color.White.copy(alpha = 0.6f), r, Offset(size.width / 2, size.height / 2), style = Stroke(size.minDimension * 0.06f))
    }
}
