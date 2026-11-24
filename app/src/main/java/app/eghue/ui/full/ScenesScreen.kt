package app.eghue.ui.full

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.GroupUi
import app.eghue.hue.Preset
import app.eghue.hue.SceneUi
import app.eghue.ui.components.SceneGrid
import app.eghue.ui.components.groupIcon
import app.eghue.ui.detail.SceneActions

/** Every room's and zone's scenes in one place, with presets, editing and new scenes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenesScreen(c: AppContainer, onOpenSettings: () -> Unit, onEdit: (String) -> Unit, onNew: (String) -> Unit) {
    val repo = c.repository
    val home by repo.home.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var menu by remember { mutableStateOf<Pair<SceneUi, GroupUi>?>(null) }
    val groups = (home.rooms + home.zones).filter { it.lightIds.isNotEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scenes", style = MaterialTheme.typography.headlineSmall) },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(groups, key = { it.id }) { g ->
                val lights = g.lightIds.mapNotNull(home::light)
                val all = home.scenesFor(g.id)
                val presets = Preset.entries.map { p ->
                    SceneUi(p.name, p.title, g.id, p.previewColors(lights), active = all.any { it.preset == p && it.active })
                }
                val mine = all.filter { it.preset == null && !it.retired }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(groupIcon(g.archetype, g.kind), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(g.name, style = MaterialTheme.typography.titleMedium)
                    }
                    SceneGrid(presets, onRecall = { repo.applyPreset(g.id, Preset.valueOf(it)) })
                    SceneGrid(
                        mine,
                        onRecall = repo::recallScene,
                        onLongPress = { menu = it to g },
                        addLabel = "New scene",
                        onAdd = { onNew(g.id) },
                    )
                }
            }
            if (groups.isEmpty()) item {
                Text("No rooms or zones with lights yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
            }
        }
    }
    menu?.let { (s, g) -> SceneActions(s, g, repo, onDismiss = { menu = null }, onEdit = onEdit) }
}
