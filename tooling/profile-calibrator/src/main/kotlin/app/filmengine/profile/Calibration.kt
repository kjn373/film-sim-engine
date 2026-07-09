package app.filmengine.profile

import app.filmengine.color.ColorSpaces
import app.filmengine.color.Mat3
import app.filmengine.color.Srgb
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * Solves the 3×3 camera-RGB → XYZ matrix from ColorChecker patch measurements:
 * weighted linear least squares, iteratively reweighted by perceptual error so
 * patches the current fit renders badly pull harder (ARCHITECTURE.md §16).
 */
// ponytail: IRLS weights use ΔE76, not ΔE00 — swap the metric if skin-tone
// accuracy on real charts falls short.
object ColorMatrixSolver {

    class Patch(val cameraRgb: FloatArray, val xyz: FloatArray)

    fun solve(patches: List<Patch>, iterations: Int = 3): Mat3 {
        require(patches.size >= 3) { "need at least 3 patches, got ${patches.size}" }
        var weights = FloatArray(patches.size) { 1f }
        var mat = solveWeighted(patches, weights)
        repeat(iterations - 1) {
            weights = FloatArray(patches.size) { i ->
                val fit = mat.transform(patches[i].cameraRgb[0], patches[i].cameraRgb[1], patches[i].cameraRgb[2])
                1f / (1f + deltaE76(fit, patches[i].xyz))
            }
            mat = solveWeighted(patches, weights)
        }
        return mat
    }

    /** Normal equations per XYZ row: (RᵀWR)·a = RᵀW·target. */
    private fun solveWeighted(patches: List<Patch>, w: FloatArray): Mat3 {
        val ata = FloatArray(9)
        val atb = Array(3) { FloatArray(3) } // per XYZ component
        patches.forEachIndexed { i, p ->
            val r = p.cameraRgb
            for (row in 0..2) {
                for (col in 0..2) ata[row * 3 + col] += w[i] * r[row] * r[col]
                for (comp in 0..2) atb[comp][row] += w[i] * p.xyz[comp] * r[row]
            }
        }
        val inv = Mat3(ata).inverse()
        val out = FloatArray(9)
        for (comp in 0..2) {
            val a = inv.transform(atb[comp][0], atb[comp][1], atb[comp][2])
            out[comp * 3] = a[0]; out[comp * 3 + 1] = a[1]; out[comp * 3 + 2] = a[2]
        }
        return Mat3(out)
    }

    private fun deltaE76(xyz1: FloatArray, xyz2: FloatArray): Float {
        val a = lab(xyz1)
        val b = lab(xyz2)
        val dl = a[0] - b[0]; val da = a[1] - b[1]; val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    // D65 white from the same chromaticities color-science derives its matrices from
    private const val WN_X = 0.3127f / 0.3290f
    private const val WN_Z = (1f - 0.3127f - 0.3290f) / 0.3290f

    private fun lab(xyz: FloatArray): FloatArray {
        fun f(t: Float): Float = if (t > 0.008856f) cbrt(t) else t * 7.787f + 4f / 29f
        val fx = f(xyz[0] / WN_X)
        val fy = f(xyz[1])
        val fz = f(xyz[2] / WN_Z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }
}

/**
 * Least-squares fit of y = k1·r² + k2·r⁴ + k3·r⁶ — the even radial polynomial
 * shared by vignette falloff (y = gain − 1) and Brown-Conrady distortion
 * (y = r_distorted/r_undistorted − 1).
 */
object RadialPolyFitter {

    /** [samples] are (radius in 0..1, y). Returns [k1, k2, k3]. */
    fun fit(samples: List<Pair<Float, Float>>): FloatArray {
        require(samples.size >= 3) { "need at least 3 radial samples" }
        val ata = FloatArray(9)
        val atb = FloatArray(3)
        for ((r, y) in samples) {
            val r2 = r * r
            val basis = floatArrayOf(r2, r2 * r2, r2 * r2 * r2)
            for (row in 0..2) {
                for (col in 0..2) ata[row * 3 + col] += basis[row] * basis[col]
                atb[row] += basis[row] * y
            }
        }
        return Mat3(ata).inverse().transform(atb[0], atb[1], atb[2])
    }
}

/** Fits variance = scale·mean + offset across frames at one ISO (plain linear regression). */
object NoiseFitter {

    /** [points] are (mean signal, variance). Returns (scale, offset). */
    fun fit(points: List<Pair<Float, Float>>): Pair<Float, Float> {
        require(points.isNotEmpty()) { "no noise samples" }
        val n = points.size.toFloat()
        val sx = points.fold(0f) { a, p -> a + p.first }
        val sy = points.fold(0f) { a, p -> a + p.second }
        val sxx = points.fold(0f) { a, p -> a + p.first * p.first }
        val sxy = points.fold(0f) { a, p -> a + p.first * p.second }
        val det = n * sxx - sx * sx
        // all samples at one signal level (e.g. dark frames only): offset-only model
        if (det < 1e-12f) return 0f to sy / n
        val scale = (n * sxy - sx * sy) / det
        val offset = (sy - scale * sx) / n
        return scale to offset
    }
}

/**
 * Reference XYZ (D65, Y of white ≈ 0.91) for the classic 24-patch ColorChecker,
 * row-major top-left → bottom-right.
 */
// ponytail: derived from the widely published nominal sRGB rendition of the
// chart — good to a few ΔE. Feed measured Lab data for production-grade profiles.
object ColorCheckerReference {

    private val srgb8 = listOf(
        intArrayOf(115, 82, 68), intArrayOf(194, 150, 130), intArrayOf(98, 122, 157),
        intArrayOf(87, 108, 67), intArrayOf(133, 128, 177), intArrayOf(103, 189, 170),
        intArrayOf(214, 126, 44), intArrayOf(80, 91, 166), intArrayOf(193, 90, 99),
        intArrayOf(94, 60, 108), intArrayOf(157, 188, 64), intArrayOf(224, 163, 46),
        intArrayOf(56, 61, 150), intArrayOf(70, 148, 73), intArrayOf(175, 54, 60),
        intArrayOf(231, 199, 31), intArrayOf(187, 86, 149), intArrayOf(8, 133, 161),
        intArrayOf(243, 243, 242), intArrayOf(200, 200, 200), intArrayOf(160, 160, 160),
        intArrayOf(122, 122, 121), intArrayOf(85, 85, 85), intArrayOf(52, 52, 52),
    )

    val XYZ_D65: List<FloatArray> = srgb8.map { rgb ->
        val lin = FloatArray(3) { Srgb.eotf(rgb[it] / 255f) }
        ColorSpaces.SRGB_TO_XYZ.transform(lin[0], lin[1], lin[2])
    }

    const val PATCH_COUNT = 24
}
