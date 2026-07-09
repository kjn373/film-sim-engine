package app.filmengine.camera.core

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationStatsTest {

    private val w = 64
    private val h = 48

    /** RGGB: R at (even, even), G at (odd, even)/(even, odd), B at (odd, odd). */
    private fun rggb(x: Int, y: Int, r: Int, g: Int, b: Int): Int = when {
        y % 2 == 0 && x % 2 == 0 -> r
        y % 2 == 1 && x % 2 == 1 -> b
        else -> g
    }

    @Test
    fun `dark frame means land on the per-channel values with zero variance`() {
        val stats = CalibrationStats.compute("dark", 100, 1_000_000, 6504f, w, h, cfa = 0) { x, y ->
            rggb(x, y, 64, 65, 66)
        }
        assertEquals(64f, stats.means[0], 1e-3f)
        assertEquals(65f, stats.means[1], 1e-3f)
        assertEquals(66f, stats.means[2], 1e-3f)
        for (v in stats.variances) assertEquals(0f, v, 1e-2f)
        assertTrue(stats.patches.isEmpty() && stats.radialGains.isEmpty())
    }

    @Test
    fun `bggr pattern maps channels correctly`() {
        val stats = CalibrationStats.compute("dark", 100, 1_000_000, 6504f, w, h, cfa = 3) { x, y ->
            rggb(x, y, 300, 200, 100) // value at the RGGB "R" site is now the B channel
        }
        assertEquals(100f, stats.means[0], 1e-3f) // R reads the (odd,odd) sites
        assertEquals(200f, stats.means[1], 1e-3f)
        assertEquals(300f, stats.means[2], 1e-3f)
    }

    @Test
    fun `chart frame extracts the 24 patch means from the central grid`() {
        // paint each 6×4 grid cell of the central 80% with its patch index
        val x0 = w * 0.1f
        val y0 = h * 0.1f
        val cellW = w * 0.8f / 6
        val cellH = h * 0.8f / 4
        val stats = CalibrationStats.compute("chart", 100, 10_000_000, 6504f, w, h, cfa = 0) { x, y ->
            val col = ((x - x0) / cellW).toInt().coerceIn(0, 5)
            val row = ((y - y0) / cellH).toInt().coerceIn(0, 3)
            (row * 6 + col) * 10
        }
        assertEquals(24, stats.patches.size)
        for (i in 0 until 24) {
            for (c in 0..2) {
                assertEquals("patch $i ch $c", i * 10f, stats.patches[i][c], 1e-3f)
            }
        }
    }

    @Test
    fun `flat frame radial gains fall off from center`() {
        val cx = w / 2f
        val cy = h / 2f
        val rMax = sqrt(cx * cx + cy * cy)
        val stats = CalibrationStats.compute("flat", 100, 5_000_000, 6504f, w, h, cfa = 0) { x, y ->
            val r = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / rMax
            (1000 * (1f - 0.5f * r * r)).toInt()
        }
        assertTrue(stats.radialGains.isNotEmpty())
        assertTrue("center gain should be ~1", abs(stats.radialGains.first()[1] - 1f) < 0.02f)
        assertTrue("edge gain should fall off", stats.radialGains.last()[1] < 0.75f)
        for (i in 1 until stats.radialGains.size) {
            assertTrue("gains must decrease", stats.radialGains[i][1] <= stats.radialGains[i - 1][1] + 1e-3f)
        }
    }
}
