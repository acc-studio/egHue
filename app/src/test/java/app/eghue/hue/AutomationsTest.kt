package app.eghue.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class AutomationsTest {
    private val weekdays = setOf(MONDAY, TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, FRIDAY)
    private val names: (String) -> String? = { if (it == "/groups/3") "Bedroom" else null }

    private fun roundTrip(spec: AutoSpec, extra: Map<String, String> = emptyMap()): Automation {
        var json = Automations.toJson(spec, "KEY", LocalDateTime.of(2026, 11, 24, 22, 0))
        if (extra.isNotEmpty()) json = deepMerge(json, kotlinx.serialization.json.buildJsonObject {
            extra.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        })
        return Automations.parse("1", json, names)
    }

    @Test fun wakeUpStartsEarlySoLightsAreUpOnTime() {
        val spec = AutoSpec(AutoKind.WAKE, "/groups/3", "Bedroom", AutoAction.On(100, 370), AutoWhen.Daily(LocalTime.of(7, 0), weekdays), 30)
        val json = Automations.toJson(spec, "KEY")
        assertEquals("W124/T06:30:00", json.str("localtime"))
        val cmd = json.obj("command")!!
        assertEquals("/api/KEY/groups/3/action", cmd.str("address"))
        assertEquals(18000, cmd.obj("body")?.int("transitiontime"))
        assertEquals(254, cmd.obj("body")?.int("bri"))
        assertEquals(spec, roundTrip(spec).spec)
        assertEquals(AutoKind.WAKE, roundTrip(spec).kind)
    }

    @Test fun earlyWakeUpWrapsToThePreviousDay() {
        val spec = AutoSpec(AutoKind.WAKE, "/groups/3", "Bedroom", AutoAction.On(80, 370), AutoWhen.Daily(LocalTime.of(0, 10), setOf(MONDAY)), 20)
        // Starts Sunday 23:50.
        assertEquals("W001/T23:50:00", Automations.toJson(spec, "KEY").str("localtime"))
        assertEquals(spec, roundTrip(spec).spec)
    }

    @Test fun bedtimeFadesOut() {
        val spec = AutoSpec(AutoKind.BEDTIME, "/groups/3", "Bedroom", AutoAction.Off, AutoWhen.Daily(LocalTime.of(23, 30), setOf(SATURDAY, SUNDAY)), 10)
        val json = Automations.toJson(spec, "KEY")
        assertEquals("W003/T23:30:00", json.str("localtime"))
        assertEquals(false, json.obj("command")?.obj("body")?.bool("on"))
        assertEquals(spec, roundTrip(spec).spec)
    }

    @Test fun oneOffRunsAtTheNextOccurrence() {
        val spec = AutoSpec(AutoKind.AT_TIME, "/groups/3", "Bedroom", AutoAction.Scene("abc"), AutoWhen.Daily(LocalTime.of(7, 0), emptySet()))
        val json = Automations.toJson(spec, "KEY", LocalDateTime.of(2026, 11, 24, 22, 0))
        assertEquals("2026-11-25T07:00:00", json.str("localtime"))
        assertEquals("true", json["autodelete"].toString())
        assertEquals(spec, roundTrip(spec).spec)
    }

    @Test fun timerCountsDownFromItsStart() {
        val spec = AutoSpec(AutoKind.TIMER, "/groups/3", "Bedroom", AutoAction.Off, AutoWhen.In(90), 1)
        val json = Automations.toJson(spec, "KEY")
        assertEquals("PT01:30:00", json.str("localtime"))
        val a = roundTrip(spec, mapOf("starttime" to "2026-11-24T21:00:00"))
        assertEquals(Instant.parse("2026-11-24T22:30:00Z"), a.endsAt)
        assertEquals(spec, a.spec)
    }

    @Test fun othersSchedulesAreShownButNotUnderstood() {
        val o = HueJson.parseToJsonElement(
            """{"name":"Hue thing","localtime":"W127/T08:00:00","command":{"address":"/api/K/sensors/5/state","method":"PUT","body":{"status":1}}}"""
        ) as kotlinx.serialization.json.JsonObject
        val a = Automations.parse("9", o, names)
        assertEquals(AutoKind.OTHER, a.kind)
        assertNull(a.spec)
    }

    @Test fun namesFitTheBridgeLimit() {
        val spec = AutoSpec(AutoKind.WAKE, "/groups/3", "A very long room name indeed", AutoAction.On(), AutoWhen.Daily(LocalTime.NOON, weekdays), 10)
        assertEquals(32, Automations.toJson(spec, "K").str("name")!!.length)
    }

    @Test fun daysDescribeNaturally() {
        assertEquals("Weekdays", Automations.describeDays(weekdays))
        assertEquals("Weekends", Automations.describeDays(setOf(SATURDAY, SUNDAY)))
        assertEquals("Mon, Fri", Automations.describeDays(setOf(FRIDAY, MONDAY)))
        assertEquals("Once", Automations.describeDays(emptySet()))
    }

    @Test fun adjustChangesOnlyLightsThatAreOn() {
        val spec = AutoSpec(AutoKind.AT_TIME, "/groups/3", "Bedroom", AutoAction.Adjust(65, 416), AutoWhen.Daily(LocalTime.of(20, 0), weekdays), 10)
        val body = Automations.toJson(spec, "KEY").obj("command")!!.obj("body")!!
        assertNull("must not switch lights on", body["on"])
        assertEquals(416, body.int("ct"))
        assertEquals(spec, roundTrip(spec).spec)
    }

    @Test fun naturalLightGetsWarmerThroughTheDay() {
        val specs = Automations.naturalLightSpecs("/groups/0", "Whole home")
        assertEquals(5, specs.size)
        val mireks = specs.map { (it.action as AutoAction.Adjust).mirek }
        assertEquals(mireks.drop(1), mireks.drop(1).sorted())
        assertEquals(AutoKind.NATURAL, specs.first().kind)
        specs.forEach { assertEquals("W127/T", Automations.toJson(it, "K").str("localtime")!!.take(6)) }
    }
}
