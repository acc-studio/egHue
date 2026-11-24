package app.eghue.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Lite: the quick home screen only. Full: every feature, in tabs. */
enum class ViewMode { LITE, FULL }

data class BridgeInfo(val id: String, val name: String, val host: String)

/** App settings. SharedPreferences keeps reads synchronous, so the first frame has the right theme. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val bridgeHost: String? get() = prefs.getString(K_HOST, null)
    val bridgeId: String? get() = prefs.getString(K_ID, null)
    val appKey: String? get() = prefs.getString(K_KEY, null)
    val certPin: String? get() = prefs.getString(K_PIN, null)

    private val _bridge = MutableStateFlow(readBridge())
    val bridge: StateFlow<BridgeInfo?> = _bridge.asStateFlow()

    private val _paired = MutableStateFlow(appKey != null && bridgeHost != null)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(K_DYNAMIC, true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _followPowerOn = MutableStateFlow(prefs.getBoolean(K_FOLLOW_POWER_ON, true))
    /** Switch lights on in their power-on (default) state rather than their last state. */
    val followPowerOn: StateFlow<Boolean> = _followPowerOn.asStateFlow()

    fun setFollowPowerOn(enabled: Boolean) {
        prefs.edit { putBoolean(K_FOLLOW_POWER_ON, enabled) }
        _followPowerOn.value = enabled
    }

    private val _viewMode = MutableStateFlow(
        runCatching { ViewMode.valueOf(prefs.getString(K_VIEW, null) ?: "") }.getOrDefault(ViewMode.LITE)
    )
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    fun setViewMode(mode: ViewMode) {
        prefs.edit { putString(K_VIEW, mode.name) }
        _viewMode.value = mode
    }

    private val _showLights = MutableStateFlow(prefs.getBoolean(K_SHOW_LIGHTS, true))
    /** Show the individual Lights section on the home screen (rooms and zones always show). */
    val showLights: StateFlow<Boolean> = _showLights.asStateFlow()

    fun setShowLights(show: Boolean) {
        prefs.edit { putBoolean(K_SHOW_LIGHTS, show) }
        _showLights.value = show
    }

    private val _tileRoom = MutableStateFlow(prefs.getString(K_TILE_ROOM, null))
    /** The room or zone the Quick Settings room tile toggles (null: the first room). */
    val tileRoom: StateFlow<String?> = _tileRoom.asStateFlow()

    fun setTileRoom(id: String?) {
        prefs.edit { putString(K_TILE_ROOM, id) }
        _tileRoom.value = id
    }

    fun saveBridge(host: String, id: String, name: String, appKey: String, clientKey: String?, certPin: String?) {
        prefs.edit(commit = true) {
            putString(K_HOST, host)
            putString(K_ID, id)
            putString(K_NAME, name)
            putString(K_KEY, appKey)
            putString(K_CLIENT_KEY, clientKey)
            putString(K_PIN, certPin)
        }
        _bridge.value = readBridge()
        _paired.value = true
    }

    /** The bridge moved to a new IP address (DHCP); keep the pairing. */
    fun updateHost(host: String) {
        prefs.edit(commit = true) { putString(K_HOST, host) }
        _bridge.value = readBridge()
    }

    fun clearBridge() {
        prefs.edit(commit = true) {
            listOf(K_HOST, K_ID, K_NAME, K_KEY, K_CLIENT_KEY, K_PIN).forEach { remove(it) }
        }
        _bridge.value = null
        _paired.value = false
    }

    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(K_THEME, mode.name) }
        _theme.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(K_DYNAMIC, enabled) }
        _dynamicColor.value = enabled
    }

    private fun readBridge(): BridgeInfo? {
        val host = prefs.getString(K_HOST, null) ?: return null
        return BridgeInfo(prefs.getString(K_ID, null) ?: "", prefs.getString(K_NAME, null) ?: "Hue Bridge", host)
    }

    private companion object {
        const val K_HOST = "bridge_host"
        const val K_ID = "bridge_id"
        const val K_NAME = "bridge_name"
        const val K_KEY = "app_key"
        const val K_CLIENT_KEY = "client_key"
        const val K_PIN = "cert_pin"
        const val K_THEME = "theme"
        const val K_DYNAMIC = "dynamic_color"
        const val K_FOLLOW_POWER_ON = "follow_power_on"
        const val K_VIEW = "view_mode"
        const val K_TILE_ROOM = "tile_room"
        const val K_SHOW_LIGHTS = "show_lights"
    }
}
