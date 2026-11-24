package app.eghue.ui.full

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.Preset
import app.eghue.hue.SceneCapture
import app.eghue.ui.components.SceneSwatch
import app.eghue.ui.components.SectionTitle
import app.eghue.ui.detail.DetailSheet
import app.eghue.ui.home.GroupTile
import app.eghue.ui.home.LightTile
import kotlinx.coroutines.launch

/**
 * Edits a scene by setting the real lights: whatever the room looks like when you tap Save is what
 * the scene stores. [sceneId] null makes a new scene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditorScreen(c: AppContainer, groupId: String, sceneId: String?, onDone: () -> Unit) {
    val repo = c.repository
    val home by repo.home.collectAsStateWithLifecycle()
    val g = home.group(groupId)
    val existing = sceneId?.let { id -> home.scenesFor(groupId).firstOrNull { it.id == id } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var name by rememberSaveable {
        mutableStateOf(existing?.name ?: SceneCapture.uniqueName("My scene", home.scenesFor(groupId).map { it.name }))
    }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var startedFrom by rememberSaveable { mutableStateOf<String?>(null) }

    // Editing an existing scene starts by showing it on the lights.
    LaunchedEffect(sceneId) { if (sceneId != null) repo.recallScene(sceneId) }
    if (g == null) { LaunchedEffect(Unit) { onDone() }; return }
    val lights = g.lightIds.mapNotNull(home::light)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Discard changes") } },
                title = { Text(if (existing != null) "Edit scene" else "New scene") },
                actions = {
                    Button(
                        enabled = name.isNotBlank() && !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = if (existing != null) {
                                    repo.updateSceneFromCurrent(existing.id, name.trim().takeIf { it != existing.name })
                                } else repo.saveCurrentAsScene(groupId, name.trim()) != null
                                saving = false
                                if (ok) onDone()
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (saving) "Saving…" else "Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 32) name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Set ${g.name}'s lights the way you want: tap to switch, drag to dim, › for colour. " +
                    "Save stores exactly what the lights show.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle("Start from")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p ->
                    FilterChip(
                        selected = startedFrom == p.name,
                        onClick = { startedFrom = p.name; repo.previewPreset(groupId, p) },
                        label = { Text(p.title) },
                        leadingIcon = { SceneSwatch(p.previewColors(lights), size = 18.dp) },
                    )
                }
                home.scenesFor(groupId).filter { it.preset == null && !it.retired && it.id != sceneId }.forEach { s ->
                    FilterChip(
                        selected = startedFrom == s.id,
                        onClick = { startedFrom = s.id; repo.recallScene(s.id) },
                        label = { Text(s.name) },
                        leadingIcon = { SceneSwatch(s.colors, size = 18.dp) },
                    )
                }
            }
            SectionTitle("Whole room")
            GroupTile(g, repo, onOpen = { detail = "g:${g.id}" }, modifier = Modifier.fillMaxWidth())
            SectionTitle("Lights")
            lights.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { l -> LightTile(l, repo, onOpen = { detail = "l:${l.id}" }, modifier = Modifier.weight(1f), compact = true) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    detail?.let { DetailSheet(it, home, repo, onNavigate = { t -> detail = t }) }
}
