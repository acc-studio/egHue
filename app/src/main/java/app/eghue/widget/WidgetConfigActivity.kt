package app.eghue.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.eghue.container
import app.eghue.hue.HueRepository
import app.eghue.ui.AppTheme
import app.eghue.ui.MainActivity
import app.eghue.ui.components.groupIcon
import app.eghue.ui.components.lightIcon
import kotlinx.coroutines.launch

/** Shown when a widget is added (or reconfigured): pick what the widget controls. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val c = container
        val reconfiguring = c.widgetPrefs.isConfigured(widgetId)
        lifecycleScope.launch {
            c.repository.ensureLoaded()
            runCatching { c.repository.refresh() }
        }
        setContent {
            AppTheme(c) {
                ConfigScreen(
                    initial = c.widgetPrefs.get(widgetId),
                    reconfiguring = reconfiguring,
                    onDone = { selection -> save(widgetId, selection) },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun save(widgetId: Int, selection: List<String>) {
        val c = container
        c.widgetPrefs.set(widgetId, selection)
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
                HueWidget().update(this@WidgetConfigActivity, glanceId)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

private data class Option(val key: String, val name: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentActivity.ConfigScreen(
    initial: List<String>,
    reconfiguring: Boolean,
    onDone: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val c = container
    val paired by c.settings.paired.collectAsStateWithLifecycle()
    val home by c.repository.home.collectAsStateWithLifecycle()
    val loaded by c.repository.loaded.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    val seeded = remember { booleanArrayOf(initial.isNotEmpty() || reconfiguring) }

    // First-time setup: start with all rooms ticked; that's what most people want.
    if (!seeded[0] && home.rooms.isNotEmpty()) {
        selected.addAll(home.rooms.map { "${HueRepository.KIND_GROUP}:${it.id}" })
        seeded[0] = true
    }

    val sections = listOf(
        "Rooms" to home.rooms.map { Option("${HueRepository.KIND_GROUP}:${it.id}", it.name, groupIcon(it.archetype, it.kind)) },
        "Zones" to home.zones.map { Option("${HueRepository.KIND_GROUP}:${it.id}", it.name, groupIcon(it.archetype, it.kind)) },
        "Lights" to home.lights.map { Option("${HueRepository.KIND_LIGHT}:${it.id}", it.name, lightIcon(true)) },
    ).filter { it.second.isNotEmpty() }
    // Keep display order (rooms, zones, lights) regardless of tap order.
    val order = sections.flatMap { s -> s.second.map { it.key } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose what to show") },
                actions = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
        bottomBar = {
            if (paired) Surface(tonalElevation = 3.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(
                        onClick = { onDone(order.filter { it in selected }) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (reconfiguring) "Save" else "Add widget (${selected.size})") }
                }
            }
        },
    ) { pad ->
        when {
            !paired -> Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Connect egHue to your Hue Bridge first.", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    startActivity(Intent(this@ConfigScreen, MainActivity::class.java))
                    onCancel()
                }) { Text("Open egHue") }
            }
            !loaded && home.isEmpty -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sections.forEach { (title, options) ->
                    item(key = "h:$title") {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(options, key = { it.key }) { o ->
                        val checked = o.key in selected
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (checked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().clickable { if (checked) selected.remove(o.key) else selected.add(o.key) },
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(o.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.size(14.dp))
                                Text(o.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Checkbox(checked = checked, onCheckedChange = { if (it) selected.add(o.key) else selected.remove(o.key) })
                            }
                        }
                    }
                }
            }
        }
    }
}
