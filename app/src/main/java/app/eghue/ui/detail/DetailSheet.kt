package app.eghue.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.eghue.hue.ColorUtil
import app.eghue.hue.GroupKind
import app.eghue.hue.GroupUi
import app.eghue.hue.HomeUi
import app.eghue.hue.HueRepository
import app.eghue.hue.LightUi
import app.eghue.hue.Preset
import app.eghue.hue.SceneCapture
import app.eghue.hue.SceneUi
import app.eghue.hue.Xy
import app.eghue.ui.components.BrightnessBar
import app.eghue.ui.components.SceneGrid
import app.eghue.ui.components.ColorWheel
import app.eghue.ui.components.NameDialog
import app.eghue.ui.components.SectionTitle
import app.eghue.ui.components.TemperatureBar
import app.eghue.ui.components.groupIcon
import app.eghue.ui.components.lightIcon
import app.eghue.ui.home.LightTile
import app.eghue.ui.home.groupStatus
import app.eghue.ui.home.lightStatus
import kotlinx.coroutines.launch

/**
 * Bottom sheet with full controls. [target] is "g:<room/zone id>" or "l:<light id>", optionally
 * followed by "|g:<id>" naming the group to go back to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    target: String,
    home: HomeUi,
    repo: HueRepository,
    onNavigate: (String?) -> Unit,
    /** Full view: opens the scene editor. */
    onEditScene: ((String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = target.substringBefore('|')
    val back = target.substringAfter('|', "").ifEmpty { null }
    val id = current.substring(2)
    val group = if (current.startsWith("g:")) home.group(id) else null
    val light = if (current.startsWith("l:")) home.light(id) else null

    // The resource vanished (deleted in the Hue app): close.
    LaunchedEffect(group == null && light == null, home.isEmpty) {
        if (group == null && light == null && !home.isEmpty) onNavigate(null)
    }

    ModalBottomSheet(
        onDismissRequest = { onNavigate(null) },
        sheetState = sheetState,
        // Stop below the status bar so its icons always sit on the app's own background.
        modifier = Modifier.statusBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                group != null -> GroupDetail(
                    group, home, repo,
                    onOpenLight = { onNavigate("l:$it|g:${group.id}") },
                    onEditScene = onEditScene?.let { f -> { id: String -> onNavigate(null); f(id) } },
                )
                light != null -> LightDetail(
                    light, repo,
                    backLabel = back?.let { home.group(it.substring(2))?.name },
                    onBack = { onNavigate(back) },
                )
            }
        }
    }
}

