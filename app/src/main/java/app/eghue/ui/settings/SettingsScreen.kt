package app.eghue.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.BuildConfig
import app.eghue.data.ThemeMode
import app.eghue.data.ViewMode
import app.eghue.hue.ConnState
import app.eghue.update.UpdateState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onOpenRemote: (String) -> Unit) {
    val bridge by c.settings.bridge.collectAsStateWithLifecycle()
    val conn by c.repository.connection.collectAsStateWithLifecycle()
    val theme by c.settings.theme.collectAsStateWithLifecycle()
    val dynamic by c.settings.dynamicColor.collectAsStateWithLifecycle()
    val update by c.updater.state.collectAsStateWithLifecycle()
    val followPowerOn by c.settings.followPowerOn.collectAsStateWithLifecycle()
    val viewMode by c.settings.viewMode.collectAsStateWithLifecycle()
    val home by c.repository.home.collectAsStateWithLifecycle()
    val auto by c.updater.autoUpdate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var permissionIntent by remember { mutableStateOf(c.updater.missingPermissionIntent()) }
    LifecycleResumeEffect(Unit) {
        permissionIntent = c.updater.missingPermissionIntent()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card("Bridge") {
                Text(bridge?.name ?: "Not connected", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(bridge?.host, connText(conn)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conn is ConnState.Offline || conn == ConnState.Unauthorized) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch { searching = true; c.repository.refreshNow(); searching = false }
                        },
                        enabled = !searching,
                    ) { Text(if (searching) "Reconnecting…" else "Reconnect") }
                    OutlinedButton(onClick = { confirmDisconnect = true }) {
                        Text(if (conn == ConnState.Unauthorized) "Pair again" else "Disconnect")
                    }
                }
            }

            Card("Lights") {
                SwitchRow(
                    "Switch on to power-on settings",
                    if (followPowerOn) "Lights come on the way their power-on behaviour says, like with the wall switch. " +
                        "Set it per light in its controls."
                    else "Lights come back in their last colour and brightness.",
                    followPowerOn,
                ) { c.settings.setFollowPowerOn(it) }
            }

            if (home.remotes.isNotEmpty()) Card("Remotes") {
                home.remotes.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpenRemote(r.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(r.productName, r.battery?.let { "battery $it%" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Set up ${r.name}")
                    }
                }
            }

            Card("Appearance") {
                Text("View", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    val modes = listOf(ViewMode.LITE to "Lite", ViewMode.FULL to "Full")
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = viewMode == mode,
                            onClick = { c.settings.setViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    if (viewMode == ViewMode.LITE) "Just your rooms and lights, as fast as possible."
                    else "Everything: scenes, automations, devices and more, in tabs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = theme == mode,
                            onClick = { c.settings.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow("Colours from wallpaper", "Material You", dynamic) { c.settings.setDynamicColor(it) }
                }
            }

            Card("Updates") {
                Text(c.updater.sourceDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.updater.canToggleAutoUpdate) {
                    SwitchRow("Automatic updates", "Download and install new versions by themselves", auto) { c.updater.setAutoUpdate(it) }
                }
                permissionIntent?.let { intent ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { context.startActivity(intent) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Allow egHue to install its updates", style = MaterialTheme.typography.titleSmall)
                            Text("One-time permission so updates can install without extra taps.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                UpdateStatus(update)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (update) {
                        is UpdateState.Available, is UpdateState.ReadyToInstall ->
                            Button(onClick = c.updater::installNow) { Text("Install now") }
                        else -> FilledTonalButton(
                            onClick = c.updater::checkNow,
                            enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading && update !is UpdateState.Installing,
                        ) { Text("Check for updates") }
                    }
                }
            }

            Card("About") {
                Text("egHue ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Fast, simple control for Philips Hue. Works locally with your bridge; nothing leaves your home network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(if (conn == ConnState.Unauthorized) "Pair again?" else "Disconnect bridge?") },
            text = { Text("egHue forgets this bridge. You'll need to press the bridge button to connect again. Widgets stay on your home screen.") },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; c.settings.clearBridge() }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

private fun connText(conn: ConnState): String = when (conn) {
    ConnState.Connected -> "Connected"
    ConnState.Connecting -> "Connecting…"
    is ConnState.Offline -> conn.reason
    ConnState.Unauthorized -> "Access revoked, pair again"
    ConnState.NotPaired -> "Not paired"
}

@Composable
private fun UpdateStatus(s: UpdateState) {
    val text = when (s) {
        UpdateState.Idle -> null
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "You're on the latest version."
        is UpdateState.Available -> "Version ${s.version} is available."
        is UpdateState.Downloading -> "Downloading ${s.version}…"
        is UpdateState.ReadyToInstall -> "Version ${s.version} is ready. It installs when you leave the app."
        is UpdateState.Installing -> "Installing ${s.version}…"
        is UpdateState.Failed -> s.message
    }
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (s is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    if (s is UpdateState.Downloading) {
        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
