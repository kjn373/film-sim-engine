package app.filmengine.profile

import app.filmengine.color.Mat3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileCalibratorTest {

    /** A plausible camera→XYZ matrix (warm sensor, mild crosstalk). */
    private val trueMatrix = Mat3(
        floatArrayOf(
            0.65f, 0.28f, 0.02f,
            0.27f, 0.68f, 0.05f,
            0.01f, 0.14f, 0.90f,
        )
    )

    private fun syntheticPatches(mat: Mat3, jitter: Float = 0f): List<ColorMatrixSolver.Patch> {
        val inv = mat.inverse()
        var seed = 12345
        fun noise(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 16) and 0x7FFF) / 32767f - 0.5f
        }
        return ColorCheckerReference.XYZ_D65.map { xyz ->
            val rgb = inv.transform(xyz[0], xyz[1], xyz[2])
            for (i in 0..2) rgb[i] += noise() * jitter
            ColorMatrixSolver.Patch(rgb, xyz)
        }
    }

    @Test
    fun `matrix solver recovers a known matrix from clean patches`() {
        val solved = ColorMatrixSolver.solve(syntheticPatches(trueMatrix))
        for (i in 0..8) {
            assertTrue(
                abs(solved.m[i] - trueMatrix.m[i]) < 1e-4f,
                "m[$i]: expected ${trueMatrix.m[i]}, got ${solved.m[i]}"
            )
        }
    }

    @Test
    fun `matrix solver stays close under measurement noise`() {
        val solved = ColorMatrixSolver.solve(syntheticPatches(trueMatrix, jitter = 2e-3f))
        for (i in 0..8) {
            assertTrue(abs(solved.m[i] - trueMatrix.m[i]) < 0.03f, "m[$i] drifted: ${solved.m[i]}")
        }
    }

    @Test
    fun `radial fitter recovers known vignette coefficients`() {
        val k = floatArrayOf(-0.35f, 0.10f, -0.02f)
        val samples = (1..20).map { i ->
            val r = i / 20f
            val r2 = r * r
            r to (k[0] * r2 + k[1] * r2 * r2 + k[2] * r2 * r2 * r2)
        }
        val fit = RadialPolyFitter.fit(samples)
        for (i in 0..2) assertTrue(abs(fit[i] - k[i]) < 1e-3f, "k${i + 1}: ${fit[i]}")
    }

    @Test
    fun `noise fitter recovers scale and offset, and degrades to offset-only`() {
        val (s, o) = NoiseFitter.fit(listOf(0.1f to 0.011f, 0.5f to 0.051f, 0.9f to 0.091f))
        assertTrue(abs(s - 0.1f) < 1e-4f && abs(o - 0.001f) < 1e-4f, "got scale=$s offset=$o")

        val (s2, o2) = NoiseFitter.fit(listOf(0.0f to 0.002f, 0.0f to 0.004f)) // darks only
        assertEquals(0f, s2)
        assertTrue(abs(o2 - 0.003f) < 1e-6f)
    }

    @Test
    fun `profile codec round-trips and rejects newer schema`() {
        val profile = DeviceProfile(
            model = "TestPhone",
            source = "calibrated",
            sensor = SensorCalibration(
                whiteLevel = 1023f,
                blackLevels = mapOf(100 to 64f, 800 to 66f),
                colorMatrix1 = trueMatrix.m.toList(),
                colorMatrix2 = trueMatrix.m.toList(),
            ),
            noise = listOf(NoisePoint(100, 0.01f, 0.001f)),
        )
        assertEquals(profile, DeviceProfileCodec.decode(DeviceProfileCodec.encode(profile)))

        val newer = DeviceProfileCodec.encode(profile.copy(schemaVersion = 99))
        assertFailsWith<IllegalArgumentException> { DeviceProfileCodec.decode(newer) }
    }

    @Test
    fun `cct interpolation hits the endpoints and blends in mired between them`() {
        val m1 = List(9) { 1f }
        val m2 = List(9) { 3f }
        val sensor = SensorCalibration(
            whiteLevel = 1023f, blackLevels = mapOf(100 to 64f),
            illuminant1Cct = 6504f, illuminant2Cct = 2856f,
            colorMatrix1 = m1, colorMatrix2 = m2,
        )
        assertEquals(1f, sensor.colorMatrix(6504f).m[0])
        assertEquals(3f, sensor.colorMatrix(2856f).m[0])
        val midMired = 2f / (1f / 6504f + 1f / 2856f)
        assertTrue(abs(sensor.colorMatrix(midMired).m[0] - 2f) < 1e-3f)
        // clamped outside the calibrated range
        assertEquals(1f, sensor.colorMatrix(10000f).m[0])
        assertEquals(3f, sensor.colorMatrix(2000f).m[0])
    }

    @Test
    fun `black level interpolates between measured ISOs`() {
        val sensor = SensorCalibration(
            whiteLevel = 1023f, blackLevels = mapOf(100 to 64f, 800 to 71f),
            colorMatrix1 = List(9) { 0f }, colorMatrix2 = List(9) { 0f },
        )
        assertEquals(64f, sensor.blackLevel(50))
        assertEquals(71f, sensor.blackLevel(3200))
        assertTrue(abs(sensor.blackLevel(450) - 67.5f) < 1e-3f)
    }

    @Test
    fun `calibrator turns a synthetic report into a coherent profile`() {
        val white = 1023f
        val black = 64f
        val inv = trueMatrix.inverse()

        fun chartFrame(cct: Float, iso: Int) = FrameStats(
            kind = "chart", iso = iso, exposureNs = 10_000_000, cct = cct,
            means = listOf(300f, 320f, 310f), variances = listOf(4f, 4f, 4f),
            patches = ColorCheckerReference.XYZ_D65.map { xyz ->
                val rgb = inv.transform(xyz[0], xyz[1], xyz[2])
                rgb.map { it * (white - black) + black }
            },
        )

        val report = CalibrationReport(
            model = "TestPhone", whiteLevel = white,
            frames = listOf(
                FrameStats("dark", 100, 1_000_000, means = listOf(black, black, black), variances = listOf(1f, 1f, 1f)),
                FrameStats("dark", 800, 1_000_000, means = listOf(black + 2f, black + 2f, black + 2f), variances = listOf(6f, 6f, 6f)),
                chartFrame(6504f, 100),
                chartFrame(2856f, 100),
                FrameStats(
                    "flat", 100, 5_000_000, means = listOf(500f, 500f, 500f), variances = listOf(8f, 8f, 8f),
                    radialGains = (1..10).map { i ->
                        val r = i / 10f
                        listOf(r, 1f - 0.3f * r * r)
                    },
                ),
            ),
        )

        val profile = Calibrator.calibrate(report)
        assertEquals("calibrated", profile.source)
        assertEquals(black, profile.sensor.blackLevels.getValue(100))
        assertEquals(6504f, profile.sensor.illuminant1Cct)
        assertEquals(2856f, profile.sensor.illuminant2Cct)
        // both synthetic illuminants used the same true matrix — solver must recover it
        for (i in 0..8) {
            assertTrue(abs(profile.sensor.colorMatrix1[i] - trueMatrix.m[i]) < 1e-3f, "cm1[$i]")
            assertTrue(abs(profile.sensor.colorMatrix2[i] - trueMatrix.m[i]) < 1e-3f, "cm2[$i]")
        }
        assertEquals(listOf(100, 800), profile.noise.map { it.iso })
        assertEquals(1, profile.lenses.size)
        assertTrue(abs(profile.lenses[0].vignette[0] + 0.3f) < 1e-2f, "vignette k1: ${profile.lenses[0].vignette[0]}")
    }
}
