package app.filmengine.color

import kotlin.math.pow

/**
 * RGB <-> CIE XYZ matrices (D65), IEC 61966-2-1 / ITU-R BT.2020 primaries.
 * Working space for the engine is linear Rec.2020 (ARCHITECTURE.md §5.3).
 */
object ColorSpaces {
    /**
     * Derives RGB->XYZ from primary and white chromaticities. Both spaces share the
     * exact same D65, so greys are invariant under gamut conversion — hardcoding the
     * published (differently rounded) matrices breaks that by ~1e-3.
     */
    private fun rgbToXyz(
        rx: Float, ry: Float, gx: Float, gy: Float, bx: Float, by: Float,
        wx: Float, wy: Float,
    ): Mat3 {
        val p = Mat3(
            floatArrayOf(
                rx / ry, gx / gy, bx / by,
                1f, 1f, 1f,
                (1f - rx - ry) / ry, (1f - gx - gy) / gy, (1f - bx - by) / by,
            )
        )
        val s = p.inverse().transform(wx / wy, 1f, (1f - wx - wy) / wy)
        return Mat3(
            floatArrayOf(
                p.m[0] * s[0], p.m[1] * s[1], p.m[2] * s[2],
                p.m[3] * s[0], p.m[4] * s[1], p.m[5] * s[2],
                p.m[6] * s[0], p.m[7] * s[1], p.m[8] * s[2],
            )
        )
    }

    private const val D65_X = 0.3127f
    private const val D65_Y = 0.3290f

    val SRGB_TO_XYZ = rgbToXyz(0.64f, 0.33f, 0.30f, 0.60f, 0.15f, 0.06f, D65_X, D65_Y)
    val XYZ_TO_SRGB = SRGB_TO_XYZ.inverse()

    val REC2020_TO_XYZ = rgbToXyz(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f, D65_X, D65_Y)
    val XYZ_TO_REC2020 = REC2020_TO_XYZ.inverse()

    val REC2020_TO_SRGB = XYZ_TO_SRGB * REC2020_TO_XYZ
    val SRGB_TO_REC2020 = XYZ_TO_REC2020 * SRGB_TO_XYZ

    /** Rec.2020 luma weights, for saturation/luma ops in the working space. */
    const val LUMA_R = 0.2627f
    const val LUMA_G = 0.6780f
    const val LUMA_B = 0.0593f
}

/** sRGB transfer function (IEC 61966-2-1). */
object Srgb {
    /** Encoded -> linear. */
    fun eotf(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /** Linear -> encoded. */
    fun oetf(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
}
