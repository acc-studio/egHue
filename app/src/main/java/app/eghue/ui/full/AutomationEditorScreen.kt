package app.eghue.ui.full

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.AutoAction
import app.eghue.hue.AutoKind
import app.eghue.hue.AutoSpec
import app.eghue.hue.AutoWhen
import app.eghue.ui.components.ConfirmDialog
import app.eghue.ui.components.SceneSwatch
import app.eghue.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.math.roundToInt

private val warmths = listOf("Candle" to 455, "Warm" to 370, "Neutral" to 300, "Daylight" to 220)

/** Creates or edits a wake-up, bedtime, timer or timed action. [id] null makes a new one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutomationEditorScreen(c: AppContainer, id: String?, onDone: () -> Unit) {
    val repo = c.repository
    val home by repo.home.collectAsStateWithLifecycle()
    val list by repo.automations.collectAsStateWithLifecycle()
    val existing = id?.let { i -> list.firstOrNull { it.id == i } }
    val spec = existing?.spec
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }

    val targets = buildList {
        (home.rooms + home.zones).filter { it.idV1 != null && it.lightIds.isNotEmpty() }.forEach { add(it.idV1!! to it.name) }
        add("/groups/0" to "Whole home")
    }
    // Natural-light steps edit as timed actions, and stay natural-light steps when saved.
    val wasNatural = spec?.kind == AutoKind.NATURAL
    var kind by rememberSaveable { mutableStateOf(spec?.kind?.takeIf { !wasNatural } ?: if (wasNatural) AutoKind.AT_TIME else AutoKind.WAKE) }
    var target by rememberSaveable { mutableStateOf(spec?.targetV1 ?: targets.first().first) }
    val daily = spec?.whenever as? AutoWhen.Daily
    var hour by rememberSaveable { mutableIntStateOf(daily?.time?.hour ?: 7) }
    var minute by rememberSaveable { mutableIntStateOf(daily?.time?.minute ?: 0) }
    var days by rememberSaveable {
        mutableStateOf(daily?.days?.map { it.value }?.toSet() ?: (1..5).toSet())
    }
    var fade by rememberSaveable { mutableIntStateOf(spec?.fadeMinutes?.takeIf { it > 0 } ?: 30) }
    var timerMinutes by rememberSaveable { mutableIntStateOf((spec?.whenever as? AutoWhen.In)?.minutes ?: 30) }
    val on = spec?.action as? AutoAction.On
    val adjust = spec?.action as? AutoAction.Adjust
    var brightness by rememberSaveable { mutableIntStateOf(on?.brightness ?: adjust?.brightness ?: 100) }
    var mirek by rememberSaveable { mutableIntStateOf(on?.mirek ?: adjust?.mirek ?: 370) }
    // For "At a time": "on", "off" or a scene's v1 id.
    var doWhat by rememberSaveable {
        mutableStateOf(when (val a = spec?.action) { AutoAction.Off -> "off"; is AutoAction.Scene -> a.sceneV1; is AutoAction.Adjust -> "adjust"; else -> "on" })
    }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun build(): AutoSpec {
        val name = targets.firstOrNull { it.first == target }?.second ?: "lights"
        val time = LocalTime.of(hour, minute)
        val dayset = days.map { DayOfWeek.of(it) }.toSet()
        return when (kind) {
            AutoKind.WAKE -> AutoSpec(kind, target, name, AutoAction.On(brightness, mirek), AutoWhen.Daily(time, dayset), fade)
            AutoKind.BEDTIME -> AutoSpec(kind, target, name, AutoAction.Off, AutoWhen.Daily(time, dayset), fade)
            AutoKind.TIMER -> AutoSpec(kind, target, name, AutoAction.Off, AutoWhen.In(timerMinutes), 1)
            else -> AutoSpec(
                if (wasNatural && doWhat == "adjust") AutoKind.NATURAL else AutoKind.AT_TIME, target, name,
                when (doWhat) {
                    "on" -> AutoAction.On(brightness, mirek)
                    "off" -> AutoAction.Off
                    "adjust" -> AutoAction.Adjust(brightness, mirek)
                    else -> AutoAction.Scene(doWhat)
                },
                AutoWhen.Daily(time, dayset),
                // Adjustments glide rather than jump.
                fadeMinutes = if (doWhat == "adjust") (spec?.fadeMinutes ?: 10) else 0,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") } },
                title = { Text(if (existing != null) "Edit automation" else "New automation") },
                actions = {
                    if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                    Button(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                // A timer can't be rewritten in place (its countdown would restart oddly): replace it.
                                val ok = if (kind == AutoKind.TIMER) {
                                    existing?.let { repo.deleteAutomation(it.id) }
                                    repo.saveAutomation(null, build())
                                } else repo.saveAutomation(existing?.id, build())
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val kinds = listOf(AutoKind.WAKE, AutoKind.BEDTIME, AutoKind.TIMER, AutoKind.AT_TIME)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                        icon = {},
                    ) { Text(when (k) { AutoKind.WAKE -> "Wake"; AutoKind.BEDTIME -> "Bedtime"; AutoKind.TIMER -> "Timer"; else -> "Time" }, maxLines = 1) }
                }
            }
            Text(
                when (kind) {
                    AutoKind.WAKE -> "Lights rise slowly, like a sunrise, and are fully up at the time you choose."
                    AutoKind.BEDTIME -> "At the time you choose, lights fade out slowly."
                    AutoKind.TIMER -> "Switches off after a while, fading over the last minute."
                    else -> "Switches on, off or to a scene at a set time."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Where")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { (addr, name) -> FilterChip(selected = target == addr, onClick = { target = addr }, label = { Text(name) }) }
            }

            if (kind == AutoKind.TIMER) {
                SectionTitle("Switch off in")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 45, 60, 90, 120).forEach { m ->
                        FilterChip(selected = timerMinutes == m, onClick = { timerMinutes = m }, label = { Text(if (m < 60) "$m min" else "${m / 60}${if (m % 60 == 30) ".5" else ""} h") })
                    }
                }
            } else {
                SectionTitle(if (kind == AutoKind.WAKE) "Fully up at" else "Time")
                OutlinedButton(onClick = { pickTime = true }) {
                    Text("%02d:%02d".format(hour, minute), style = MaterialTheme.typography.headlineMedium)
                }
                SectionTitle("Days")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d.value in days,
                            onClick = { days = if (d.value in days) days - d.value else days + d.value },
                            label = { Text(d.name.take(2).lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                if (days.isEmpty()) Text("No days picked: runs once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (kind == AutoKind.WAKE || kind == AutoKind.BEDTIME) {
                SectionTitle(if (kind == AutoKind.WAKE) "Sunrise length" else "Fade length")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20, 30, 45, 60).forEach { m ->
                        FilterChip(selected = fade == m, onClick = { fade = m }, label = { Text("$m min") })
                    }
                }
            }

            if (kind == AutoKind.AT_TIME) {
                SectionTitle("Do")
                val scenes = home.group(groupIdFor(home, target) ?: "")?.let { g -> home.scenesFor(g.id) }.orEmpty()
                    .filter { it.idV1 != null && !it.retired }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = doWhat == "on", onClick = { doWhat = "on" }, label = { Text("Turn on") })
                    FilterChip(selected = doWhat == "off", onClick = { doWhat = "off" }, label = { Text("Turn off") })
                    FilterChip(selected = doWhat == "adjust", onClick = { doWhat = "adjust" }, label = { Text("Adjust if on") })
                    scenes.forEach { s ->
                        val v1 = s.idV1!!.removePrefix("/scenes/")
                        FilterChip(
                            selected = doWhat == v1,
                            onClick = { doWhat = v1 },
                            label = { Text(s.name) },
                            leadingIcon = { SceneSwatch(s.colors, size = 18.dp) },
                        )
                    }
                }
            }

            if (kind == AutoKind.WAKE || (kind == AutoKind.AT_TIME && (doWhat == "on" || doWhat == "adjust"))) {
                SectionTitle("Brightness · $brightness%")
                Slider(value = brightness.toFloat(), onValueChange = { brightness = it.roundToInt() }, valueRange = 1f..100f)
                SectionTitle("White")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    warmths.forEach { (label, m) -> FilterChip(selected = mirek == m, onClick = { mirek = m }, label = { Text(label) }) }
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { hour = state.hour; minute = state.minute; pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Delete this automation?",
            text = "It's removed from your bridge.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; scope.launch { if (repo.deleteAutomation(existing.id)) onDone() } },
        )
    }
}

private fun groupIdFor(home: app.eghue.hue.HomeUi, v1: String): String? =
    (home.rooms + home.zones).firstOrNull { it.idV1 == v1 }?.id
