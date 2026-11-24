package app.eghue.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.eghue.hue.ColorUtil
import app.eghue.hue.LightUi
import app.eghue.hue.PowerOn
import app.eghue.hue.Xy
import app.eghue.ui.components.BrightnessBar
import app.eghue.ui.components.ColorWheel
import app.eghue.ui.components.TemperatureBar
import kotlin.math.roundToInt

/** One-line summary of a power-on behaviour, or of several lights' behaviours. */
fun describePowerOn(lights: List<LightUi>): String? {
    val all = lights.mapNotNull { it.powerOn }
    if (all.isEmpty()) return null
    val distinct = all.distinct()
    if (distinct.size > 1) return "Different per light"
    val p = distinct.single()
    return when (p.preset) {
        PowerOn.SAFETY -> "Hue default"
        PowerOn.POWERFAIL -> "Power loss recovery"
        PowerOn.LAST_ON_STATE -> "Last colour"
        PowerOn.CUSTOM -> buildList {
            add("Custom")
            p.brightness?.let { add("${it.roundToInt()}%") }
            p.mirek?.let { add("${(1_000_000 / it / 50) * 50} K") }
            if (p.xy != null) add("colour")
        }.joinToString(" · ")
        else -> p.preset
    }
}

/** Row shown in the detail sheet; opens [PowerOnDialog]. */
@Composable
fun PowerOnRow(lights: List<LightUi>, onClick: () -> Unit) {
    val summary = describePowerOn(lights) ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (lights.size > 1) "Power-on behaviour (all lights)" else "Power-on behaviour", style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private data class PresetOption(val preset: String, val title: String, val detail: String)

private val presets = listOf(
    PresetOption(PowerOn.SAFETY, "Hue default", "Warm white at full brightness"),
    PresetOption(PowerOn.POWERFAIL, "Power loss recovery", "Back to how it was, on or off, before the power went"),
    PresetOption(PowerOn.LAST_ON_STATE, "Last colour", "On, in the colour and brightness it last had"),
    PresetOption(PowerOn.CUSTOM, "Custom", "On, in a colour and brightness you choose"),
)

/**
 * Edits the power-on behaviour of [lights]: what they do when they get power (wall switch, power cut).
 * egHue's "switch on to power-on settings" option also uses it when lights are switched on in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerOnDialog(title: String, lights: List<LightUi>, onDismiss: () -> Unit, onSave: (PowerOn) -> Unit) {
    val configurable = lights.filter { it.powerOn != null }
    val current = configurable.mapNotNull { it.powerOn }.distinct().singleOrNull()
    val customSeed = configurable.firstNotNullOfOrNull { l -> l.powerOn?.takeIf { it.preset == PowerOn.CUSTOM } }
    val lead = configurable.firstOrNull() ?: return
    val dimmable = configurable.any { it.dimmable }
    val supportsCt = configurable.any { it.supportsCt }
    val supportsColor = configurable.any { it.supportsColor }

    var preset by remember { mutableStateOf(current?.preset) }
    var brightness by remember { mutableStateOf(customSeed?.brightness ?: lead.brightness.takeIf { lead.on } ?: 100f) }
    var mirek by remember { mutableStateOf(customSeed?.mirek ?: lead.mirek ?: 366) }
    var xy by remember { mutableStateOf(customSeed?.xy) }
    var colorTab by remember { mutableStateOf(if (customSeed?.xy != null) 1 else 0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(20.dp).widthIn(max = 520.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Power-on behaviour", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "What $title does when it gets power, for example from a wall switch. " +
                        "egHue also uses it when you switch lights on (Settings › Lights).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                presets.forEach { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .selectable(selected = preset == o.preset, role = Role.RadioButton) { preset = o.preset }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preset == o.preset, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(o.title, style = MaterialTheme.typography.bodyLarge)
                            Text(o.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (preset == PowerOn.CUSTOM) {
                    val preview = when {
                        colorTab == 1 && xy != null -> Color(ColorUtil.xyToRgb(xy!!))
                        supportsCt -> Color(ColorUtil.mirekToRgb(mirek))
                        else -> Color(ColorUtil.WARM_WHITE)
                    }
                    if (dimmable) BrightnessBar(brightness, on = true, color = preview, enabled = true, onChange = { brightness = it })
                    if (supportsColor && supportsCt) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("White", "Colour").forEachIndexed { i, label ->
                                SegmentedButton(
                                    selected = colorTab == i,
                                    onClick = { colorTab = i; if (i == 1 && xy == null) xy = Xy(0.5, 0.41) },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(label) }
                            }
                        }
                    }
                    if (supportsColor && (colorTab == 1 || !supportsCt)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ColorWheel(
                                current = xy?.let { Color(ColorUtil.xyToRgb(it)) },
                                onPick = { xy = ColorUtil.rgbToXy(it.toArgb()) },
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        }
                    } else if (supportsCt) {
                        TemperatureBar(mirek, lead.mirekMin, lead.mirekMax, onChange = { mirek = it })
                    }
                    TextButton(onClick = {
                        brightness = lead.brightness
                        if (lead.ctActive) { mirek = lead.mirek ?: mirek; colorTab = 0 } else { xy = lead.xy ?: xy; colorTab = 1 }
                    }, enabled = lead.on) { Text("Use the light's current look") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(preview))
                        Spacer(Modifier.size(8.dp))
                        Text("Comes on like this", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = preset != null,
                        onClick = {
                            val p = preset ?: return@TextButton
                            onSave(
                                if (p != PowerOn.CUSTOM) PowerOn(p)
                                else PowerOn(
                                    PowerOn.CUSTOM,
                                    brightness = if (dimmable) brightness else null,
                                    mirek = if (supportsCt && (colorTab == 0 || !supportsColor)) mirek else null,
                                    xy = if (supportsColor && (colorTab == 1 || !supportsCt)) xy else null,
                                )
                            )
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}
