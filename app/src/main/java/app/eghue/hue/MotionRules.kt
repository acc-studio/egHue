package app.eghue.hue

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * What a motion sensor does, as bridge (v1) rules: motion switches a room on (dimmer at night,
 * optionally only when it's dark), and it goes off after some minutes without motion.
 */
data class MotionSetup(
    /** Legacy presence sensor, e.g. "/sensors/5". */
    val presenceV1: String,
    /** Legacy light-level sensor of the same device, for "only when dark". */
    val lightLevelV1: String?,
    /** Room or zone, e.g. "/groups/3". */
    val targetV1: String,
    val brightness: Int = 100,
    val mirek: Int = 370,
    /** Brightness during [nightStart]–[nightEnd]; 0 = don't switch on at night; null = no night mode. */
    val nightBrightness: Int? = 20,
    val nightStart: LocalTime = LocalTime.of(23, 0),
    val nightEnd: LocalTime = LocalTime.of(7, 0),
    val onlyWhenDark: Boolean = true,
    val offAfterMinutes: Int = 5,
)

object MotionRules {
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun prefix(presenceV1: String) = "egHue motion ${presenceV1.substringAfterLast('/')} "

    private fun cond(address: String, operator: String, value: String? = null) = buildJsonObject {
        put("address", address); put("operator", operator); value?.let { put("value", it) }
    }

    private fun groupAction(targetV1: String, body: JsonObject) = buildJsonObject {
        put("address", "$targetV1/action"); put("method", "PUT"); put("body", body)
    }

    private fun onBody(bri: Int, mirek: Int) = buildJsonObject {
        put("on", true)
        put("bri", (bri.coerceIn(1, 100) * 2.54).roundToInt().coerceIn(1, 254))
        put("ct", mirek)
    }

    /** The rules for [m], ready to POST to /rules. */
    fun build(m: MotionSetup): List<RemoteRules.Spec> {
        val p = prefix(m.presenceV1)
        val presence = "${m.presenceV1}/state/presence"
        val motion = buildList {
            add(cond(presence, "eq", "true"))
            add(cond(presence, "dx"))
            if (m.onlyWhenDark && m.lightLevelV1 != null) add(cond("${m.lightLevelV1}/state/dark", "eq", "true"))
        }
        val window = "T${m.nightStart.format(hms)}/T${m.nightEnd.format(hms)}"
        val night = m.nightBrightness
        val out = mutableListOf<RemoteRules.Spec>()
        out += RemoteRules.Spec(
            p + "on",
            JsonArray(motion + listOfNotNull(night?.let { cond("/config/localtime", "not in", window) })),
            JsonArray(listOf(groupAction(m.targetV1, onBody(m.brightness, m.mirek)))),
        )
        if (night != null && night > 0) out += RemoteRules.Spec(
            p + "night",
            JsonArray(motion + cond("/config/localtime", "in", window)),
            // Night light is always the warmest white.
            JsonArray(listOf(groupAction(m.targetV1, onBody(night, 454)))),
        )
        out += RemoteRules.Spec(
            p + "off",
            JsonArray(listOf(
                cond(presence, "eq", "false"),
                cond(presence, "ddx", "PT%02d:%02d:00".format(m.offAfterMinutes / 60, m.offAfterMinutes % 60)),
            )),
            JsonArray(listOf(groupAction(m.targetV1, buildJsonObject { put("on", false); put("transitiontime", 20) }))),
        )
        return out
    }

    /** egHue's rules for [presenceV1] from GET /rules: rule id to rule. */
    fun ownRules(presenceV1: String, rules: JsonObject): Map<String, JsonObject> {
        val p = prefix(presenceV1)
        return rules.entries.mapNotNull { (id, r) -> (r as? JsonObject)?.takeIf { it.str("name")?.startsWith(p) == true }?.let { id to it } }.toMap()
    }

    /** Reads a setup back from egHue's rules, or null if there are none. */
    fun parse(presenceV1: String, lightLevelV1: String?, rules: JsonObject): MotionSetup? {
        val own = ownRules(presenceV1, rules).values
        val p = prefix(presenceV1)
        val on = own.firstOrNull { it.str("name") == p + "on" } ?: return null
        val nightRule = own.firstOrNull { it.str("name") == p + "night" }
        val off = own.firstOrNull { it.str("name") == p + "off" }
        val action = on.arr("actions")?.objects()?.firstOrNull() ?: return null
        val target = action.str("address")?.removeSuffix("/action") ?: return null
        val body = action.obj("body")
        val conds = on.arr("conditions")?.objects().orEmpty()
        val window = conds.firstOrNull { it.str("address") == "/config/localtime" }?.str("value")
        val (start, end) = window?.split('/')?.takeIf { it.size == 2 }
            ?.let { LocalTime.parse(it[0].removePrefix("T")) to LocalTime.parse(it[1].removePrefix("T")) }
            ?: (LocalTime.of(23, 0) to LocalTime.of(7, 0))
        val nightBri = when {
            window == null -> null
            nightRule == null -> 0
            else -> nightRule.arr("actions")?.objects()?.firstOrNull()?.obj("body")?.int("bri")?.let { (it / 2.54).roundToInt() } ?: 20
        }
        val ddx = off?.arr("conditions")?.objects()?.firstOrNull { it.str("operator") == "ddx" }?.str("value")
        val offMin = ddx?.removePrefix("PT")?.let(Automations::parseDuration)?.toMinutes()?.toInt() ?: 5
        return MotionSetup(
            presenceV1 = presenceV1,
            lightLevelV1 = lightLevelV1,
            targetV1 = target,
            brightness = ((body?.int("bri") ?: 254) / 2.54).roundToInt().coerceIn(1, 100),
            mirek = body?.int("ct") ?: 370,
            nightBrightness = nightBri,
            nightStart = start,
            nightEnd = end,
            onlyWhenDark = conds.any { it.str("address")?.endsWith("/state/dark") == true },
            offAfterMinutes = offMin,
        )
    }
}
