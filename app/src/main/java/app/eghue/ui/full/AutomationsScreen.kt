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
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.hue.AutoAction
import app.eghue.hue.AutoKind
import app.eghue.hue.AutoWhen
import app.eghue.hue.Automation
import app.eghue.hue.Automations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

fun kindIcon(k: AutoKind): ImageVector = when (k) {
    AutoKind.WAKE -> Icons.Outlined.Alarm
    AutoKind.BEDTIME -> Icons.Outlined.Bedtime
    AutoKind.TIMER -> Icons.Outlined.HourglassBottom
    AutoKind.AT_TIME -> Icons.Outlined.Event
    AutoKind.OTHER -> Icons.Outlined.Schedule
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** One line on what an automation does: "07:00 · Weekdays · 30 min sunrise". */
fun describe(a: Automation, now: Instant = Instant.now()): String {
    val s = a.spec ?: return "Made in another app"
    val parts = mutableListOf<String>()
    when (val w = s.whenever) {
        is AutoWhen.Daily -> { parts += w.time.format(hhmm); parts += Automations.describeDays(w.days) }
        is AutoWhen.In -> parts += a.endsAt?.let { end ->
            val left = Duration.between(now, end)
            if (left.isNegative) "Finished" else "Off in ${formatLeft(left)}"
        } ?: "In ${w.minutes} min"
    }
    when (val act = s.action) {
        AutoAction.Off -> if (s.kind != AutoKind.TIMER) parts += if (s.fadeMinutes > 0) "fade out over ${s.fadeMinutes} min" else "off"
        is AutoAction.On -> parts += if (s.kind == AutoKind.WAKE && s.fadeMinutes > 0) "${s.fadeMinutes} min sunrise to ${act.brightness}%" else "on at ${act.brightness}%"
        is AutoAction.Scene -> parts += "scene"
    }
    return parts.joinToString(" · ")
}

fun formatLeft(d: Duration): String {
    val m = (d.seconds + 59) / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(c: AppContainer, onOpenSettings: () -> Unit, onEdit: (String?) -> Unit) {
    val repo = c.repository
    val list by repo.automations.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    LaunchedEffect(Unit) {
        repo.loadAutomations(); loadedOnce = true
        // Keep countdowns current; reload now and then to drop timers that have gone off.
        var n = 0
        while (true) { delay(15_000); now = Instant.now(); if (++n % 4 == 0) repo.loadAutomations() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automations", style = MaterialTheme.typography.headlineSmall) },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onEdit(null) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; repo.loadAutomations(); refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (list.isEmpty() && loadedOnce) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No automations yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "Wake up to a slow sunrise, fade out at bedtime, or switch a room off after a while. " +
                                "They run on your bridge, so they work with your phone off.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(list, key = { it.id }) { a ->
                    AutomationRow(
                        a, now,
                        onClick = { if (a.spec != null && a.kind != AutoKind.OTHER) onEdit(a.id) },
                        onToggle = { on -> scope.launch { repo.setAutomationEnabled(a.id, on) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationRow(a: Automation, now: Instant, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(kindIcon(a.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                val title = a.spec?.let { s -> if (a.kind == AutoKind.OTHER) a.name else "${a.kind.title} · ${s.targetName}" } ?: a.name
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(describe(a, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = a.enabled, onCheckedChange = onToggle)
        }
    }
}
