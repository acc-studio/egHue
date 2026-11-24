package app.eghue.ui.full

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.eghue.AppContainer
import app.eghue.ui.home.HomeScreen

/** A tab of the full view. New tabs are added here as features land. */
enum class FullTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    SCENES("Scenes", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    AUTOMATIONS("Automations", Icons.Outlined.Schedule, Icons.Rounded.Schedule),
    DEVICES("Devices", Icons.Outlined.Devices, Icons.Rounded.Devices),
}

/** The full view: the Lite home screen plus tabs for everything else. */
@Composable
fun FullApp(c: AppContainer, onOpenSettings: () -> Unit, onOpenRemote: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(FullTab.HOME) }
    // "edit:<groupId>:<sceneId or 'new'>" (scene editor) or "auto:<id or 'new'>" (automation editor).
    var editor by rememberSaveable { mutableStateOf<String?>(null) }

    editor?.takeIf { it.startsWith("zone:") }?.let { route ->
        BackHandler { editor = null }
        ZoneEditorScreen(c, route.removePrefix("zone:").takeIf { it != "new" }, onDone = { editor = null })
        return
    }
    editor?.takeIf { it.startsWith("auto:") }?.let { route ->
        BackHandler { editor = null }
        AutomationEditorScreen(c, route.removePrefix("auto:").takeIf { it != "new" }, onDone = { editor = null })
        return
    }
    editor?.let { route ->
        val (groupId, sceneId) = route.removePrefix("edit:").split(':', limit = 2).let { it[0] to it[1].takeIf { s -> s != "new" } }
        BackHandler { editor = null }
        SceneEditorScreen(c, groupId, sceneId, onDone = { editor = null })
        return
    }
    val openEditor: (String, String?) -> Unit = { g, s -> editor = "edit:$g:${s ?: "new"}" }
    val editScene: (String) -> Unit = { sceneId ->
        c.repository.home.value.scenes.values.flatten().firstOrNull { it.id == sceneId }?.let { openEditor(it.groupId, it.id) }
    }

    if (tab != FullTab.HOME) BackHandler { tab = FullTab.HOME }
    Scaffold(
        // Only the navigation bar inset belongs to this shell; each tab handles the status bar itself.
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            NavigationBar {
                FullTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(pad)) {
            when (tab) {
                FullTab.HOME -> HomeScreen(c, onOpenSettings = onOpenSettings, onEditScene = editScene)
                FullTab.SCENES -> ScenesScreen(c, onOpenSettings = onOpenSettings, onEdit = editScene, onNew = { openEditor(it, null) })
                FullTab.DEVICES -> DevicesScreen(c, onOpenSettings = onOpenSettings, onOpenRemote = onOpenRemote, onEditZone = { editor = "zone:${it ?: "new"}" })
                FullTab.AUTOMATIONS -> AutomationsScreen(c, onOpenSettings = onOpenSettings, onEdit = { editor = "auto:${it ?: "new"}" })
            }
        }
    }
}
