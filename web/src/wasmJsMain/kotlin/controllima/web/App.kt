package controllima.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Every page of the app. Each has docs/pages/<doc>.md; keep them in sync (AGENTS.md). */
enum class Page(val title: String, val doc: String) {
    Overview("Overview", "overview"),
    Vms("VMs", "vms"),
    Containers("Containers", "containers"),
    Images("Images", "images"),
    Ports("Ports & Network", "ports-network"),
    Metrics("Metrics", "metrics"),
    Alerts("Alerts", "alerts"),
    Logs("Logs", "logs"),
    Templates("Templates", "templates"),
    Backups("Backups", "backups"),
    Settings("Settings & Access", "settings-access"),
}

@Composable
fun App() {
    var page by remember { mutableStateOf(Page.Overview) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp)) {
                    Text("ControlLima", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                    Page.entries.forEach { p ->
                        NavigationDrawerItem(
                            label = { Text(p.title) },
                            selected = p == page,
                            onClick = { page = p },
                        )
                    }
                }
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(page.title, style = MaterialTheme.typography.headlineMedium)
                    Text("Not implemented yet. Spec: docs/pages/${page.doc}.md", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
