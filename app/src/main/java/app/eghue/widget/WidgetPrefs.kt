package app.eghue.widget

import android.content.Context
import androidx.core.content.edit
import app.eghue.hue.HomeUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Which rooms/zones ("g:<id>") and lights ("l:<id>") each widget instance shows, in order. */
class WidgetPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private val flows = HashMap<Int, MutableStateFlow<List<String>>>()

    fun get(widgetId: Int): List<String> =
        prefs.getString(key(widgetId), null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun isConfigured(widgetId: Int): Boolean = prefs.contains(key(widgetId))

    fun set(widgetId: Int, items: List<String>) {
        prefs.edit(commit = true) { putString(key(widgetId), items.joinToString(",")) }
        synchronized(flows) { flows[widgetId]?.value = items }
    }

    fun remove(widgetId: Int) {
        prefs.edit { remove(key(widgetId)) }
        synchronized(flows) { flows.remove(widgetId) }
    }

    fun flow(widgetId: Int): StateFlow<List<String>> = synchronized(flows) {
        flows.getOrPut(widgetId) { MutableStateFlow(get(widgetId)) }
    }

    /** What a widget can display; used to skip widget refreshes when nothing visible changed. */
    fun fingerprint(home: HomeUi): Int {
        var h = 17
        for (g in home.rooms + home.zones) h = 31 * h + (g.id + g.name + g.on + g.brightness.roundToInt() + g.color).hashCode()
        for (l in home.lights) h = 31 * h + (l.id + l.name + l.on + l.brightness.roundToInt() + l.color + l.reachable).hashCode()
        return h
    }

    private fun key(id: Int) = "w_$id"
}
