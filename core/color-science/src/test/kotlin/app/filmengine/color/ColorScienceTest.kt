package app.filmengine.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ColorScienceTest {

    private fun assertMat3Equals(expected: Mat3, actual: Mat3, tol: Float = 1e-5f) {
        for (i in 0..8) {
            assertTrue(
                abs(expected.m[i] - actual.m[i]) < tol,
                "m[$i]: expected ${expected.m[i]}, got ${actual.m[i]}"
            )
        }
    }

    @Test
    fun `inverse round-trips to identity`() {
        assertMat3Equals(Mat3.IDENTITY, ColorSpaces.SRGB_TO_XYZ * ColorSpaces.XYZ_TO_SRGB)
        assertMat3Equals(Mat3.IDENTITY, ColorSpaces.REC2020_TO_XYZ * ColorSpaces.XYZ_TO_REC2020)
        assertMat3Equals(Mat3.IDENTITY, ColorSpaces.SRGB_TO_REC2020 * ColorSpaces.REC2020_TO_SRGB)
    }

    @Test
    fun `white maps to D65 white point in XYZ`() {
        val d65 = floatArrayOf(0.95047f, 1.0f, 1.08883f)
        for (mat in listOf(ColorSpaces.SRGB_TO_XYZ, ColorSpaces.REC2020_TO_XYZ)) {
            val xyz = mat.transform(1f, 1f, 1f)
            for (i in 0..2) {
                assertTrue(abs(xyz[i] - d65[i]) < 2e-3f, "channel $i: ${xyz[i]} vs ${d65[i]}")
            }
        }
    }

    @Test
    fun `greys are invariant under gamut conversion`() {
        for (v in listOf(0.0f, 0.18f, 0.5f, 1.0f)) {
            val rgb = ColorSpaces.REC2020_TO_SRGB.transform(v, v, v)
            for (i in 0..2) {
                assertTrue(abs(rgb[i] - v) < 1e-4f, "grey $v channel $i drifted to ${rgb[i]}")
            }
        }
    }

    @Test
    fun `srgb transfer round-trips`() {
        var v = 0.0f
        while (v <= 1.0f) {
            assertTrue(abs(Srgb.oetf(Srgb.eotf(v)) - v) < 1e-5f, "round-trip failed at $v")
            v += 0.01f
        }
    }

    @Test
    fun `srgb transfer known values`() {
        assertEquals(0.0f, Srgb.oetf(0.0f), 1e-6f)
        assertEquals(1.0f, Srgb.oetf(1.0f), 1e-4f)
        // 18% grey encodes to ~0.4613
        assertEquals(0.4613f, Srgb.oetf(0.18f), 1e-3f)
    }

    @Test
    fun `luma weights sum to one`() {
        assertEquals(1.0f, ColorSpaces.LUMA_R + ColorSpaces.LUMA_G + ColorSpaces.LUMA_B, 1e-4f)
    }
}
