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
        "strip" in n || "play" in n || "bar" in n -> FixtureRole.ACCENT
        // Plain bulbs with no hint: most bulbs sit in lamps.
        else -> FixtureRole.LAMP
    }
}

/** A light setting: a colour temperature, or an xy colour for lights that can show one. */
data class Tone(val mirek: Int, val xy: Xy? = null)

/** One light's setting in a preset; null brightness means off. */
data class Look(val tone: Tone, val brightness: Float?)

private fun white(kelvin: Int) = Tone(1_000_000 / kelvin)
private fun colour(x: Double, y: Double, fallbackKelvin: Int = 2200) = Tone(1_000_000 / fallbackKelvin, Xy(x, y))

// Candlelit
private val flame = colour(0.585, 0.395, 2000)
private val rose = colour(0.600, 0.330, 2000)
// Lounge
private val honey = colour(0.555, 0.405, 2200)
private val dusk = colour(0.300, 0.170, 2200)
// Neon: neighbours in this list clash on purpose.
private val neonPalette = listOf(
    colour(0.400, 0.160), // magenta
    colour(0.165, 0.360), // teal
    colour(0.610, 0.370), // tangerine
    colour(0.210, 0.090), // violet
    colour(0.480, 0.250), // hot pink
    colour(0.400, 0.520), // chartreuse
)

/**
 * egHue's built-in looks. Each is designed per fixture: what a ceiling light, a pendant, a lamp and
 * an accent (strip, bar, bloom) should do for that mood, with fallbacks for rooms that lack a role.
 */
enum class Preset(val title: String, private val key: String) {
    /** Romantic: no overhead light, low flame-coloured lamps, a rose glow behind things. */
    CANDLELIT("Candlelit", "candle"),
    /** Reading: lamps bright and neutral-warm (never blue), overhead fill, accents quiet. */
    READING("Reading", "read"),
    /** Fun: every colour light gets a different, deliberately clashing saturated colour. */
    NEON("Neon", "neon"),
    /** Chill: warm honey lamps, a dusky violet accent glow, overhead lights off. */
    LOUNGE("Lounge", "lounge");

    /** Tag stored in the scene's `metadata.appdata` (max 16 chars). Bump [VERSION] when looks change. */
    val appdata: String get() = "$TAG$key:$VERSION"

    /** The design for one role (colour lights in [NEON] get their colour from the palette instead). */
    private fun design(role: FixtureRole): Look = when (this) {
        CANDLELIT -> when (role) {
            FixtureRole.CEILING -> Look(white(2000), null)
            FixtureRole.PENDANT -> Look(white(2000), 15f)
            FixtureRole.LAMP -> Look(flame, 30f)
            FixtureRole.ACCENT -> Look(rose, 25f)
        }
        READING -> when (role) {
            FixtureRole.CEILING -> Look(white(2700), 65f)
            FixtureRole.PENDANT -> Look(white(2900), 80f)
            FixtureRole.LAMP -> Look(white(2900), 100f)
            FixtureRole.ACCENT -> Look(white(2700), 35f)
        }
        NEON -> when (role) {
            FixtureRole.CEILING -> Look(white(2200), 45f)
            FixtureRole.PENDANT -> Look(white(2200), 60f)
            FixtureRole.LAMP -> Look(white(2200), 75f)
            FixtureRole.ACCENT -> Look(white(2200), 90f)
        }
        LOUNGE -> when (role) {
            FixtureRole.CEILING -> Look(white(2200), null)
            FixtureRole.PENDANT -> Look(white(2200), 25f)
            FixtureRole.LAMP -> Look(honey, 45f)
            FixtureRole.ACCENT -> Look(dusk, 30f)
        }
    }

    /** What each light in a room gets: light id to Hue scene action. */
    fun actions(lights: List<LightUi>): Map<String, JsonObject> =
        plan(lights).mapValues { (id, look) -> actionFor(lights.first { it.id == id }, look) }

    /** Display colours for a preset tile in this room. */
    fun previewColors(lights: List<LightUi>): List<Int> {
        val planned = plan(lights)
        val looks = lights.mapNotNull { l -> planned[l.id]?.takeIf { it.brightness != null }?.let { l to it } }
        val colours = looks.map { (l, look) ->
            val xy = look.tone.xy
            if (xy != null && l.supportsColor) ColorUtil.xyToRgb(xy) else ColorUtil.mirekToRgb(look.tone.mirek)
        }.distinct()
        return colours.ifEmpty { listOf(ColorUtil.mirekToRgb(design(FixtureRole.LAMP).tone.mirek)) }.take(4)
    }

    private fun plan(lights: List<LightUi>): Map<String, Look> {
        val sorted = lights.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val roles = sorted.associate { it.id to roleOf(it) }
        val hasLamp = roles.values.any { it == FixtureRole.LAMP }
        val seen = HashMap<FixtureRole, Int>()
        var colourIndex = 0
        val plan = LinkedHashMap<String, Look>()
        for (light in sorted) {
            val role = roles.getValue(light.id)
            var look = design(role)
            // No lamps: overhead lights stand in for them, so the room is never dark.
            if (!hasLamp && (role == FixtureRole.CEILING || role == FixtureRole.PENDANT)) look = design(FixtureRole.LAMP)
            if (this == NEON) {
                if (light.supportsColor) {
                    look = look.copy(tone = neonPalette[colourIndex % neonPalette.size])
                    colourIndex++
                } else {
                    // White-only lights stay low and warm so they don't wash the colours out.
                    look = Look(white(2200), 25f)
                }
            }
            // In the softer looks, several lights in the same role step down so the room isn't flat.
            val i = seen.merge(role, 1, Int::plus)!! - 1
            val step = if (this == CANDLELIT || this == LOUNGE) STEPS[i % STEPS.size] else 1f
            plan[light.id] = look.copy(brightness = look.brightness?.let { (it * step).coerceAtLeast(1f) })
        }
        // Never leave a room completely dark.
        if (plan.isNotEmpty() && plan.values.all { it.brightness == null }) {
            val first = sorted.first().id
            plan[first] = design(FixtureRole.LAMP)
        }
        return plan
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
                put("mirek", look.tone.mirek.coerceIn(light.mirekMin, light.mirekMax))
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
            return entries.firstOrNull { it.key == key }
        }

        /** A scene egHue saved for a preset it no longer has (e.g. the first-generation "Evening"). */
        fun isRetired(appdata: String?): Boolean =
            appdata != null && appdata.startsWith(TAG) && !appdata.startsWith(SCENE_TAG) && fromAppdata(appdata) == null

        /** Tag for scenes people make in egHue (not presets). */
        const val SCENE_TAG = "eghue:my:"
    }
}
