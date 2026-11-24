package app.eghue.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
    }
}
