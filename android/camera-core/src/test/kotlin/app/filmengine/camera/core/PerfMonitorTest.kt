package app.filmengine.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfMonitorTest {

    @Test
    fun `starts at FULL quality`() {
        val pm = PerfMonitor()
        assertEquals(QualityLevel.FULL, pm.currentLevel)
    }

    @Test
    fun `degrades after sustained overrun`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 5,
            recoverAfter = 10,
            recoverMs = 14.0f,
        )
        // 4 frames over budget — no change
        repeat(4) { assertFalse(pm.recordFrame(20f)) }
        assertEquals(QualityLevel.FULL, pm.currentLevel)

        // 5th frame triggers degradation
        assertTrue(pm.recordFrame(20f))
        assertEquals(QualityLevel.REDUCED_GRAIN, pm.currentLevel)
    }

    @Test
    fun `recovers after sustained headroom`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 2,
            recoverAfter = 5,
            recoverMs = 14.0f,
        )
        // Force to REDUCED_GRAIN
        repeat(2) { pm.recordFrame(20f) }
        assertEquals(QualityLevel.REDUCED_GRAIN, pm.currentLevel)

        // 4 fast frames — not enough
        repeat(4) { assertFalse(pm.recordFrame(10f)) }
        assertEquals(QualityLevel.REDUCED_GRAIN, pm.currentLevel)

        // 5th fast frame triggers recovery
        assertTrue(pm.recordFrame(10f))
        assertEquals(QualityLevel.FULL, pm.currentLevel)
    }

    @Test
    fun `does not recover beyond FULL`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 2,
            recoverAfter = 2,
            recoverMs = 14.0f,
        )
        // Already at FULL — fast frames should not crash or change
        repeat(5) { pm.recordFrame(10f) }
        assertEquals(QualityLevel.FULL, pm.currentLevel)
    }

    @Test
    fun `does not degrade beyond MINIMAL`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 2,
            recoverAfter = 100,
            recoverMs = 14.0f,
        )
        // Hammer it down to MINIMAL
        repeat(20) { pm.recordFrame(25f) }
        assertEquals(QualityLevel.MINIMAL, pm.currentLevel)

        // More overruns — should stay at MINIMAL, not crash
        repeat(10) { pm.recordFrame(25f) }
        assertEquals(QualityLevel.MINIMAL, pm.currentLevel)
    }

    @Test
    fun `cascades through all levels on sustained overrun`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 2,
            recoverAfter = 100,
            recoverMs = 14.0f,
        )
        val expected = listOf(
            QualityLevel.FULL,
            QualityLevel.REDUCED_GRAIN,
            QualityLevel.NO_GRAIN,
            QualityLevel.HALF_RES_SPATIAL,
            QualityLevel.MINIMAL,
        )
        assertEquals(expected[0], pm.currentLevel)
        for (i in 1 until expected.size) {
            repeat(2) { pm.recordFrame(25f) }
            assertEquals("After ${i * 2} overrun frames", expected[i], pm.currentLevel)
        }
    }

    @Test
    fun `frames in hysteresis band reset both streaks`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 3,
            recoverAfter = 3,
            recoverMs = 14.0f,
        )
        // 2 overruns, then a frame in the band [14, 16.6]
        repeat(2) { pm.recordFrame(20f) }
        pm.recordFrame(15f) // band — resets overrunStreak

        // Another 2 overruns — should NOT trigger (streak was reset)
        repeat(2) { assertFalse(pm.recordFrame(20f)) }
        assertEquals(QualityLevel.FULL, pm.currentLevel)
    }

    @Test
    fun `reset restores FULL and clears history`() {
        val pm = PerfMonitor(
            targetMs = 16.6f,
            degradeAfter = 2,
            recoverAfter = 100,
            recoverMs = 14.0f,
        )
        repeat(4) { pm.recordFrame(25f) }
        assertEquals(QualityLevel.NO_GRAIN, pm.currentLevel)

        pm.reset()
        assertEquals(QualityLevel.FULL, pm.currentLevel)
        assertEquals(0f, pm.averageMs)
    }

    @Test
    fun `averageMs tracks rolling window`() {
        val pm = PerfMonitor()
        pm.recordFrame(10f)
        pm.recordFrame(20f)
        assertEquals(15f, pm.averageMs, 0.01f)
    }
}
