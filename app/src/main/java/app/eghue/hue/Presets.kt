package app.eghue.hue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** What a fixture is, for lighting design purposes. */
enum class FixtureRole { CEILING, PENDANT, LAMP, ACCENT }

private val ceilingArchetypes = setOf("recessed_ceiling", "single_spot", "double_spot", "hue_centris")
private val lampArchetypes = setOf("wall_shade", "wall_lantern", "flexible_lamp", "hue_go")
private val accentArchetypes = setOf(
    "hue_play", "hue_iris", "hue_bloom", "hue_signe", "hue_tube", "string_light", "wall_washer",
)

/** A light's role from its archetype (the fixture type chosen in the Hue app), then its name. */
fun roleOf(light: LightUi): FixtureRole {
    val a = light.archetype.orEmpty()
    when {
        a.startsWith("pendant") -> return FixtureRole.PENDANT
        a.startsWith("ceiling") || a in ceilingArchetypes -> return FixtureRole.CEILING
        a.startsWith("hue_lightstrip") || a.startsWith("hue_gradient") || a in accentArchetypes -> return FixtureRole.ACCENT
        a.startsWith("floor") || a.startsWith("table") || a in lampArchetypes -> return FixtureRole.LAMP
    }
    val n = light.name.lowercase()
    return when {
        "pendant" in n -> FixtureRole.PENDANT
        "ceiling" in n || "spot" in n -> FixtureRole.CEILING
        "strip" in n -> FixtureRole.ACCENT
        // Plain bulbs with no hint: most bulbs sit in lamps.
        else -> FixtureRole.LAMP
    }
}

/** A warm light setting: a colour temperature, or an xy colour for lights that can show one. */
data class Tone(val mirek: Int, val xy: Xy? = null)

/** One role's setting in a preset; null brightness means off. */
data class Look(val tone: Tone, val brightness: Float?)

private val k2700 = Tone(370)
private val k2400 = Tone(417)
private val k2200 = Tone(455)
private val k2000 = Tone(500)
private val amber = Tone(500, Xy(0.565, 0.405))
private val deepAmber = Tone(500, Xy(0.595, 0.385))
private val ember = Tone(500, Xy(0.62, 0.36))

enum class Preset(val title: String, private val looks: Map<FixtureRole, Look>) {
    EVENING("Evening", mapOf(
        FixtureRole.CEILING to Look(k2700, 45f),
        FixtureRole.PENDANT to Look(k2700, 55f),
        FixtureRole.LAMP to Look(k2400, 75f),
        FixtureRole.ACCENT to Look(amber, 60f),
    )),
    UNWIND("Unwind", mapOf(
        FixtureRole.CEILING to Look(k2200, 15f),
        FixtureRole.PENDANT to Look(k2200, 25f),
        FixtureRole.LAMP to Look(k2200, 45f),
        FixtureRole.ACCENT to Look(deepAmber, 35f),
    )),
    DINNER("Dinner", mapOf(
        FixtureRole.CEILING to Look(k2700, 40f),
        FixtureRole.PENDANT to Look(k2700, 80f),
        FixtureRole.LAMP to Look(k2200, 30f),
        FixtureRole.ACCENT to Look(ember, 25f),
    )),
    LATE("Late", mapOf(
        FixtureRole.CEILING to Look(k2000, null),
        FixtureRole.PENDANT to Look(k2000, null),
        // Only the first lamp; see [lookFor].
        FixtureRole.LAMP to Look(k2000, 10f),
        FixtureRole.ACCENT to Look(ember, 5f),
    ));

    /** Tag stored in the scene's `metadata.appdata` (max 16 chars). Bump [VERSION] when looks change. */
    val appdata: String get() = "$TAG${name.lowercase()}:$VERSION"

    /** What each light in a room gets: light id to Hue scene action. */
    fun actions(lights: List<LightUi>): Map<String, JsonObject> =
        plan(lights).mapValues { (id, look) -> actionFor(lights.first { it.id == id }, look) }

    /** Display colours for a preset tile in this room. */
    fun previewColors(lights: List<LightUi>): List<Int> {
        val looks = plan(lights).values.filter { it.brightness != null }.ifEmpty { looks.values.toList() }
        return looks.map { it.tone.xy?.let(ColorUtil::xyToRgb) ?: ColorUtil.mirekToRgb(it.tone.mirek) }.distinct().take(4)
    }

    private fun plan(lights: List<LightUi>): Map<String, Look> {
        val sorted = lights.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val roles = sorted.associate { it.id to roleOf(it) }
        val hasLamp = roles.values.any { it == FixtureRole.LAMP }
        val hasPendant = roles.values.any { it == FixtureRole.PENDANT }
        // In Late, one light stays on as the "lamp": the first lamp, or the first light if there are none.
        val lateLight = sorted.firstOrNull { roles[it.id] == FixtureRole.LAMP }?.id ?: sorted.firstOrNull()?.id
        val seen = HashMap<FixtureRole, Int>()
        return sorted.associate { light ->
            val role = roles.getValue(light.id)
            var look = looks.getValue(role)
            if (this == LATE) {
                look = if (light.id == lateLight) looks.getValue(FixtureRole.LAMP)
                else if (role == FixtureRole.ACCENT) look else Look(look.tone, null)
            } else if (!hasLamp && (role == FixtureRole.CEILING || role == FixtureRole.PENDANT)) {
                // No lamps: overhead lights carry the room.
                look = looks.getValue(FixtureRole.LAMP)
            } else if (this == DINNER && !hasPendant && role == FixtureRole.CEILING) {
                look = look.copy(brightness = 60f)
            }
            // Several lights in the same role step down a little so the room isn't flat.
            val i = seen.merge(role, 1, Int::plus)!! - 1
            val step = STEPS[i % STEPS.size]
            light.id to look.copy(brightness = look.brightness?.let { (it * step).coerceAtLeast(1f) })
        }
    }

    private fun actionFor(light: LightUi, look: Look): JsonObject = buildJsonObject {
        val bri = look.brightness
        putJsonObject("on") { put("on", bri != null) }
        if (bri == null) return@buildJsonObject
        if (light.dimmable) putJsonObject("dimming") { put("brightness", (bri * 10).roundToInt() / 10.0) }
        val xy = look.tone.xy
        when {
            xy != null && light.supportsColor -> putJsonObject("color") {
                putJsonObject("xy") { put("x", xy.x); put("y", xy.y) }
            }
            light.supportsCt -> putJsonObject("color_temperature") {
                // Colour accents that can't show colour get their warmest white.
                val m = if (xy != null) light.mirekMax else look.tone.mirek
                put("mirek", m.coerceIn(light.mirekMin, light.mirekMax))
            }
            xy == null && light.supportsColor -> putJsonObject("color") {
                // Colour-only lights: show the colour temperature as a colour.
                val c = ColorUtil.rgbToXy(ColorUtil.mirekToRgb(look.tone.mirek))
                putJsonObject("xy") { put("x", c.x); put("y", c.y) }
            }
        }
    }

    companion object {
        const val TAG = "eghue:"
        const val VERSION = 1
        private val STEPS = floatArrayOf(1f, 0.85f, 0.7f)

        /** The preset a scene's appdata names, if it's one of ours (any version). */
        fun fromAppdata(appdata: String?): Preset? {
            if (appdata == null || !appdata.startsWith(TAG)) return null
            val key = appdata.removePrefix(TAG).substringBefore(':')
            return entries.firstOrNull { it.name.lowercase() == key }
        }
    }
}
