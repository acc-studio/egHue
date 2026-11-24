package app.eghue.hue

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** Turns lights' current state into Hue scene actions ("save the current look"). */
object SceneCapture {
    fun actionFor(l: LightUi): JsonObject = buildJsonObject {
        putJsonObject("on") { put("on", l.on) }
        if (!l.on) return@buildJsonObject
        if (l.dimmable) putJsonObject("dimming") { put("brightness", ((l.brightness * 10).roundToInt() / 10.0).coerceIn(1.0, 100.0)) }
        val mirek = l.mirek
        when {
            l.ctActive && mirek != null && l.supportsCt -> putJsonObject("color_temperature") { put("mirek", mirek.coerceIn(l.mirekMin, l.mirekMax)) }
            l.xy != null && l.supportsColor -> putJsonObject("color") {
                putJsonObject("xy") { put("x", l.xy.x); put("y", l.xy.y) }
            }
        }
    }

    /** Scene `actions` for [lights], as the bridge expects them. */
    fun actions(lights: List<LightUi>): JsonArray = actionsJson(lights.associate { it.id to actionFor(it) })

    fun actionsJson(actions: Map<String, JsonObject>): JsonArray = JsonArray(actions.map { (id, action) ->
        buildJsonObject {
            putJsonObject("target") { put("rid", id); put("rtype", "light") }
            put("action", action)
        }
    })

    /** A name for a new scene that doesn't clash with [taken] (case-insensitive): "Scene", "Scene 2", … */
    fun uniqueName(base: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in lower) return base
        var n = 2
        while ("$base $n".lowercase() in lower) n++
        return "$base $n"
    }
}
