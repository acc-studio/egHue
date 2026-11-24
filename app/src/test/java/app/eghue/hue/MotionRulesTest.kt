package app.eghue.hue

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class MotionRulesTest {
    private val setup = MotionSetup(
        presenceV1 = "/sensors/5", lightLevelV1 = "/sensors/6", targetV1 = "/groups/3",
        brightness = 80, mirek = 370, nightBrightness = 15, nightStart = LocalTime.of(23, 0), nightEnd = LocalTime.of(6, 30),
        onlyWhenDark = true, offAfterMinutes = 3,
    )

    private fun asRules(specs: List<RemoteRules.Spec>) = JsonObject(specs.mapIndexed { i, s -> (i + 1).toString() to s.toJson() }.toMap())

    @Test fun buildsDayNightAndOffRules() {
        val specs = MotionRules.build(setup)
        assertEquals(listOf("egHue motion 5 on", "egHue motion 5 night", "egHue motion 5 off"), specs.map { it.name })
        val day = specs[0]
        assertEquals(4, day.conditions.size) // presence, dx, dark, not-in-night
        assertEquals(203, day.actions.objects()[0].obj("body")?.int("bri"))
        val night = specs[1]
        assertEquals("T23:00:00/T06:30:00", night.conditions.objects().last().str("value"))
        assertEquals(454, night.actions.objects()[0].obj("body")?.int("ct"))
        assertEquals("PT00:03:00", specs[2].conditions.objects()[1].str("value"))
        specs.forEach { assert(it.name.length <= 32 && it.conditions.size <= 8 && it.actions.size <= 8) }
    }

    @Test fun roundTripsThroughTheBridgeRules() {
        assertEquals(setup, MotionRules.parse("/sensors/5", "/sensors/6", asRules(MotionRules.build(setup))))
    }

    @Test fun nightOffMeansNoNightRule() {
        val s = setup.copy(nightBrightness = 0, onlyWhenDark = false)
        val specs = MotionRules.build(s)
        assertEquals(2, specs.size)
        assertEquals(s, MotionRules.parse("/sensors/5", "/sensors/6", asRules(specs)))
    }

    @Test fun noNightModeHasNoTimeCondition() {
        val s = setup.copy(nightBrightness = null)
        val specs = MotionRules.build(s)
        assertEquals(3, specs[0].conditions.size)
        assertEquals(s.copy(nightStart = LocalTime.of(23, 0), nightEnd = LocalTime.of(7, 0)), MotionRules.parse("/sensors/5", "/sensors/6", asRules(specs)))
    }

    @Test fun otherSensorsRulesAreIgnored() {
        assertNull(MotionRules.parse("/sensors/9", null, asRules(MotionRules.build(setup))))
    }
}
