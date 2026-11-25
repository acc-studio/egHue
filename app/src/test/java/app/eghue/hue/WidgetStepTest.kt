package app.eghue.hue

import app.eghue.hue.HueRepository.Companion.stepBrightness
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStepTest {
    @Test fun snapsToTheStepGrid() {
        assertEquals(40f, stepBrightness(true, 37f, 10))
        assertEquals(30f, stepBrightness(true, 37f, -10))
        assertEquals(50f, stepBrightness(true, 40f, 10))
        assertEquals(30f, stepBrightness(true, 40f, -10))
        // Bridge brightness comes back slightly off the grid, e.g. 39.92 for 40.
        assertEquals(50f, stepBrightness(true, 39.96f, 10))
    }

    @Test fun neverSwitchesOffOrGoesPastFull() {
        assertEquals(1f, stepBrightness(true, 5f, -10))
        assertEquals(1f, stepBrightness(true, 1f, -10))
        assertEquals(100f, stepBrightness(true, 95f, 10))
        assertEquals(100f, stepBrightness(true, 100f, 10))
    }

    @Test fun fromOffStartsAtOneStep() {
        assertEquals(10f, stepBrightness(false, 0f, 10))
        assertEquals(1f, stepBrightness(false, 0f, -10))
    }
}
