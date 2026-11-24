package app.eghue.hue

import app.eghue.update.Versions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class JsonMergeTest {
    @Test fun mergesNestedObjectsAndReplacesLeaves() {
        val base = HueJson.parseToJsonElement("""{"id":"a","on":{"on":false},"dimming":{"brightness":20,"min_dim_level":2}}""").jsonObject
        val patch = HueJson.parseToJsonElement("""{"on":{"on":true},"dimming":{"brightness":80}}""").jsonObject
        val m = deepMerge(base, patch)
        assertEquals(true, m.obj("on")?.bool("on"))
        assertEquals(80.0, m.obj("dimming")?.dbl("brightness"))
        assertEquals(2.0, m.obj("dimming")?.dbl("min_dim_level"))
        assertEquals("a", m.str("id"))
    }

    @Test fun memberPatchKeepsOnlyLightState() {
        val body = HueJson.parseToJsonElement("""{"on":{"on":true},"recall":{"action":"active"},"dimming":{"brightness":5}}""").jsonObject
        assertEquals(setOf("on", "dimming"), memberPatch(body).keys)
    }
}

class DeriveHomeTest {
    private val resources: List<JsonObject> = HueJson.parseToJsonElement(
        """
        [
          {"id":"dev1","type":"device","metadata":{"name":"Sofa lamp","archetype":"sultan_bulb"},"services":[{"rid":"l1","rtype":"light"},{"rid":"z1","rtype":"zigbee_connectivity"}]},
          {"id":"dev2","type":"device","metadata":{"name":"Ceiling","archetype":"ceiling_round"},"services":[{"rid":"l2","rtype":"light"},{"rid":"z2","rtype":"zigbee_connectivity"}]},
          {"id":"dev3","type":"device","metadata":{"name":"Strip"},"services":[{"rid":"l3","rtype":"light"}]},
          {"id":"z1","type":"zigbee_connectivity","owner":{"rid":"dev1","rtype":"device"},"status":"connected"},
          {"id":"z2","type":"zigbee_connectivity","owner":{"rid":"dev2","rtype":"device"},"status":"connectivity_issue"},
          {"id":"l1","type":"light","owner":{"rid":"dev1","rtype":"device"},"metadata":{"name":"Sofa lamp"},"on":{"on":true},"dimming":{"brightness":60},
            "color_temperature":{"mirek":366,"mirek_valid":true,"mirek_schema":{"mirek_minimum":153,"mirek_maximum":500}},"color":{"xy":{"x":0.45,"y":0.41}}},
          {"id":"l2","type":"light","owner":{"rid":"dev2","rtype":"device"},"metadata":{"name":"Ceiling"},"on":{"on":false},"dimming":{"brightness":100}},
          {"id":"l3","type":"light","owner":{"rid":"dev3","rtype":"device"},"metadata":{"name":"Strip"},"on":{"on":true},"dimming":{"brightness":30},
            "color_temperature":{"mirek":null,"mirek_valid":false},"color":{"xy":{"x":0.68,"y":0.31}}},
          {"id":"r1","type":"room","metadata":{"name":"Living room","archetype":"living_room"},"children":[{"rid":"dev1","rtype":"device"},{"rid":"dev2","rtype":"device"}],"services":[{"rid":"gl1","rtype":"grouped_light"}]},
          {"id":"zone1","type":"zone","metadata":{"name":"Movie","archetype":"tv"},"children":[{"rid":"l1","rtype":"light"},{"rid":"l3","rtype":"light"}],"services":[{"rid":"gl2","rtype":"grouped_light"}]},
          {"id":"gl1","type":"grouped_light","owner":{"rid":"r1","rtype":"room"},"on":{"on":true},"dimming":{"brightness":60}},
          {"id":"gl2","type":"grouped_light","owner":{"rid":"zone1","rtype":"zone"},"on":{"on":true},"dimming":{"brightness":45}},
          {"id":"home","type":"bridge_home","services":[{"rid":"gl0","rtype":"grouped_light"}]},
          {"id":"s1","type":"scene","metadata":{"name":"Relax"},"group":{"rid":"r1","rtype":"room"},"status":{"active":"static"},
            "actions":[{"target":{"rid":"l1","rtype":"light"},"action":{"on":{"on":true},"color_temperature":{"mirek":447}}}]},
          {"id":"s2","type":"scene","metadata":{"name":"Arctic"},"group":{"rid":"r1","rtype":"room"},"status":{"active":"inactive"},
            "actions":[{"target":{"rid":"l1","rtype":"light"},"action":{"on":{"on":true},"color":{"xy":{"x":0.15,"y":0.06}}}}]}
        ]
        """.trimIndent()
    ).jsonArray.objects()

    private val home = deriveHome(resources)

    @Test fun roomsExpandDevicesToLights() {
        val room = home.group("r1")!!
        assertEquals(listOf("l1", "l2"), room.lightIds)
        assertEquals("gl1", room.groupedLightId)
        assertTrue(room.on)
        assertEquals(1, room.lightsOn)
        assertEquals(60f, room.brightness)
        assertEquals(1, room.reachableLights)
    }

