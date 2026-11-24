package app.eghue.ui.full

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.GroupKind
import app.eghue.ui.components.ConfirmDialog
import app.eghue.ui.components.SectionTitle
import app.eghue.ui.components.groupIcon
import kotlinx.coroutines.launch

/** Zone kinds offered when making one (Hue archetypes), shown with their icons. */
private val zoneTypes = listOf(
    "other" to "Zone", "living_room" to "Living", "dining" to "Dining", "kitchen" to "Kitchen",
    "bedroom" to "Bedroom", "office" to "Work", "tv" to "TV", "reading" to "Reading",
    "hallway" to "Hallway", "garden" to "Outside", "downstairs" to "Floor",
)

/** Creates or edits a zone: any lights, across rooms. [zoneId] null makes a new one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ZoneEditorScreen(c: AppContainer, zoneId: String?, onDone: () -> Unit) {
    val repo = c.repository
    val home by repo.home.collectAsStateWithLifecycle()
    val zone = zoneId?.let { home.group(it) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var name by rememberSaveable { mutableStateOf(zone?.name ?: "") }
    var type by rememberSaveable { mutableStateOf(zone?.archetype ?: "other") }
    var picked by rememberSaveable { mutableStateOf(zone?.lightIds?.toSet() ?: emptySet()) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
                title = { Text(if (zone != null) "Edit zone" else "New zone") },
                actions = {
                    if (zone != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete zone") }
                    Button(
                        enabled = name.isNotBlank() && picked.isNotEmpty() && !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = if (zone != null) repo.updateZone(zone.id, name.trim(), type, picked)
                                else repo.createZone(name.trim(), type, picked) != null
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 32) name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Downstairs, Reading corner") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "A zone groups any lights, even from different rooms, so you can switch, dim and set scenes for them together.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle("Icon")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                zoneTypes.forEach { (key, label) ->
                    FilterChip(
                        selected = type == key,
                        onClick = { type = key },
                        label = { Text(label) },
                        leadingIcon = { Icon(groupIcon(key, GroupKind.ZONE), null, Modifier.size(18.dp)) },
                    )
                }
            }
            SectionTitle("Lights · ${picked.size}")
            // Lights grouped by room, then any not in a room.
            val inRooms = home.rooms.flatMap { it.lightIds }.toSet()
            val sections = home.rooms.map { it.name to it.lightIds } + ("Not in a room" to home.lights.map { it.id }.filter { it !in inRooms })
            sections.filter { it.second.isNotEmpty() }.forEach { (room, ids) ->
                Text(room, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                ids.mapNotNull(home::light).forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (l.id in picked) picked - l.id else picked + l.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = l.id in picked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (confirmDelete && zone != null) {
        ConfirmDialog(
            title = "Delete “${zone.name}”?",
            text = "The zone and its scenes are removed from your bridge. The lights themselves stay as they are.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; scope.launch { if (repo.deleteZone(zone.id)) onDone() } },
        )
    }
}
