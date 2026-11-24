package app.eghue.hue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val HueJson = Json { ignoreUnknownKeys = true }

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** A `{rid, rtype}` resource reference. */
data class Ref(val rid: String, val rtype: String)

fun JsonObject.ref(key: String): Ref? = obj(key)?.toRef()
fun JsonObject.toRef(): Ref? {
    val rid = str("rid") ?: return null
    return Ref(rid, str("rtype") ?: "")
}
fun JsonObject.refs(key: String): List<Ref> = arr(key)?.objects()?.mapNotNull { it.toRef() }.orEmpty()

/**
 * Recursively merges [patch] into [base]. Objects merge key by key; anything else in [patch] replaces.
 * Hue event-stream updates and command bodies share the resource's shape, so one merge serves both.
 */
fun deepMerge(base: JsonObject, patch: JsonObject): JsonObject {
    if (patch.isEmpty()) return base
    val out = LinkedHashMap<String, JsonElement>(base)
    for ((k, pv) in patch) {
        val bv = out[k]
        out[k] = if (bv is JsonObject && pv is JsonObject) deepMerge(bv, pv) else pv
    }
    return JsonObject(out)
}

// ---- Command bodies -------------------------------------------------------------------------

fun onBody(on: Boolean) = buildJsonObject { putJsonObject("on") { put("on", on) } }

fun brightnessBody(brightness: Float) = buildJsonObject {
    putJsonObject("on") { put("on", true) }
    putJsonObject("dimming") { put("brightness", (brightness.coerceIn(1f, 100f) * 10).toInt() / 10.0) }
}

fun xyBody(xy: Xy) = buildJsonObject {
    putJsonObject("on") { put("on", true) }
    putJsonObject("color") {
        putJsonObject("xy") {
            put("x", (xy.x * 10000).toInt() / 10000.0)
            put("y", (xy.y * 10000).toInt() / 10000.0)
        }
    }
}

fun mirekBody(mirek: Int) = buildJsonObject {
    putJsonObject("on") { put("on", true) }
    putJsonObject("color_temperature") { put("mirek", mirek) }
}

/** Local-only hints merged with optimistic state so the UI shows the right colour mode at once. */
val CT_INACTIVE = buildJsonObject { putJsonObject("color_temperature") { put("mirek_valid", false) } }
val CT_ACTIVE = buildJsonObject { putJsonObject("color_temperature") { put("mirek_valid", true) } }

val RECALL_BODY = buildJsonObject { putJsonObject("recall") { put("action", "active") } }

/** The parts of a grouped_light command that also apply to each member light. */
fun memberPatch(body: JsonObject): JsonObject =
    JsonObject(body.filterKeys { it == "on" || it == "dimming" || it == "color" || it == "color_temperature" })

// ---- Power-on behaviour ---------------------------------------------------------------------

/** PUT body that stores [p] as the light's power-on behaviour, limited to what [light] supports. */
fun powerUpBody(p: PowerOn, light: LightUi): JsonObject = buildJsonObject {
    putJsonObject("powerup") {
        put("preset", p.preset)
        if (p.preset != PowerOn.CUSTOM) return@putJsonObject
        putJsonObject("on") {
            put("mode", "on")
            putJsonObject("on") { put("on", true) }
        }
        if (light.dimmable) putJsonObject("dimming") {
            if (p.brightness != null) {
                put("mode", "dimming")
                putJsonObject("dimming") { put("brightness", (p.brightness.coerceIn(1f, 100f) * 10).toInt() / 10.0) }
            } else put("mode", "previous")
        }
        if (light.supportsColor || light.supportsCt) putJsonObject("color") {
            when {
                p.xy != null && light.supportsColor -> {
                    put("mode", "color")
                    putJsonObject("color") { putJsonObject("xy") { put("x", p.xy.x); put("y", p.xy.y) } }
                }
                p.mirek != null && light.supportsCt -> {
                    put("mode", "color_temperature")
                    putJsonObject("color_temperature") { put("mirek", p.mirek.coerceIn(light.mirekMin, light.mirekMax)) }
                }
                else -> put("mode", "previous")
            }
        }
    }
}

/**
 * The command that switches [l] on. When [followPowerOn] is set, lights with a custom (or Hue
 * default) power-on behaviour come on in that state, the same as after flipping the wall switch.
 * Lights set to "last on state" / "power loss recovery" simply come back as they were.
 */
fun switchOnBody(l: LightUi, followPowerOn: Boolean): JsonObject {
    val p = l.powerOn
    if (!followPowerOn || p == null) return onBody(true)
    return when (p.preset) {
        PowerOn.CUSTOM -> buildJsonObject {
            putJsonObject("on") { put("on", true) }
            if (l.dimmable && p.brightness != null) putJsonObject("dimming") { put("brightness", p.brightness.toDouble()) }
            when {
                p.xy != null && l.supportsColor -> putJsonObject("color") { putJsonObject("xy") { put("x", p.xy.x); put("y", p.xy.y) } }
                p.mirek != null && l.supportsCt -> putJsonObject("color_temperature") { put("mirek", p.mirek) }
            }
        }
        PowerOn.SAFETY -> buildJsonObject {
            putJsonObject("on") { put("on", true) }
            if (l.dimmable) putJsonObject("dimming") { put("brightness", 100.0) }
            if (l.supportsCt) putJsonObject("color_temperature") { put("mirek", PowerOn.SAFETY_MIREK.coerceIn(l.mirekMin, l.mirekMax)) }
        }
        else -> onBody(true)
    }
}

/** True if [body] sets more than just on/off (so it can't be replaced by one group command). */
fun JsonObject.setsState(): Boolean = keys.any { it != "on" }

/** Local-only colour-mode hint matching a command body, for the optimistic UI. */
fun JsonObject.colorModeHint(): JsonObject? = when {
    containsKey("color") -> CT_INACTIVE
    containsKey("color_temperature") -> CT_ACTIVE
    else -> null
}
