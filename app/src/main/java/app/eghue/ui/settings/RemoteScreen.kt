package app.eghue.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.ButtonAction
import app.eghue.hue.ButtonKey
import app.eghue.hue.Gesture
import app.eghue.hue.HomeUi
import app.eghue.hue.RemoteSetup
import app.eghue.hue.RemoteUi
import app.eghue.hue.buttonLabel
import kotlinx.coroutines.launch

fun describeAction(a: ButtonAction, gesture: Gesture, home: HomeUi): String = when (a) {
    ButtonAction.Nothing -> "Does nothing"
    ButtonAction.Toggle -> "Toggle on / off"
    ButtonAction.On -> "Turn on"
    ButtonAction.Off -> "Turn off"
    ButtonAction.AllOff -> "All lights off"
    ButtonAction.Brighter -> if (gesture == Gesture.HOLD) "Brighten while held" else "Brighter"
    ButtonAction.Dimmer -> if (gesture == Gesture.HOLD) "Dim while held" else "Dimmer"
    is ButtonAction.Scene -> "Scene: " + (sceneName(home, a.sceneId) ?: "unknown")
    is ButtonAction.Cycle -> "Cycle: " + a.sceneIds.mapNotNull { sceneName(home, it) }.joinToString(", ")
    is ButtonAction.Keep -> "Keep current: ${a.description.replaceFirstChar { it.lowercase() }}"
}

private fun sceneName(home: HomeUi, id: String) = home.scenes.values.flatten().firstOrNull { it.id == id }?.name

private val pressOptions = listOf(
    ButtonAction.Toggle, ButtonAction.On, ButtonAction.Off, ButtonAction.Scene(""), ButtonAction.Cycle(emptyList()),
    ButtonAction.Brighter, ButtonAction.Dimmer, ButtonAction.AllOff, ButtonAction.Nothing,
)
private val holdOptions = listOf(
    ButtonAction.Brighter, ButtonAction.Dimmer, ButtonAction.Off, ButtonAction.On, ButtonAction.Scene(""),
    ButtonAction.AllOff, ButtonAction.Nothing,
)

private sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val message: String) : LoadState
    data class Ready(val original: RemoteSetup) : LoadState
}