@Composable
private fun GroupDetail(g: GroupUi, home: HomeUi, repo: HueRepository, onOpenLight: (String) -> Unit, onEditScene: ((String) -> Unit)?) {
    Header(
        icon = groupIcon(g.archetype, g.kind),
        title = g.name,
        subtitle = "${if (g.kind == GroupKind.ROOM) "Room" else "Zone"} · ${groupStatus(g)}",
        on = g.on,
        enabled = g.groupedLightId != null && g.lightIds.isNotEmpty(),
        onToggle = { repo.setGroupOn(g.id, it) },
    )
    if (g.dimmable) {
        BrightnessBar(g.brightness, g.on, Color(g.color), enabled = g.groupedLightId != null, onChange = { repo.setGroupBrightness(g.id, it) })
    }
    ColorSection(
        supportsColor = g.supportsColor,
        supportsCt = g.supportsCt,
        ctActive = g.ctActive,
        color = if (g.on) Color(g.color) else null,
        mirek = g.mirek,
        mirekMin = g.mirekMin,
        mirekMax = g.mirekMax,
        onXy = { repo.setGroupXy(g.id, it) },
        onMirek = { repo.setGroupMirek(g.id, it) },
    )
    val lights = g.lightIds.mapNotNull { home.light(it) }
    var editPowerOn by remember { mutableStateOf(false) }
    PowerOnRow(lights, onClick = { editPowerOn = true })
    if (editPowerOn) {
        PowerOnDialog(g.name, lights, onDismiss = { editPowerOn = false }, onSave = {
            repo.setPowerOn(g.lightIds, it); editPowerOn = false
        })
    }
    g.idV1?.let { v1 -> if (lights.isNotEmpty()) SleepTimerRow(v1, repo) }
    val allScenes = home.scenesFor(g.id)
    if (lights.isNotEmpty()) {
        SectionTitle("Presets")
        val presets = Preset.entries.map { p ->
            SceneUi(
                id = p.name,
                name = p.title,
                groupId = g.id,
                colors = p.previewColors(lights),
                active = allScenes.any { it.preset == p && it.active },
            )
        }
        SceneGrid(presets, onRecall = { key -> repo.applyPreset(g.id, Preset.valueOf(key)) })
    }
    // egHue's saved presets already show above.
    val scenes = allScenes.filter { it.preset == null && !it.retired }
    var sceneMenu by remember { mutableStateOf<SceneUi?>(null) }
    var naming by remember { mutableStateOf(false) }
    if (lights.isNotEmpty() || scenes.isNotEmpty()) {
        SectionTitle("Scenes")
        SceneGrid(
            scenes,
            onRecall = repo::recallScene,
            onLongPress = { sceneMenu = it },
            addLabel = "Save current look",
            onAdd = if (lights.isNotEmpty() && g.groupedLightId != null) ({ naming = true }) else null,
        )
    }
    if (naming) {
        val scope = rememberCoroutineScope()
        NameDialog(
            title = "Save as a scene",
            message = "Saves how ${g.name}'s lights look right now.",
            initial = SceneCapture.uniqueName("My scene", allScenes.map { it.name }),
            confirmLabel = "Save",
            onDismiss = { naming = false },
            onConfirm = { name -> naming = false; scope.launch { repo.saveCurrentAsScene(g.id, name) } },
        )
    }
    sceneMenu?.let { s -> SceneActions(s, g, repo, onDismiss = { sceneMenu = null }, onEdit = onEditScene) }
    if (lights.isNotEmpty()) {
        SectionTitle("Lights")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            lights.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { l ->
                        LightTile(l, repo, onOpen = { onOpenLight(l.id) }, modifier = Modifier.weight(1f), compact = true)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LightDetail(l: LightUi, repo: HueRepository, backLabel: String?, onBack: () -> Unit) {
    if (backLabel != null) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(backLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
    Header(
        icon = lightIcon(l.on),
        title = l.name,
        subtitle = lightStatus(l),
        on = l.on,
        enabled = true,
        onToggle = { repo.setLightOn(l.id, it) },
    )
    if (l.dimmable) {
        BrightnessBar(l.brightness, l.on, Color(l.color), enabled = true, onChange = { repo.setLightBrightness(l.id, it) })
    }
    ColorSection(
        supportsColor = l.supportsColor,
        supportsCt = l.supportsCt,
        ctActive = l.ctActive,
        color = if (l.on) Color(l.color) else null,
        mirek = l.mirek,
        mirekMin = l.mirekMin,
        mirekMax = l.mirekMax,
        onXy = { repo.setLightXy(l.id, it) },
        onMirek = { repo.setLightMirek(l.id, it) },
    )
    var editPowerOn by remember { mutableStateOf(false) }
    PowerOnRow(listOf(l), onClick = { editPowerOn = true })
    if (editPowerOn) {
        PowerOnDialog(l.name, listOf(l), onDismiss = { editPowerOn = false }, onSave = {
            repo.setPowerOn(listOf(l.id), it); editPowerOn = false
        })
    }
    if (!l.reachable) {
        Text(
            "This light isn't responding. Check that it's powered at the wall switch.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Header(icon: ImageVector, title: String, subtitle: String, on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onToggle, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSection(
    supportsColor: Boolean,
    supportsCt: Boolean,
    ctActive: Boolean,
    color: Color?,
    mirek: Int?,
    mirekMin: Int,
    mirekMax: Int,
    onXy: (Xy) -> Unit,
    onMirek: (Int) -> Unit,
) {
    if (!supportsColor && !supportsCt) return
    var tab by rememberSaveable { mutableStateOf(if (supportsColor && !ctActive) 1 else 0) }
    if (supportsColor && supportsCt) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("White", "Colour").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
    }
    val showColor = supportsColor && (!supportsCt || tab == 1)
    if (showColor) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheel(
                current = if (!ctActive) color else null,
                onPick = { onXy(ColorUtil.rgbToXy(it.toArgb())) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    } else {
        TemperatureBar(mirek = if (ctActive) mirek else null, mirekMin = mirekMin, mirekMax = mirekMax, onChange = onMirek)
        WhitePresets(mirekMin, mirekMax, onMirek)
    }
}

private val whitePresets = listOf("Warm" to 454, "Soft" to 370, "Bright" to 250, "Daylight" to 182)

@Composable
private fun WhitePresets(mirekMin: Int, mirekMax: Int, onMirek: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        whitePresets.forEach { (label, m) ->
            val mk = m.coerceIn(mirekMin, mirekMax)
            val c = Color(ColorUtil.mirekToRgb(mk))
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(role = Role.Button) { onMirek(mk) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(c))
                Spacer(Modifier.height(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
