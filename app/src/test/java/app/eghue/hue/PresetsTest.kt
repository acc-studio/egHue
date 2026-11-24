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

    @Test fun eveningGivesEachFixtureItsOwnLight() {
        val a = Preset.EVENING.actions(listOf(ceiling, floor, strip))
        assertEquals(45.0, a.getValue("c").bri())
        assertEquals(370, a.getValue("c").mirek())
        assertEquals(75.0, a.getValue("f").bri())
        assertEquals(417, a.getValue("f").mirek())
        assertTrue(a.getValue("s").hasColor())
        assertNull(a.getValue("s").mirek())
        assertEquals(60.0, a.getValue("s").bri())
    }

    @Test fun nothingIsCoolWhite() {
        val room = listOf(ceiling, pendant, floor, table, strip)
        for (p in Preset.entries) for ((_, action) in p.actions(room)) {
            action.mirek()?.let { assertTrue("$p mirek $it", it >= 370) }
        }
    }

    @Test fun sameRoleLightsStepDown() {
        val a = Preset.EVENING.actions(listOf(floor, table, ceiling))
        assertEquals(75.0, a.getValue("f").bri())
        assertEquals(63.75, a.getValue("t").bri()!!, 0.11)
    }

    @Test fun ceilingOnlyRoomStillGetsALitEvening() {
        val a = Preset.EVENING.actions(listOf(ceiling))
        assertEquals(75.0, a.getValue("c").bri())
    }

    @Test fun dinnerWithoutPendantLiftsTheCeiling() {
        val a = Preset.DINNER.actions(listOf(ceiling, floor))
        assertEquals(60.0, a.getValue("c").bri())
        val withPendant = Preset.DINNER.actions(listOf(ceiling, pendant, floor))
        assertEquals(40.0, withPendant.getValue("c").bri())
        assertEquals(80.0, withPendant.getValue("p").bri())
    }

    @Test fun lateKeepsOneLampAndTheAccents() {
        val a = Preset.LATE.actions(listOf(ceiling, floor, table, strip))
        assertEquals(false, a.getValue("c").on())
        assertEquals(10.0, a.getValue("f").bri())
        assertEquals(false, a.getValue("t").on())
        assertEquals(5.0, a.getValue("s").bri())
        // No lamps: the first light by name stays on.
        val b = Preset.LATE.actions(listOf(pendant, ceiling))
        assertEquals(true, b.getValue("c").on())
        assertEquals(false, b.getValue("p").on())
    }

    @Test fun whiteOnlyAccentsGetTheirWarmestWhite() {
        val whiteStrip = light("w", "Shelf strip", "hue_lightstrip", color = false, mirekMax = 454)
        val a = Preset.EVENING.actions(listOf(whiteStrip))
        assertFalse(a.getValue("w").hasColor())
        assertEquals(454, a.getValue("w").mirek())
    }

    @Test fun colourTemperatureIsClampedToTheLight() {
        val a = Preset.LATE.actions(listOf(light("x", "Lamp", "table_shade", mirekMax = 454)))
        assertEquals(454, a.getValue("x").mirek())
    }

    @Test fun dimOnlyLightsGetBrightnessAlone() {
        val a = Preset.EVENING.actions(listOf(light("d", "Lamp", "table_shade", color = false, ct = false)))
        assertEquals(75.0, a.getValue("d").bri())
        assertFalse(a.getValue("d").hasColor())
        assertNull(a.getValue("d").mirek())
    }

    @Test fun appdataFitsAndRoundTrips() {
        for (p in Preset.entries) {
            assertTrue(p.appdata.length <= 16)
            assertEquals(p, Preset.fromAppdata(p.appdata))
        }
        assertEquals(Preset.LATE, Preset.fromAppdata("eghue:late:0"))
        assertNull(Preset.fromAppdata("something:else"))
    }

    @Test fun savedPresetsAreRecognisedInHomeState() {
        val resources = HueJson.parseToJsonElement(
            """
            [{"id":"s9","type":"scene","metadata":{"name":"Evening","appdata":"eghue:evening:1"},"group":{"rid":"r1","rtype":"room"},
              "status":{"active":"static"},"actions":[{"target":{"rid":"l1","rtype":"light"},"action":{"on":{"on":true}}}]},
             {"id":"s1","type":"scene","metadata":{"name":"Relax"},"group":{"rid":"r1","rtype":"room"},"actions":[]}]
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonArray }.objects()
        val scenes = deriveHome(resources).scenesFor("r1")
        val saved = scenes.first { it.id == "s9" }
        assertEquals(Preset.EVENING, saved.preset)
        assertEquals(setOf("l1"), saved.targets)
        assertNull(scenes.first { it.id == "s1" }.preset)
    }
}