/** Button setup for one remote: what each button does on press and on hold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(c: AppContainer, remoteId: String, onBack: () -> Unit) {
    val home by c.repository.home.collectAsStateWithLifecycle()
    val remote = home.remotes.firstOrNull { it.id == remoteId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var edited by remember { mutableStateOf<RemoteSetup?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(remoteId, reload, remote != null) {
        if (remote == null) return@LaunchedEffect
        state = LoadState.Loading
        state = runCatching { c.repository.loadRemote(remote) }
            .fold({ LoadState.Ready(it).also { r -> edited = r.original } }, { LoadState.Failed(it.message ?: "Couldn't read the remote") })
    }

    val original = (state as? LoadState.Ready)?.original
    val dirty = original != null && edited != null && edited != original

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(remote?.name ?: "Remote") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (dirty) Surface(tonalElevation = 3.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(
                        onClick = {
                            val r = remote ?: return@Button
                            val o = original ?: return@Button
                            val e = edited ?: return@Button
                            scope.launch {
                                saving = true
                                runCatching { c.repository.saveRemote(r, o, e) }
                                    .onSuccess { snackbar.showSnackbar("Saved. The remote works this way now.") ; reload++ }
                                    .onFailure { snackbar.showSnackbar("Couldn't save: ${it.message}") }
                                saving = false
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (saving) "Saving…" else "Save to bridge") }
                }
            }
        },
    ) { pad ->
        when {
            remote == null -> Message(pad, "This remote is no longer on your bridge.")
            remote.sensorV1 == null -> Message(pad, "This kind of remote can't be set up in egHue yet. Use the Hue app.")
            state is LoadState.Loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state is LoadState.Failed -> Message(pad, (state as LoadState.Failed).message)
            else -> RemoteEditor(remote, home, edited!!, pad, onChange = { edited = it })
        }
    }
}

@Composable
private fun Message(pad: PaddingValues, text: String) {
    Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteEditor(remote: RemoteUi, home: HomeUi, setup: RemoteSetup, pad: PaddingValues, onChange: (RemoteSetup) -> Unit) {
    var picking by remember { mutableStateOf<ButtonKey?>(null) }
    var pickingTarget by remember { mutableStateOf(false) }
    val target = setup.targetGroupId?.let(home::group)

    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(remote.productName ?: remote.modelId ?: "Remote", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            remote.battery?.let {
                Icon(Icons.Outlined.Battery5Bar, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" $it%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "Button actions run on your bridge, so the remote works even when your phone is off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PickerRow("Controls", target?.name ?: "Choose a room or zone", onClick = { pickingTarget = true })
        remote.buttons.forEach { b ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(buttonLabel(remote.modelId, b, remote.buttons.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    for (g in Gesture.entries) {
                        PickerRow(
                            if (g == Gesture.PRESS) "Press" else "Hold",
                            describeAction(setup.action(b, g), g, home),
                            onClick = { picking = ButtonKey(b, g) },
                        )
                    }
                }
            }
        }
        Text(
            "Hold actions other than brightness happen when you let go. Buttons marked \"keep current\" were set up " +
                "elsewhere (e.g. the Hue app) and stay exactly as they are unless you change them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(80.dp))
    }

    if (pickingTarget) {
        val groups = home.rooms + home.zones
        ChoiceDialog(
            title = "Controls",
            options = groups.map { it.name },
            selected = groups.indexOfFirst { it.id == setup.targetGroupId },
            onDismiss = { pickingTarget = false },
            onPick = { i -> onChange(setup.copy(targetGroupId = groups[i].id)); pickingTarget = false },
        )
    }

    picking?.let { key ->
        val current = setup.action(key.button, key.gesture)
        val base = if (key.gesture == Gesture.PRESS) pressOptions else holdOptions
        val options = (if (current is ButtonAction.Keep) listOf(current) else emptyList()) + base
        var sub by remember(key) { mutableStateOf<ButtonAction?>(null) }
        val scenes = setup.targetGroupId?.let(home::scenesFor).orEmpty()
        fun set(a: ButtonAction) { onChange(setup.copy(actions = setup.actions + (key to a))); picking = null }
        when (val s = sub) {
            null -> ChoiceDialog(
                title = (if (key.gesture == Gesture.PRESS) "Press: " else "Hold: ") + buttonLabel(remote.modelId, key.button, remote.buttons.size),
                options = options.map { o ->
                    when (o) {
                        is ButtonAction.Scene -> "Scene…"
                        is ButtonAction.Cycle -> "Cycle through scenes…"
                        else -> describeAction(o, key.gesture, home)
                    }
                },
                selected = options.indexOfFirst { it::class == current::class },
                onDismiss = { picking = null },
                onPick = { i ->
                    val o = options[i]
                    if (o is ButtonAction.Scene || o is ButtonAction.Cycle) sub = o else set(o)
                },
            )
            is ButtonAction.Scene -> ChoiceDialog(
                title = "Scene",
                options = scenes.map { it.name },
                selected = scenes.indexOfFirst { it.id == (current as? ButtonAction.Scene)?.sceneId },
                empty = "No scenes for ${setup.targetGroupId?.let(home::group)?.name ?: "this room"}. Choose a room first or create scenes in the Hue app.",
                onDismiss = { sub = null },
                onPick = { i -> set(ButtonAction.Scene(scenes[i].id)) },
            )
            is ButtonAction.Cycle -> MultiChoiceDialog(
                title = "Cycle through",
                options = scenes.map { it.name },
                initiallySelected = (current as? ButtonAction.Cycle)?.sceneIds?.mapNotNull { id -> scenes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }.orEmpty(),
                onDismiss = { sub = null },
                onDone = { idx -> if (idx.isNotEmpty()) set(ButtonAction.Cycle(idx.map { scenes[it].id })) else sub = null },
            )
            else -> Unit
        }
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null)
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    empty: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty() && empty != null) Text(empty)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options.indices.toList()) { i ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .selectable(selected = i == selected, role = Role.RadioButton) { onPick(i) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(options[i], style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MultiChoiceDialog(
    title: String,
    options: List<String>,
    initiallySelected: List<Int>,
    onDismiss: () -> Unit,
    onDone: (List<Int>) -> Unit,
) {
    val chosen = remember { mutableStateListOf<Int>().apply { addAll(initiallySelected) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) Text("No scenes for this room yet. Create them in the Hue app.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options.indices.toList()) { i ->
                    val on = i in chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .toggleable(value = on, role = Role.Checkbox) { if (it) chosen += i else chosen -= i }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.size(12.dp))
                        Text(options[i], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (on) Text("${chosen.indexOf(i) + 1}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
