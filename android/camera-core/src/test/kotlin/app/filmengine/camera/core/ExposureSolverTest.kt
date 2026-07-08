package app.filmengine.camera.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposureSolverTest {

    private val caps = ExposureCaps(
        isoMin = 100,
        isoMax = 12800,
        minShutterNs = 125_000,          // 1/8000 s
        maxShutterNs = 1_000_000_000,    // 1 s
    )
    private val solver = ExposureSolver(caps)
    private val metered = ExposureSettings(iso = 400, shutterNs = 16_666_666) // 1/60 s

    @Test
    fun `auto passes the metered pair through`() {
        val r = solver.solve(ExposureMode.AUTO, metered)
        assertEquals(metered, r.settings)
        assertEquals(0f, r.offsetStops)
    }

    @Test
    fun `shutter priority - halving the shutter doubles the iso`() {
        val r = solver.solve(ExposureMode.SHUTTER_PRIORITY, metered, userShutterNs = metered.shutterNs / 2)
        assertEquals(800, r.settings.iso)
        assertTrue(abs(r.offsetStops) < 0.01f, "offset ${r.offsetStops} should be ~0")
    }

    @Test
    fun `iso priority - quadrupling the iso quarters the shutter`() {
        val r = solver.solve(ExposureMode.ISO_PRIORITY, metered, userIso = 1600)
        assertEquals(1600, r.settings.iso)
        assertTrue(abs(r.settings.shutterNs - metered.shutterNs / 4) <= 1)
        assertTrue(abs(r.offsetStops) < 0.01f)
    }

    @Test
    fun `exposure compensation shifts the product`() {
        val r = solver.solve(
            ExposureMode.SHUTTER_PRIORITY, metered,
            userShutterNs = metered.shutterNs, ecStops = 1f,
        )
        assertEquals(800, r.settings.iso)
        assertTrue(abs(r.offsetStops) < 0.01f)
    }

    @Test
    fun `clamping at the iso ceiling reports underexposure`() {
        // 1/8000 needs iso 400 * (1/60)/(1/8000) = ~53333 — far beyond 12800.
        val r = solver.solve(ExposureMode.SHUTTER_PRIORITY, metered, userShutterNs = 125_000)
        assertEquals(12800, r.settings.iso)
        assertTrue(r.offsetStops < -1.5f, "expected strong underexposure, got ${r.offsetStops}")
    }

    @Test
    fun `clamping at the iso floor reports overexposure`() {
        // 1 s needs iso ~6.7 — below 100.
        val r = solver.solve(ExposureMode.SHUTTER_PRIORITY, metered, userShutterNs = 1_000_000_000)
        assertEquals(100, r.settings.iso)
        assertTrue(r.offsetStops > 3f, "expected strong overexposure, got ${r.offsetStops}")
    }

    @Test
    fun `manual clamps user values into the capability range`() {
        val r = solver.solve(
            ExposureMode.MANUAL, metered,
            userIso = 50, userShutterNs = 5_000_000_000L,
        )
        assertEquals(100, r.settings.iso)
        assertEquals(1_000_000_000L, r.settings.shutterNs)
    }
}
