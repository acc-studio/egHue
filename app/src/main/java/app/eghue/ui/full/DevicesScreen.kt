package app.eghue.ui.full

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.LightUi
import app.eghue.hue.SensorUi
import app.eghue.ui.components.Dot
import app.eghue.ui.components.groupIcon
import app.eghue.ui.components.lightIcon
import app.eghue.ui.detail.DetailSheet
import app.eghue.ui.home.lightStatus
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Every light, sensor, remote and the bridge itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(c: AppContainer, onOpenSettings: () -> Unit, onOpenRemote: (String) -> Unit, onEditZone: (String?) -> Unit) {
    val repo = c.repository
    val home by repo.home.collectAsStateWithLifecycle()
    val bridge by c.settings.bridge.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices", style = MaterialTheme.typography.headlineSmall) },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Header("Rooms & zones") }
            items(home.rooms, key = { "g" + it.id }) { g ->
                Row_(groupIcon(g.archetype, g.kind), g.name, "Room · ${g.lightIds.size} lights", onClick = { detail = "g:${g.id}" })
            }
            items(home.zones, key = { "z" + it.id }) { z ->
                Row_(groupIcon(z.archetype, z.kind), z.name, "Zone · ${z.lightIds.size} lights · tap to edit", onClick = { onEditZone(z.id) })
            }
            item { Row_(Icons.Outlined.AddCircleOutline, "New zone", "Group any lights, across rooms", onClick = { onEditZone(null) }) }

            if (home.lights.isNotEmpty()) item { Header("Lights · ${home.lights.size}") }
            items(home.lights, key = { "l" + it.id }) { l -> LightRow(l, onClick = { detail = "l:${l.id}" }) }

            if (home.sensors.isNotEmpty()) item { Header("Sensors") }
            items(home.sensors, key = { "s" + it.id }) { s ->
                SensorCard(s, onMotionEnabled = { on -> s.motionId?.let { scope.launch { repo.setMotionEnabled(it, on) } } })
            }

            if (home.remotes.isNotEmpty()) item { Header("Remotes") }
            items(home.remotes, key = { "r" + it.id }) { r ->
                Row_(
                    icon = Icons.Outlined.SettingsRemote,
                    title = r.name,
                    subtitle = listOfNotNull(r.productName, r.battery?.let { "battery $it%" }, "not responding".takeIf { _ -> !r.reachable }).joinToString(" · "),
                    onClick = { onOpenRemote(r.id) },
                )
            }

            item { Header("Bridge") }
            item {
                Row_(
                    icon = Icons.Outlined.Router,
                    title = home.bridge?.name ?: bridge?.name ?: "Hue Bridge",
                    subtitle = listOfNotNull(home.bridge?.model, bridge?.host, home.bridge?.software?.let { "software $it" }).joinToString(" · "),
                    onClick = onOpenSettings,
                )
            }
        }
    }
    detail?.let { DetailSheet(it, home, repo, onNavigate = { t -> detail = t }, full = true) }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun LightRow(l: LightUi, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(lightIcon(l.on), contentDescription = null)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(lightStatus(l), l.effect?.let { "effect: $it" }, l.productName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (l.reachable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (l.on) Dot(Color(l.color))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun Row_(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** "2 min ago" from an ISO timestamp. */
fun ago(iso: String?, now: Instant = Instant.now()): String? {
    val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val d = Duration.between(t, now)
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} d ago"
    }
}

@Composable
private fun SensorCard(s: SensorUi, onMotionEnabled: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sensors, contentDescription = null)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(s.productName, s.battery?.let { "battery $it%" }, "not responding".takeIf { !s.reachable }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.motionId != null) Switch(checked = s.motionEnabled, onCheckedChange = onMotionEnabled)
            }
            val readings = listOfNotNull(
                s.motion?.let { m ->
                    if (!s.motionEnabled) "Motion detection off"
                    else if (m) "Motion now" else "No motion" + (ago(s.motionChanged)?.let { " · last $it" } ?: "")
                },
                s.temperature?.let { "%.1f °C".format(it) },
                s.lux?.let { "${it.roundToInt()} lux" },
                s.contactClosed?.let { if (it) "Closed" else "Open" },
            )
            if (readings.isNotEmpty()) Text(readings.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
