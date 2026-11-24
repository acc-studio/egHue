package app.eghue.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eghue.AppContainer
import app.eghue.data.ViewMode
import app.eghue.hue.ConnState
import app.eghue.hue.GroupUi
import app.eghue.hue.HueRepository
import app.eghue.hue.LightUi
import app.eghue.ui.components.BrightnessBar
import app.eghue.ui.components.ControlTile
import app.eghue.hue.ColorUtil
import app.eghue.ui.components.groupIcon
import app.eghue.ui.components.lightIcon
import app.eghue.ui.detail.DetailSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(c: AppContainer, onOpenSettings: () -> Unit, onEditScene: ((String) -> Unit)? = null) {
    val repo = c.repository
    val viewMode by c.settings.viewMode.collectAsStateWithLifecycle()
    val showLights by c.settings.showLights.collectAsStateWithLifecycle()
    val home by repo.home.collectAsStateWithLifecycle()
    val conn by repo.connection.collectAsStateWithLifecycle()
    val loaded by repo.loaded.collectAsStateWithLifecycle()
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(repo) { repo.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Home", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    val full = viewMode == ViewMode.FULL
                    IconButton(onClick = { c.settings.setViewMode(if (full) ViewMode.LITE else ViewMode.FULL) }) {
                        Icon(
                            if (full) Icons.Outlined.Bolt else Icons.Outlined.Apps,
                            contentDescription = if (full) "Switch to Lite view" else "Switch to Full view",
                        )
                    }
                    IconButton(onClick = repo::allOff, enabled = home.lights.any { it.on }) {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Turn all lights off")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    repo.refreshNow()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
        ) {
            if (!loaded && home.isEmpty) {
                LoadingState(conn, onRetry = repo::reconnectNow)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "banner", span = { GridItemSpan(maxLineSpan) }) {
                        ConnectionBanner(conn, onRetry = repo::reconnectNow)
                    }
                    if (viewMode == ViewMode.FULL && home.homeGroupedLightId != null && home.lights.isNotEmpty()) {
                        item(key = "all", span = { GridItemSpan(maxLineSpan) }) {
                            val on = home.lights.filter { it.on }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (on.isEmpty()) "All lights · off" else "All lights · ${on.size} on",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                                BrightnessBar(
                                    value = on.map { it.brightness }.average().takeUnless { it.isNaN() }?.toFloat() ?: 0f,
                                    on = on.isNotEmpty(),
                                    color = Color(ColorUtil.average(on.map { it.color })),
                                    enabled = on.isNotEmpty(),
                                    onChange = repo::setHomeBrightness,
                                )
                            }
                        }
                    }
                    groupSection("Rooms", home.rooms, repo) { detail = "g:$it" }
                    groupSection("Zones", home.zones, repo) { detail = "g:$it" }
                    if (home.lights.isNotEmpty() && showLights) {
                        header("lights", "Lights")
                        items(home.lights, key = { "l:" + it.id }) { l ->
                            LightTile(l, repo, onOpen = { detail = "l:${l.id}" })
                        }
                    }
                    if (home.isEmpty) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Your bridge has no lights yet. Add them in the Hue app, then pull to refresh.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { target ->
        DetailSheet(
            target = target,
            home = home,
            repo = repo,
            onNavigate = { detail = it },
            onEditScene = onEditScene,
        )
    }
}

private fun LazyGridScope.header(key: String, title: String) {
    item(key = "h:$key", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

private fun LazyGridScope.groupSection(title: String, groups: List<GroupUi>, repo: HueRepository, onOpen: (String) -> Unit) {
    if (groups.isEmpty()) return
    header(title, title)
    items(groups, key = { "g:" + it.id }) { g -> GroupTile(g, repo, onOpen = { onOpen(g.id) }) }
}

@Composable
fun GroupTile(g: GroupUi, repo: HueRepository, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ControlTile(
        name = g.name,
        icon = groupIcon(g.archetype, g.kind),
        on = g.on,
        brightness = g.brightness,
        color = Color(g.color),
        status = groupStatus(g),
        enabled = g.groupedLightId != null && g.lightIds.isNotEmpty(),
        dimmable = g.dimmable,
        onToggle = { repo.setGroupOn(g.id, !g.on) },
        onBrightness = { repo.setGroupBrightness(g.id, it) },
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
fun LightTile(l: LightUi, repo: HueRepository, onOpen: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    ControlTile(
        name = l.name,
        icon = lightIcon(l.on),
        on = l.on,
        brightness = l.brightness,
        color = Color(l.color),
        status = lightStatus(l),
        enabled = true,
        dimmable = l.dimmable,
        onToggle = { repo.setLightOn(l.id, !l.on) },
        onBrightness = { repo.setLightBrightness(l.id, it) },
        onOpen = onOpen,
        modifier = modifier,
        height = if (compact) 100.dp else 108.dp,
    )
}

fun groupStatus(g: GroupUi): String = when {
    g.lightIds.isEmpty() -> "No lights"
    g.reachableLights == 0 -> "Unreachable"
    !g.on -> "Off"
    g.lightsOn in 1 until g.lightIds.size -> "${g.lightsOn} of ${g.lightIds.size} on · ${g.brightness.roundToInt()}%"
    else -> "${g.brightness.roundToInt()}%"
}

fun lightStatus(l: LightUi): String = when {
    !l.reachable -> "Unreachable"
    !l.on -> "Off"
    !l.dimmable -> "On"
    else -> "${l.brightness.roundToInt()}%"
}

@Composable
private fun ConnectionBanner(conn: ConnState, onRetry: () -> Unit) {
    val text = when (conn) {
        is ConnState.Offline -> "${conn.reason}. Retrying…"
        ConnState.Unauthorized -> "The bridge no longer accepts egHue. Reconnect in Settings."
        else -> null
    }
    AnimatedVisibility(text != null, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WifiOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text(text ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (conn is ConnState.Offline) TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun LoadingState(conn: ConnState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            if (conn is ConnState.Offline) {
                Icon(Icons.Outlined.WifiOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(conn.reason, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Make sure your phone is on the same Wi-Fi as the bridge.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Connecting to your bridge…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
