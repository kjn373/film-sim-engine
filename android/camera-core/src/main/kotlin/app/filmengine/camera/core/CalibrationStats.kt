package app.filmengine.camera.core

import app.filmengine.profile.FrameStats

/**
 * Per-frame statistics over a raw Bayer mosaic — the on-device half of B7.
 * Produces the [FrameStats] rows that `tooling/profile-calibrator` turns into
 * a DeviceProfile. Pure Kotlin: the pixel source is a lambda, so tests feed
 * synthetic mosaics and the controller feeds an ImageProxy plane.
 */
object CalibrationStats {

    /** Channel (0=R, 1=G, 2=B) per 2×2 CFA site, indexed by
     *  CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0..3). */
    private val CFA_PATTERNS = arrayOf(
        intArrayOf(0, 1, 1, 2), // RGGB
        intArrayOf(1, 0, 2, 1), // GRBG
        intArrayOf(1, 2, 0, 1), // GBRG
        intArrayOf(2, 1, 1, 0), // BGGR
    )

    /** ColorChecker geometry: 6×4 patches assumed to fill the central 80% of a
     *  landscape frame; each patch sampled over its inner half to dodge borders. */
    private const val CHART_COLS = 6
    private const val CHART_ROWS = 4
    private const val CHART_SPAN = 0.8f

    private const val RADIAL_BINS = 12

    fun compute(
        kind: String,
        iso: Int,
        exposureNs: Long,
        cct: Float,
        width: Int,
        height: Int,
        cfa: Int,
        sample: (x: Int, y: Int) -> Int,
    ): FrameStats {
        val pattern = CFA_PATTERNS.getOrElse(cfa) { CFA_PATTERNS[0] }
        fun channel(x: Int, y: Int) = pattern[(y and 1) * 2 + (x and 1)]

        // whole-frame per-channel mean + variance
        val sum = DoubleArray(3)
        val sumSq = DoubleArray(3)
        val count = LongArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = channel(x, y)
                val v = sample(x, y).toDouble()
                sum[c] += v
                sumSq[c] += v * v
                count[c]++
            }
        }
        val means = List(3) { (sum[it] / count[it]).toFloat() }
        val variances = List(3) { (sumSq[it] / count[it] - (sum[it] / count[it]) * (sum[it] / count[it])).toFloat() }

        return FrameStats(
            kind = kind, iso = iso, exposureNs = exposureNs, cct = cct,
            means = means, variances = variances,
            patches = if (kind == "chart") patchMeans(width, height, ::channel, sample) else emptyList(),
            radialGains = if (kind == "flat") radialGains(width, height, ::channel, sample) else emptyList(),
        )
    }

    private inline fun patchMeans(
        width: Int, height: Int,
        channel: (Int, Int) -> Int,
        sample: (Int, Int) -> Int,
    ): List<List<Float>> {
        val x0 = width * (1f - CHART_SPAN) / 2f
        val y0 = height * (1f - CHART_SPAN) / 2f
        val cellW = width * CHART_SPAN / CHART_COLS
        val cellH = height * CHART_SPAN / CHART_ROWS
        return (0 until CHART_ROWS * CHART_COLS).map { p ->
            val col = p % CHART_COLS
            val row = p / CHART_COLS
            // inner half of the cell
            val xa = (x0 + cellW * (col + 0.25f)).toInt()
            val xb = (x0 + cellW * (col + 0.75f)).toInt()
            val ya = (y0 + cellH * (row + 0.25f)).toInt()
            val yb = (y0 + cellH * (row + 0.75f)).toInt()
            val s = DoubleArray(3)
            val n = LongArray(3)
            for (y in ya until yb) for (x in xa until xb) {
                val c = channel(x, y)
                s[c] += sample(x, y).toDouble()
                n[c]++
            }
            List(3) { if (n[it] > 0) (s[it] / n[it]).toFloat() else 0f }
        }
    }

    /** Green-channel mean per radius bin, as gain relative to the center bin. */
    private inline fun radialGains(
        width: Int, height: Int,
        channel: (Int, Int) -> Int,
        sample: (Int, Int) -> Int,
    ): List<List<Float>> {
        val cx = width / 2f
        val cy = height / 2f
        val rMax = kotlin.math.sqrt(cx * cx + cy * cy)
        val sums = DoubleArray(RADIAL_BINS)
        val counts = LongArray(RADIAL_BINS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (channel(x, y) != 1) continue
                val dx = x - cx
                val dy = y - cy
                val bin = (kotlin.math.sqrt(dx * dx + dy * dy) / rMax * RADIAL_BINS)
                    .toInt().coerceAtMost(RADIAL_BINS - 1)
                sums[bin] += sample(x, y).toDouble()
                counts[bin]++
            }
        }
        val center = if (counts[0] > 0) sums[0] / counts[0] else return emptyList()
        return (0 until RADIAL_BINS).filter { counts[it] > 0 }.map { bin ->
            listOf((bin + 0.5f) / RADIAL_BINS, (sums[bin] / counts[bin] / center).toFloat())
        }
    }
}
