package app.eghue.shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.eghue.R
import app.eghue.container
import app.eghue.hue.GroupUi
import app.eghue.hue.HomeUi
import app.eghue.hue.HueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Launcher-icon shortcuts: one per room (up to four), each toggling it without opening the app. */
object LauncherShortcuts {
    private const val ACTION = "app.eghue.TOGGLE"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_ID = "id"
    private const val ALL_OFF = "all_off"

    /** Rooms first, by name, then an "All off" shortcut. Called whenever the room list changes. */
    fun publish(context: Context, home: HomeUi) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
        if (max <= 0) return
        val rooms = home.rooms.filter { it.lightIds.isNotEmpty() && it.groupedLightId != null }
        val shortcuts = buildList {
            add(shortcut(context, ALL_OFF, "", "All lights off", "All off", R.drawable.ic_tile_power))
            rooms.take(max - 1).forEach { g -> add(shortcut(context, HueRepository.KIND_GROUP, g.id, "Toggle ${g.name}", g.name, R.drawable.ic_tile_room)) }
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** A stable key so shortcuts are only republished when rooms change. */
    fun fingerprint(home: HomeUi): String = home.rooms.joinToString("|") { "${it.id}:${it.name}:${it.lightIds.isNotEmpty()}" }

    private fun shortcut(context: Context, kind: String, id: String, long: String, short: String, icon: Int) =
        ShortcutInfoCompat.Builder(context, "$kind:$id")
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, ShortcutActivity::class.java).setAction(ACTION).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ID, id))
            .build()

    /** Runs a shortcut's action in the app's scope (it outlives the invisible activity). */
    fun run(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val c = context.container
        val app = context.applicationContext
        c.scope.launch {
            val ok = if (kind == ALL_OFF) c.repository.allOffNow() else c.repository.widgetToggle(kind, id) { }
            if (!ok) launch(Dispatchers.Main) { Toast.makeText(app, "Can't reach your Hue Bridge", Toast.LENGTH_SHORT).show() }
        }
    }
}

/** Invisible activity behind launcher shortcuts. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherShortcuts.run(this, intent)
        finish()
    }
}

/** Quick Settings tile: every light off. */
class AllOffTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_all_off)
            updateTile()
        }
    }

    override fun onClick() {
        val tile = qsTile
        tile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
        // The app scope, so the command isn't cancelled when the panel closes.
        container.scope.launch {
            container.repository.allOffNow()
            scope.launch { tile?.apply { state = Tile.STATE_INACTIVE; updateTile() } }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Quick Settings tile: toggles one room (chosen in Settings; the first room by default). */
class RoomTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    private fun room(home: HomeUi): GroupUi? {
        val chosen = container.settings.tileRoom.value
        return (home.rooms + home.zones).firstOrNull { it.id == chosen }
            ?: home.rooms.firstOrNull { it.lightIds.isNotEmpty() }
    }

    override fun onStartListening() {
        val repo = container.repository
        repo.refreshIfStale(15_000)
        watch?.cancel()
        watch = scope.launch {
            repo.ensureLoaded()
            repo.home.collect { render(room(it)) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
    }

    private fun render(g: GroupUi?) {
        val tile = qsTile ?: return
        tile.label = g?.name ?: getString(R.string.tile_room)
        tile.state = when {
            g == null -> Tile.STATE_UNAVAILABLE
            g.on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                g == null -> "No rooms"
                g.on -> "${g.brightness.toInt()}%"
                else -> "Off"
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_room)
        tile.updateTile()
    }

    override fun onClick() {
        val g = room(container.repository.home.value) ?: return
        container.scope.launch { container.repository.widgetToggle(HueRepository.KIND_GROUP, g.id) { } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
