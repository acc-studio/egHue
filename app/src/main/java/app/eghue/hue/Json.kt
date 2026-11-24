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
