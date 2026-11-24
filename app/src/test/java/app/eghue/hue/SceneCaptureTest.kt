package app.eghue.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneCaptureTest {
    private fun light(on: Boolean = true, ct: Boolean = true, mirek: Int? = 400, xy: Xy? = null, bri: Float = 42.37f) = LightUi(
        id = "l1", name = "Lamp", archetype = null, on = on, brightness = bri, dimmable = true,
        supportsColor = true, supportsCt = true, mirek = mirek, mirekMin = 153, mirekMax = 454,
        xy = xy, ctActive = ct, reachable = true, color = 0,
    )

    @Test fun whiteLightKeepsItsColourTemperature() {
        val a = SceneCapture.actionFor(light())
        assertEquals(true, a.obj("on")?.bool("on"))
        assertEquals(42.4, a.obj("dimming")?.dbl("brightness"))
        assertEquals(400, a.obj("color_temperature")?.int("mirek"))
        assertNull(a.obj("color"))
    }

    @Test fun colourLightKeepsItsColour() {
        val a = SceneCapture.actionFor(light(ct = false, mirek = null, xy = Xy(0.5, 0.4)))
        assertEquals(0.5, a.obj("color")?.obj("xy")?.dbl("x"))
        assertNull(a.obj("color_temperature"))
    }

    @Test fun offLightIsJustOff() {
        val a = SceneCapture.actionFor(light(on = false))
        assertEquals(setOf("on"), a.keys)
        assertEquals(false, a.obj("on")?.bool("on"))
    }

    @Test fun brightnessNeverDropsBelowHuesMinimum() {
        assertEquals(1.0, SceneCapture.actionFor(light(bri = 0.2f)).obj("dimming")?.dbl("brightness"))
    }

    @Test fun actionsTargetEachLight() {
        val arr = SceneCapture.actions(listOf(light()))
        val first = arr.objects().single()
        assertEquals("l1", first.ref("target")?.rid)
        assertEquals("light", first.ref("target")?.rtype)
    }

    @Test fun uniqueNamesCountUp() {
        assertEquals("My scene", SceneCapture.uniqueName("My scene", listOf("Relax")))
        assertEquals("My scene 2", SceneCapture.uniqueName("My scene", listOf("my scene")))
        assertEquals("My scene 3", SceneCapture.uniqueName("My scene", listOf("My scene", "My scene 2")))
    }
}
