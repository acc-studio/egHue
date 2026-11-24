package app.eghue.hue

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicesModelTest {
    private val resources = (HueJson.parseToJsonElement(
        """
        [{"id":"d1","type":"device","metadata":{"name":"Lamp"},"product_data":{"product_name":"Hue color lamp"},
          "services":[{"rid":"l1","rtype":"light"}]},
         {"id":"l1","type":"light","owner":{"rid":"d1","rtype":"device"},"metadata":{"name":"Lamp"},"on":{"on":true},
          "effects":{"status":"candle","effect_values":["no_effect","candle","fire"]}},
         {"id":"d2","type":"device","metadata":{"name":"Hall sensor"},"product_data":{"product_name":"Hue motion sensor"},
          "services":[{"rid":"m","rtype":"motion"},{"rid":"t","rtype":"temperature"},{"rid":"ll","rtype":"light_level"},{"rid":"p","rtype":"device_power"}]},
         {"id":"m","type":"motion","owner":{"rid":"d2","rtype":"device"},"enabled":false,"motion":{"motion":true,"motion_valid":true}},
         {"id":"t","type":"temperature","owner":{"rid":"d2","rtype":"device"},"temperature":{"temperature_report":{"temperature":19.5}}},
         {"id":"ll","type":"light_level","owner":{"rid":"d2","rtype":"device"},"light":{"light_level":20001}},
         {"id":"p","type":"device_power","owner":{"rid":"d2","rtype":"device"},"power_state":{"battery_level":55}}]
        """.trimIndent()
    ) as JsonArray).objects()

    @Test fun lightsKnowTheirEffectsAndDevice() {
        val l = deriveHome(resources).light("l1")!!
        assertEquals(listOf("candle", "fire"), l.effects)
        assertEquals("candle", l.effect)
        assertEquals("d1", l.deviceId)
        assertEquals("Hue color lamp", l.productName)
    }

    @Test fun sensorsCollectTheirReadings() {
        val s = deriveHome(resources).sensors.single()
        assertEquals("Hall sensor", s.name)
        assertEquals("m", s.motionId)
        assertEquals(false, s.motionEnabled)
        assertEquals(true, s.motion)
        assertEquals(19.5, s.temperature!!, 0.001)
        assertEquals(100.0, s.lux!!, 0.1)
        assertEquals(55, s.battery)
        assertNull(s.contactClosed)
    }
}
