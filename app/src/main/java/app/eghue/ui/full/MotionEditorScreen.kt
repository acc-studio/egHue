package app.eghue.ui.full

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.MotionSetup
import app.eghue.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.roundToInt

private val whites = listOf("Candle" to 455, "Warm" to 370, "Neutral" to 300, "Daylight" to 220)

/** Sets what a motion sensor does. Stored as bridge rules, so it works with every phone off. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MotionEditorScreen(c: AppContainer, sensorId: String, onDone: () -> Unit) {
    val repo = c.repository
    val home by repo.home.collectAsStateWithLifecycle()
    val sensor = home.sensors.firstOrNull { it.id == sensorId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<MotionSetup?>(null) }
    var setup by remember { mutableStateOf<MotionSetup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val targets = (home.rooms + home.zones).filter { it.idV1 != null && it.lightIds.isNotEmpty() }.map { it.idV1!! to it.name }
    LaunchedEffect(sensorId) {
        val s = sensor ?: return@LaunchedEffect
        try {
            existing = repo.loadMotion(s)
            // Default target: a room whose name the sensor's name mentions, else the first room.
            val guess = targets.firstOrNull { (_, n) -> s.name.contains(n, ignoreCase = true) }?.first ?: targets.firstOrNull()?.first
            setup = existing ?: guess?.let { MotionSetup(s.presenceV1!!, s.lightLevelV1, it) }
        } catch (e: Exception) {
            error = "Couldn't read the sensor's setup from the bridge."
        }
        loaded = true
    }
    if (sensor == null || sensor.presenceV1 == null) { LaunchedEffect(Unit) { onDone() }; return }

    fun save(value: MotionSetup?) {
        saving = true
        scope.launch {
            val ok = repo.saveMotion(sensor, value)
            saving = false
            if (ok) onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
                title = { Text(sensor.name) },
                actions = {
                    Button(enabled = setup != null && !saving, onClick = { save(setup) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val m = setup
        if (!loaded || m == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                if (!loaded) CircularProgressIndicator() else Text(error ?: "Add a room with lights first.", modifier = Modifier.padding(32.dp))
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "When there's motion, the lights come on; after a while without motion they go off. " +
                    "This runs on your bridge. If the Hue app also controls this sensor, set it up in one place only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle("Lights")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { (v1, name) ->
                    FilterChip(selected = m.targetV1 == v1, onClick = { setup = m.copy(targetV1 = v1) }, label = { Text(name) })
                }
            }
            SectionTitle("Brightness · ${m.brightness}%")
            Slider(value = m.brightness.toFloat(), onValueChange = { setup = m.copy(brightness = it.roundToInt()) }, valueRange = 1f..100f)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                whites.forEach { (label, mk) -> FilterChip(selected = m.mirek == mk, onClick = { setup = m.copy(mirek = mk) }, label = { Text(label) }) }
            }
            SectionTitle("At night (${"%02d:%02d".format(m.nightStart.hour, m.nightStart.minute)}–${"%02d:%02d".format(m.nightEnd.hour, m.nightEnd.minute)})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Pair<Int?, String>>(null to "Same as day", 5 to "Night light 5%", 20 to "Dim 20%", 0 to "Stay off").forEach { (v, label) ->
                    FilterChip(selected = m.nightBrightness == v, onClick = { setup = m.copy(nightBrightness = v) }, label = { Text(label) })
                }
            }
            if (m.nightBrightness != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LocalTime.of(22, 0), LocalTime.of(23, 0), LocalTime.MIDNIGHT).forEach { t ->
                        FilterChip(selected = m.nightStart == t, onClick = { setup = m.copy(nightStart = t) }, label = { Text("from %02d:00".format(t.hour)) })
                    }
                    listOf(LocalTime.of(6, 0), LocalTime.of(7, 0), LocalTime.of(8, 0)).forEach { t ->
                        FilterChip(selected = m.nightEnd == t, onClick = { setup = m.copy(nightEnd = t) }, label = { Text("to %02d:00".format(t.hour)) })
                    }
                }
            }
            SectionTitle("Turn off after no motion for")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5, 10, 15, 30).forEach { v ->
                    FilterChip(selected = m.offAfterMinutes == v, onClick = { setup = m.copy(offAfterMinutes = v) }, label = { Text("$v min") })
                }
            }
            if (sensor.lightLevelV1 != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { setup = m.copy(onlyWhenDark = !m.onlyWhenDark) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Only when it's dark", style = MaterialTheme.typography.bodyLarge)
                        Text("Uses the sensor's own light reading.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = m.onlyWhenDark, onCheckedChange = { setup = m.copy(onlyWhenDark = it) })
                }
            }
            if (existing != null) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { save(null) }, enabled = !saving) { Text("Stop using this sensor for lights") }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}
