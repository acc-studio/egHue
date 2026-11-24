package app.eghue.hue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRulesTest {
    private val home = deriveHome(
        HueJson.parseToJsonElement(
            """
            [
              {"id":"dev1","type":"device","metadata":{"name":"Lamp"},"services":[{"rid":"l1","rtype":"light"}]},
              {"id":"dev2","type":"device","metadata":{"name":"Lamp 2"},"services":[{"rid":"l2","rtype":"light"}]},
              {"id":"l1","type":"light","id_v1":"/lights/1","owner":{"rid":"dev1","rtype":"device"},"metadata":{"name":"Lamp"},"on":{"on":false},"dimming":{"brightness":50},
                "color_temperature":{"mirek":300,"mirek_valid":true,"mirek_schema":{"mirek_minimum":153,"mirek_maximum":500}},
                "powerup":{"preset":"custom","configured":true,"on":{"mode":"on","on":{"on":true}},"dimming":{"mode":"dimming","dimming":{"brightness":65.2}},"color":{"mode":"color_temperature","color_temperature":{"mirek":428}}}},
              {"id":"l2","type":"light","id_v1":"/lights/2","owner":{"rid":"dev2","rtype":"device"},"metadata":{"name":"Lamp 2"},"on":{"on":false},"dimming":{"brightness":50},
                "powerup":{"preset":"last_on_state","configured":true,"on":{"mode":"on","on":{"on":true}},"dimming":{"mode":"previous"}}},
              {"id":"r1","type":"room","id_v1":"/groups/81","metadata":{"name":"Salon","archetype":"living_room"},"children":[{"rid":"dev1","rtype":"device"},{"rid":"dev2","rtype":"device"}],"services":[{"rid":"gl1","rtype":"grouped_light"}]},
              {"id":"gl1","type":"grouped_light","owner":{"rid":"r1","rtype":"room"},"on":{"on":false}},
              {"id":"s1","type":"scene","id_v1":"/scenes/AAA","metadata":{"name":"Relax"},"group":{"rid":"r1","rtype":"room"},"actions":[]},
              {"id":"s2","type":"scene","id_v1":"/scenes/BBB","metadata":{"name":"Read"},"group":{"rid":"r1","rtype":"room"},"actions":[]}
            ]
            """.trimIndent()
        ).jsonArray.objects()
    )
    private val index = V1Index(home)
    private val factory = HueJson.parseToJsonElement(
        javaClass.classLoader!!.getResource("factory_dimmer_rules.json")!!.readText()
    ).jsonObject

    @Test fun parsesFactoryDimmerSetup() {
        val setup = RemoteRules.parse("/sensors/2", factory, index)
        assertEquals("r1", setup.targetGroupId)
        assertEquals(ButtonAction.On, setup.action(1, Gesture.PRESS))
        assertEquals(ButtonAction.Off, setup.action(1, Gesture.HOLD))
        assertEquals(ButtonAction.Brighter, setup.action(2, Gesture.PRESS))
        assertEquals(ButtonAction.Brighter, setup.action(2, Gesture.HOLD))
        assertEquals(ButtonAction.Dimmer, setup.action(3, Gesture.PRESS))
        assertEquals(ButtonAction.Dimmer, setup.action(3, Gesture.HOLD))
        // The factory Hue button cycles raw light states, not scenes: shown as "keep".
        assertEquals(ButtonAction.Keep("Cycles through 5 light settings"), setup.action(4, Gesture.PRESS))
        assertEquals("/sensors/4", setup.cycleSensors[4])
        assertEquals(listOf("8"), setup.resetRules["/sensors/4"])
        assertEquals(5, setup.ruleIds[ButtonKey(4, Gesture.PRESS)]!!.size)
    }

    @Test fun ignoresOtherSwitches() {
        val setup = RemoteRules.parse("/sensors/99", factory, index)
        assertTrue(setup.actions.isEmpty())
    }

    @Test fun toggleBuildsTwoGuardedRulesAndRoundTrips() {
        val specs = RemoteRules.build("/sensors/2", 1, Gesture.PRESS, ButtonAction.Toggle, ButtonAction.Nothing, "/groups/81", null, index)
        assertEquals(2, specs.size)
        // No hold action: fire on initial press for speed.
        assertTrue(specs.all { s -> s.conditions.toString().contains("\"1000\"") })
        val parsed = RemoteRules.parse("/sensors/2", asRules(specs), index)
        assertEquals(ButtonAction.Toggle, parsed.action(1, Gesture.PRESS))
        assertEquals("r1", parsed.targetGroupId)
    }

    @Test fun pressWaitsForReleaseWhenHoldIsUsed() {
        assertEquals(1002, RemoteRules.eventCode(1, Gesture.PRESS, ButtonAction.Toggle, ButtonAction.Off))
        assertEquals(1003, RemoteRules.eventCode(1, Gesture.HOLD, ButtonAction.Off, ButtonAction.Off))
        assertEquals(2001, RemoteRules.eventCode(2, Gesture.HOLD, ButtonAction.Brighter, ButtonAction.Brighter))
    }

    @Test fun sceneCycleRoundTrips() {
        val specs = RemoteRules.build("/sensors/2", 4, Gesture.PRESS, ButtonAction.Cycle(listOf("s1", "s2")), ButtonAction.Nothing,
            "/groups/81", null, index, memory = "/sensors/9") + RemoteRules.cycleReset("/sensors/2", 4, "/sensors/9")
        assertEquals(3, specs.size)
        val parsed = RemoteRules.parse("/sensors/2", asRules(specs), index)
        assertEquals(ButtonAction.Cycle(listOf("s1", "s2")), parsed.action(4, Gesture.PRESS))
        assertEquals(1, parsed.resetRules["/sensors/9"]!!.size)
    }

    @Test fun sceneAndOffRoundTrip() {
        val specs = RemoteRules.build("/sensors/2", 4, Gesture.PRESS, ButtonAction.Scene("s2"), ButtonAction.AllOff, "/groups/81", null, index) +
            RemoteRules.build("/sensors/2", 4, Gesture.HOLD, ButtonAction.AllOff, ButtonAction.AllOff, "/groups/81", null, index)
        val parsed = RemoteRules.parse("/sensors/2", asRules(specs), index)
        assertEquals(ButtonAction.Scene("s2"), parsed.action(4, Gesture.PRESS))
        assertEquals(ButtonAction.AllOff, parsed.action(4, Gesture.HOLD))
    }

    @Test fun turnOnFollowsPowerOnSettingsPerLight() {
        val lights = home.group("r1")!!.lightIds.map { home.light(it)!! }
        val actions = RemoteRules.turnOnActions("/groups/81", lights, followPowerOn = true, index = index)
        assertEquals(2, actions.size)
        val first = actions[0] as JsonObject
        assertEquals("/lights/1/state", first.str("address"))
        assertEquals(166, first.obj("body")?.int("bri")) // 65.2% of 254
        assertEquals(428, first.obj("body")?.int("ct"))
        assertEquals(setOf("on"), (actions[1] as JsonObject).obj("body")!!.keys)
        // And egHue recognises its own per-light "on" when reading the rules back.
        val spec = RemoteRules.build("/sensors/2", 1, Gesture.PRESS, ButtonAction.On, ButtonAction.Nothing, "/groups/81", actions, index)
        assertEquals(ButtonAction.On, RemoteRules.parse("/sensors/2", asRules(spec), index).action(1, Gesture.PRESS))
        // Without the setting it's one plain group command.
        assertEquals(1, RemoteRules.turnOnActions("/groups/81", lights, followPowerOn = false, index = index).size)
    }

    @Test fun switchOnBodyUsesPowerOnSettings() {
        val l1 = home.light("l1")!!
        val body = switchOnBody(l1, true)
        assertEquals(428, body.obj("color_temperature")?.int("mirek"))
        assertEquals(65.2, body.obj("dimming")?.dbl("brightness")!!, 0.01)
        assertEquals(setOf("on"), switchOnBody(l1, false).keys)
        assertEquals(setOf("on"), switchOnBody(home.light("l2")!!, true).keys)
    }

    @Test fun powerUpBodyMatchesLightCapabilities() {
        val l2 = home.light("l2")!! // dimmable, no colour
        val body = powerUpBody(PowerOn(PowerOn.CUSTOM, brightness = 40f, mirek = 400), l2).obj("powerup")!!
        assertEquals("custom", body.str("preset"))
        assertEquals(40.0, body.obj("dimming")?.obj("dimming")?.dbl("brightness")!!, 0.01)
        assertTrue(body.obj("color") == null)
        assertEquals(setOf("preset"), powerUpBody(PowerOn(PowerOn.SAFETY), l2).obj("powerup")!!.keys)
    }

    private fun asRules(specs: List<RemoteRules.Spec>): JsonObject =
        JsonObject(specs.mapIndexed { i, s -> (100 + i).toString() to s.toJson() }.toMap())
}
