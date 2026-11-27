package app.eghue.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDimmingTest {
    private fun assertNear(expected: Float, actual: Float?) = assertEquals(expected, actual!!, 0.15f)

    @Test fun scalesProRata() {
        val out = GroupDimming.scale(mapOf("a" to 50f, "b" to 100f), 60f)
        assertNear(40f, out["a"])
        assertNear(80f, out["b"])
    }

    @Test fun brighterLightStopsAtFullAndTheOthersCatchUp() {
        val out = GroupDimming.scale(mapOf("a" to 50f, "b" to 100f), 90f)
        assertNear(100f, out["b"])
        assertNear(80f, out["a"])
    }

    @Test fun extremesGoFlat() {
        assertEquals(mapOf("a" to 100f, "b" to 100f), GroupDimming.scale(mapOf("a" to 50f, "b" to 100f), 100f))
        assertEquals(mapOf("a" to 1f, "b" to 1f), GroupDimming.scale(mapOf("a" to 50f, "b" to 100f), 1f))
    }

    @Test fun dimmerLightBottomsOutAtOnePercent() {
        val out = GroupDimming.scale(mapOf("a" to 2f, "b" to 100f), 10f)
        assertNear(1f, out["a"])
        assertNear(19f, out["b"])
    }

    @Test fun backToStartRestoresTheOriginalLevels() {
        val base = mapOf("a" to 50f, "b" to 100f)
        val out = GroupDimming.scale(base, 75f)
        assertNear(50f, out["a"])
        assertNear(100f, out["b"])
    }

    @Test fun differsNeedsTwoDistinctLevels() {
        assertFalse(GroupDimming.differs(listOf(60f)))
        assertFalse(GroupDimming.differs(listOf(60f, 60.4f)))
        assertTrue(GroupDimming.differs(listOf(50f, 100f)))
    }
}
