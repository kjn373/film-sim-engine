package app.filmengine.profile

import app.filmengine.color.Mat3
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
 * Reference XYZ (D65) for the classic 24-patch ColorChecker, row-major
 * top-left → bottom-right — the actual measured chart data, not a rendition.
 *
 * Source: the official GretagMacbeth "ColorChecker 2005" L*a*b* (D50)
 * reference values (Danny Pascale / BabelColor, "RGB coordinates of the
 * Macbeth ColorChecker", Table 2, rev. 2006-06-01 — the same data used by
 * dcraw/ArgyllCMS/DNG-style camera calibration). Converted to XYZ D65 via the
 * standard Lab→XYZ transform plus the paper's own Bradford D50→D65 matrix
 * (Table 7), matching this engine's D65 working space.
 */
object ColorCheckerReference {

    // L*, a*, b* per patch, GretagMacbeth 2005 official values.
    private val LAB_D50 = listOf(
        floatArrayOf(37.99f, 13.56f, 14.06f),   // dark skin
        floatArrayOf(65.71f, 18.13f, 17.81f),   // light skin
        floatArrayOf(49.93f, -4.88f, -21.93f),  // blue sky
        floatArrayOf(43.14f, -13.10f, 21.91f),  // foliage
        floatArrayOf(55.11f, 8.84f, -25.40f),   // blue flower
        floatArrayOf(70.72f, -33.40f, -0.20f),  // bluish green
        floatArrayOf(62.66f, 36.07f, 57.10f),   // orange
        floatArrayOf(40.02f, 10.41f, -45.96f),  // purplish blue
        floatArrayOf(51.12f, 48.24f, 16.25f),   // moderate red
        floatArrayOf(30.33f, 22.98f, -21.59f),  // purple
        floatArrayOf(72.53f, -23.71f, 57.26f),  // yellow green
        floatArrayOf(71.94f, 19.36f, 67.86f),   // orange yellow
        floatArrayOf(28.78f, 14.18f, -50.30f),  // blue
        floatArrayOf(55.26f, -38.34f, 31.37f),  // green
        floatArrayOf(42.10f, 53.38f, 28.19f),   // red
        floatArrayOf(81.73f, 4.04f, 79.82f),    // yellow
        floatArrayOf(51.94f, 49.99f, -14.57f),  // magenta
        floatArrayOf(51.04f, -28.63f, -28.64f), // cyan
        floatArrayOf(96.54f, -0.425f, 1.186f),  // white 9.5 (.05 D)
        floatArrayOf(81.26f, -0.638f, -0.335f), // neutral 8 (.23 D)
        floatArrayOf(66.77f, -0.734f, -0.504f), // neutral 6.5 (.44 D)
        floatArrayOf(50.87f, -0.153f, -0.270f), // neutral 5 (.70 D)
        floatArrayOf(35.66f, -0.421f, -1.231f), // neutral 3.5 (1.05 D)
        floatArrayOf(20.46f, -0.079f, -0.973f), // black 2 (1.5 D)
    )

    // CIE D50 reference white (2° observer), Y normalized to 1.
    private const val D50_XN = 0.9642f
    private const val D50_YN = 1.0f
    private const val D50_ZN = 0.8249f

    private fun labToXyzD50(lab: FloatArray): FloatArray {
        val fy = (lab[0] + 16f) / 116f
        val fx = fy + lab[1] / 500f
        val fz = fy - lab[2] / 200f
        fun finv(t: Float) = if (t > 6f / 29f) t * t * t else 3f * (6f / 29f) * (6f / 29f) * (t - 4f / 29f)
        return floatArrayOf(D50_XN * finv(fx), D50_YN * finv(fy), D50_ZN * finv(fz))
    }

    // Bradford D50->D65 chromatic adaptation matrix (Pascale/BabelColor Table 7).
    private val BRADFORD_D50_TO_D65 = Mat3(
        floatArrayOf(
            0.9555766f, -0.0230393f, 0.0631636f,
            -0.0282895f, 1.0099416f, 0.0210077f,
            0.0122982f, -0.0204830f, 1.3299098f,
        )
    )

    val XYZ_D65: List<FloatArray> = LAB_D50.map { lab ->
        val xyzD50 = labToXyzD50(lab)
        BRADFORD_D50_TO_D65.transform(xyzD50[0], xyzD50[1], xyzD50[2])
    }

    const val PATCH_COUNT = 24
}
