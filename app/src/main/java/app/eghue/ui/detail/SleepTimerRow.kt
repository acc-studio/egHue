package app.eghue.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.eghue.hue.HueRepository
import app.eghue.ui.full.formatLeft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** "Off in 15 / 30 / 60 min" chips; shows the countdown when a timer runs. Runs on the bridge. */
@Composable
fun SleepTimerRow(targetV1: String, repo: HueRepository) {
    val scope = rememberCoroutineScope()
    val list by repo.automations.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(Instant.now()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(targetV1) {
        repo.loadAutomations()
        while (true) { delay(10_000); now = Instant.now() }
    }
    val timer = remember(list, now) { repo.sleepTimerFor(targetV1) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.HourglassBottom, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(2.dp))
        val end = timer?.endsAt
        if (timer != null) {
            InputChip(
                selected = true,
                onClick = { if (!busy) scope.launch { busy = true; repo.cancelSleepTimer(targetV1); busy = false } },
                label = { Text(if (end != null) "Off in ${formatLeft(Duration.between(now, end).coerceAtLeast(Duration.ZERO))} · Cancel" else "Timer on · Cancel") },
            )
        } else {
            Text("Off in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(15, 30, 60, 90).forEach { m ->
                AssistChip(
                    enabled = !busy,
                    onClick = { scope.launch { busy = true; repo.startSleepTimer(targetV1, m); busy = false } },
                    label = { Text(if (m < 60) "$m min" else if (m == 60) "1 h" else "1.5 h") },
                )
            }
        }
    }
}
