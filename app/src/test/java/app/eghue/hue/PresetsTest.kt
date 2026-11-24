package app.eghue.hue

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    private fun light(
        id: String, name: String, archetype: String? = null,
        color: Boolean = true, ct: Boolean = true, dimmable: Boolean = true, mirekMax: Int = 454,
    ) = LightUi(
        id = id, name = name, archetype = archetype, on = false, brightness = 50f, dimmable = dimmable,
        supportsColor = color, supportsCt = ct, mirek = 366, mirekMin = 153, mirekMax = mirekMax,
        xy = null, ctActive = true, reachable = true, color = 0,
    )

    private val ceiling = light("c", "Ceiling", "ceiling_round", color = false)
    private val pendant = light("p", "Dining pendant", "pendant_round", color = false)
    private val floor = light("f", "Floor lamp", "floor_shade")
    private val table = light("t", "Table lamp", "table_shade")
    private val strip = light("s", "TV strip", "hue_lightstrip")
    private val bulb = light("b", "Hue color lamp 1", "sultan_bulb")

    private fun JsonObject.on() = obj("on")?.bool("on")
    private fun JsonObject.bri() = obj("dimming")?.dbl("brightness")
    private fun JsonObject.mirek() = obj("color_temperature")?.int("mirek")
    private fun JsonObject.hasColor() = obj("color") != null

    @Test fun rolesComeFromArchetypeThenName() {
        assertEquals(FixtureRole.CEILING, roleOf(ceiling))
        assertEquals(FixtureRole.PENDANT, roleOf(pendant))
        assertEquals(FixtureRole.LAMP, roleOf(floor))
        assertEquals(FixtureRole.ACCENT, roleOf(strip))
        assertEquals(FixtureRole.LAMP, roleOf(bulb))
        assertEquals(FixtureRole.ACCENT, roleOf(light("x", "Kitchen strip", "classic_bulb")))
        assertEquals(FixtureRole.PENDANT, roleOf(light("x", "Pendant", "sultan_bulb")))
    }

    private fun JsonObject.xy() = obj("color")?.obj("xy")?.let { Xy(it.dbl("x")!!, it.dbl("y")!!) }

    @Test fun candlelitKeepsOverheadOffAndLampsLow() {
        val a = Preset.CANDLELIT.actions(listOf(ceiling, pendant, floor, strip))
        assertEquals(false, a.getValue("c").on())
        assertEquals(15.0, a.getValue("p").bri())
        assertEquals(30.0, a.getValue("f").bri())
        assertTrue("colour lamp gets the flame colour", a.getValue("f").hasColor())
        assertTrue(a.getValue("s").hasColor())
        assertEquals(25.0, a.getValue("s").bri())
    }

    @Test fun readingPutsLampsAtFullNeutralWarmWhite() {
        val a = Preset.READING.actions(listOf(ceiling, floor, strip))
        assertEquals(100.0, a.getValue("f").bri())
        assertEquals(344, a.getValue("f").mirek())
        assertFalse(a.getValue("f").hasColor())
        assertEquals(65.0, a.getValue("c").bri())
        assertEquals(35.0, a.getValue("s").bri())
        assertFalse("reading accents stay white", a.getValue("s").hasColor())
    }

    @Test fun neonGivesEveryColourLightADifferentColour() {
        val bulbs = (1..4).map { light("n$it", "Bulb $it", "sultan_bulb") }
        val a = Preset.NEON.actions(bulbs + ceiling)
        val colours = bulbs.map { a.getValue(it.id).xy() }
        assertEquals(4, colours.toSet().size)
        // The white-only ceiling stays low and warm.
        assertEquals(25.0, a.getValue("c").bri())
        assertEquals(454, a.getValue("c").mirek())
    }

    @Test fun loungeAddsADuskAccentToWarmLamps() {
        val a = Preset.LOUNGE.actions(listOf(ceiling, floor, strip))
        assertEquals(false, a.getValue("c").on())
        assertEquals(45.0, a.getValue("f").bri())
        val dusk = a.getValue("s").xy()!!
        assertTrue("dusk accent is violet, not amber", dusk.x < 0.4)
    }

    @Test fun onlyNeonUsesCoolColours() {
        val room = listOf(ceiling, pendant, floor, table, strip)
        for (p in Preset.entries) for ((_, action) in p.actions(room)) {
            action.mirek()?.let { assertTrue("$p mirek $it", it >= 333) }
        }
    }

    @Test fun sameRoleLightsStepDownInSoftLooks() {
        val a = Preset.LOUNGE.actions(listOf(floor, table, ceiling))
        assertEquals(45.0, a.getValue("f").bri())
        assertEquals(38.25, a.getValue("t").bri()!!, 0.11)
        val r = Preset.READING.actions(listOf(floor, table))
        assertEquals(100.0, r.getValue("t").bri())
    }

    @Test fun ceilingOnlyRoomIsNeverDark() {
        for (p in Preset.entries) {
            val a = Preset.valueOf(p.name).actions(listOf(ceiling))
            assertEquals("$p", true, a.getValue("c").on())
        }
    }

    @Test fun whiteOnlyAccentsGetAWarmWhiteInsteadOfColour() {
        val whiteStrip = light("w", "Shelf strip", "hue_lightstrip", color = false, mirekMax = 454)
        val a = Preset.CANDLELIT.actions(listOf(whiteStrip, floor))
        assertFalse(a.getValue("w").hasColor())
        assertEquals(454, a.getValue("w").mirek())
    }

    @Test fun colourTemperatureIsClampedToTheLight() {
        val a = Preset.CANDLELIT.actions(listOf(light("x", "Lamp", "table_shade", color = false, mirekMax = 454)))
        assertEquals(454, a.getValue("x").mirek())
    }

    @Test fun dimOnlyLightsGetBrightnessAlone() {
        val a = Preset.READING.actions(listOf(light("d", "Lamp", "table_shade", color = false, ct = false)))
        assertEquals(100.0, a.getValue("d").bri())
        assertFalse(a.getValue("d").hasColor())
        assertNull(a.getValue("d").mirek())
    }

    @Test fun appdataFitsAndRoundTrips() {
        for (p in Preset.entries) {
            assertTrue(p.appdata.length <= 16)
            assertEquals(p, Preset.fromAppdata(p.appdata))
        }
        assertEquals(Preset.NEON, Preset.fromAppdata("eghue:neon:0"))
        assertNull(Preset.fromAppdata("something:else"))
        assertTrue(Preset.isRetired("eghue:evening:1"))
        assertFalse(Preset.isRetired("eghue:lounge:1"))
        assertFalse(Preset.isRetired("eghue:my:1"))
        assertFalse(Preset.isRetired(null))
    }

    @Test fun savedPresetsAreRecognisedInHomeState() {
        val resources = HueJson.parseToJsonElement(
            """
            [{"id":"s9","type":"scene","metadata":{"name":"Lounge","appdata":"eghue:lounge:1"},"group":{"rid":"r1","rtype":"room"},
              "status":{"active":"static"},"actions":[{"target":{"rid":"l1","rtype":"light"},"action":{"on":{"on":true}}}]},
             {"id":"s1","type":"scene","metadata":{"name":"Relax"},"group":{"rid":"r1","rtype":"room"},"actions":[]}]
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonArray }.objects()
        val scenes = deriveHome(resources).scenesFor("r1")
        val saved = scenes.first { it.id == "s9" }
        assertEquals(Preset.LOUNGE, saved.preset)
        assertEquals(setOf("l1"), saved.targets)
        assertNull(scenes.first { it.id == "s1" }.preset)
    }
}
