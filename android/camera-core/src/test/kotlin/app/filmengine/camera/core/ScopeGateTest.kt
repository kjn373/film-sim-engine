package app.filmengine.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeGateTest {

    @Test
    fun `interval follows the perf ladder`() {
        assertEquals(4, ScopeGate.interval(QualityLevel.FULL))
        assertEquals(4, ScopeGate.interval(QualityLevel.REDUCED_GRAIN))
        assertEquals(8, ScopeGate.interval(QualityLevel.NO_GRAIN))
        assertEquals(0, ScopeGate.interval(QualityLevel.HALF_RES_SPATIAL))
        assertEquals(0, ScopeGate.interval(QualityLevel.MINIMAL))
    }

    @Test
    fun `collects every 4th frame at FULL`() {
        val hits = (0L until 16L).count {
            ScopeGate.shouldCollect(it, enabled = true, quality = QualityLevel.FULL)
        }
        assertEquals(4, hits)
        assertTrue(ScopeGate.shouldCollect(0, true, QualityLevel.FULL))
        assertFalse(ScopeGate.shouldCollect(1, true, QualityLevel.FULL))
    }

    @Test
    fun `disabled never collects`() {
        for (q in QualityLevel.entries) {
            assertFalse(ScopeGate.shouldCollect(0, enabled = false, quality = q))
        }
    }

    @Test
    fun `degraded spatial quality turns scopes off even when enabled`() {
        assertFalse(ScopeGate.shouldCollect(0, true, QualityLevel.HALF_RES_SPATIAL))
        assertFalse(ScopeGate.shouldCollect(0, true, QualityLevel.MINIMAL))
    }
}