    @Test fun zonesUseLightsDirectly() {
        val zone = home.group("zone1")!!
        assertEquals(GroupKind.ZONE, zone.kind)
        assertEquals(listOf("l1", "l3"), zone.lightIds)
        assertEquals(2, zone.lightsOn)
    }

    @Test fun lightsAreSortedAndCarryCapabilities() {
        assertEquals(listOf("Ceiling", "Sofa lamp", "Strip"), home.lights.map { it.name })
        val sofa = home.light("l1")!!
        assertTrue(sofa.ctActive); assertTrue(sofa.supportsColor); assertTrue(sofa.dimmable)
        val strip = home.light("l3")!!
        assertFalse(strip.ctActive)
        // Strip is red: red channel dominates.
        assertTrue(((strip.color shr 16) and 0xFF) > ((strip.color shr 8) and 0xFF))
        assertFalse(home.light("l2")!!.reachable)
    }

    @Test fun scenesAttachToGroupWithActiveState() {
        val scenes = home.scenesFor("r1")
        assertEquals(listOf("Arctic", "Relax"), scenes.map { it.name })
        assertTrue(scenes.first { it.name == "Relax" }.active)
        assertFalse(scenes.first { it.name == "Arctic" }.active)
        assertEquals("gl0", home.homeGroupedLightId)
    }
}

class ColorUtilTest {
    @Test fun xyRoundTripsThroughRgb() {
        for (xy in listOf(Xy(0.68, 0.31), Xy(0.17, 0.7), Xy(0.15, 0.06), Xy(0.3227, 0.329))) {
            val back = ColorUtil.rgbToXy(ColorUtil.xyToRgb(xy))
            assertTrue("x for $xy was ${back.x}", abs(back.x - xy.x) < 0.03)
            assertTrue("y for $xy was ${back.y}", abs(back.y - xy.y) < 0.03)
        }
    }

    @Test fun warmIsWarmerThanCool() {
        val warm = ColorUtil.mirekToRgb(454); val cool = ColorUtil.mirekToRgb(153)
        assertTrue((warm and 0xFF) < (cool and 0xFF)) // less blue
    }
}

class VersionsTest {
    @Test fun comparesSemver() {
        assertTrue(Versions.isNewer("1.2.0", "1.1.9"))
        assertTrue(Versions.isNewer("v2.0", "1.9.9"))
        assertTrue(Versions.isNewer("1.10.0", "1.9.0"))
        assertFalse(Versions.isNewer("1.2.0", "1.2.0"))
        assertFalse(Versions.isNewer("1.2.0", "1.2.1-debug"))
    }
}

class SseParserTest {
    @Test fun parsesEventsAndIgnoresComments() {
        val p = SseParser()
        assertNull(p.feed(": hi"))
        assertNull(p.feed(""))
        assertNull(p.feed("id: 1:0"))
        assertNull(p.feed("data: [{\"a\":1}]"))
        assertEquals("[{\"a\":1}]", p.feed(""))
        assertNull(p.feed("data: x"))
        assertNull(p.feed("data: y"))
        assertEquals("x\ny", p.feed(""))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommandQueueTest {
    @Test fun firstCommandIsImmediateAndBurstsCoalesce() {
        val scope = TestScope(StandardTestDispatcher())
        val sent = mutableListOf<JsonObject>()
        val q = CommandQueue(scope, send = { _, _, b -> sent += b }, onError = { _, _, _ -> }, intervalMs = { 300L })
        q.submit("grouped_light", "g", brightnessBody(10f))
        scope.runCurrent()
        assertEquals(1, sent.size)
        q.submit("grouped_light", "g", brightnessBody(20f))
        q.submit("grouped_light", "g", brightnessBody(30f))
        q.submit("grouped_light", "g", brightnessBody(40f))
        scope.advanceTimeBy(299); scope.runCurrent()
        assertEquals(1, sent.size)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(2, sent.size)
        assertEquals(40.0, sent.last().obj("dimming")?.dbl("brightness"))
        scope.advanceUntilIdle()
        assertEquals(2, sent.size)
    }

    @Test fun differentResourcesDoNotBlockEachOther() {
        val scope = TestScope(StandardTestDispatcher())
        val sent = mutableListOf<String>()
        val q = CommandQueue(scope, send = { _, id, _ -> sent += id }, onError = { _, _, _ -> }, intervalMs = { 300L })
        q.submit("light", "a", onBody(true))
        q.submit("light", "b", onBody(true))
        scope.runCurrent()
        assertEquals(listOf("a", "b"), sent)
    }

    @Test fun errorsAreReportedAndQueueRecovers() {
        val scope = TestScope(StandardTestDispatcher())
        var errors = 0
        var calls = 0
        val q = CommandQueue(scope, send = { _, _, _ -> calls++; if (calls == 1) throw java.io.IOException("x") }, onError = { _, _, _ -> errors++ }, intervalMs = { 0L })
        q.submit("light", "a", onBody(true))
        scope.advanceUntilIdle()
        q.submit("light", "a", onBody(false))
        scope.advanceUntilIdle()
        assertEquals(1, errors); assertEquals(2, calls)
    }
}
