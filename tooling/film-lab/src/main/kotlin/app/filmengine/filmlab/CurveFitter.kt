package app.filmengine.filmlab

import app.filmengine.film.CharacteristicCurve
import kotlin.math.pow

/**
 * Fits CharacteristicCurve shape parameters (gamma / toe / shoulder) to measured
 * samples — datasheet H&D points or (log exposure, scanned value) pairs. Black and
 * white points are supplied, not fitted: they are read directly off the scan's
 * min/max, which is far more robust than co-fitting five parameters.
 */
// ponytail: coarse grid + coordinate-descent refinement — derivative-free, no deps,
// deterministic. Swap for Levenberg-Marquardt only if real scan data proves noisy
// enough to need it.
object CurveFitter {

    /** One measurement: exposure in stops relative to 18% grey -> linear output [0,1]. */
    data class Sample(val stops: Float, val value: Float)

    data class Fit(val curve: CharacteristicCurve, val rmse: Float)

    fun fit(samples: List<Sample>, black: Float, white: Float): Fit {
        require(samples.size >= 5) { "Need at least 5 samples to fit 3 parameters" }

        fun error(gamma: Float, toe: Float, shoulder: Float): Float {
            val curve = CharacteristicCurve(gamma, toe, shoulder, black, white)
            var sum = 0.0
            for (s in samples) {
                val x = CharacteristicCurve.MID_GREY * 2f.pow(s.stops)
                val d = curve.eval(x) - s.value
                sum += d * d
            }
            return (sum / samples.size).toFloat()
        }

        // Coarse grid over the plausible parameter box.
        var bestG = 1f; var bestT = 1f; var bestS = 1f
        var best = Float.MAX_VALUE
        val gammas = grid(0.3f, 2.5f, 12)
        val shapes = grid(0.3f, 3.0f, 12)
        for (g in gammas) for (t in shapes) for (sh in shapes) {
            val e = error(g, t, sh)
            if (e < best) { best = e; bestG = g; bestT = t; bestS = sh }
        }

        // Coordinate descent: halve the step, walk each axis while it improves.
        var step = 0.12f
        repeat(6) {
            var improved = true
            while (improved) {
                improved = false
                for ((dg, dt, ds) in listOf(
                    Triple(step, 0f, 0f), Triple(-step, 0f, 0f),
                    Triple(0f, step, 0f), Triple(0f, -step, 0f),
                    Triple(0f, 0f, step), Triple(0f, 0f, -step),
                )) {
                    val g = (bestG + dg).coerceIn(0.1f, 3f)
                    val t = (bestT + dt).coerceIn(0.1f, 4f)
                    val s = (bestS + ds).coerceIn(0.1f, 4f)
                    val e = error(g, t, s)
                    if (e < best) {
                        best = e; bestG = g; bestT = t; bestS = s
                        improved = true
                    }
                }
            }
            step /= 2f
        }

        return Fit(CharacteristicCurve(bestG, bestT, bestS, black, white), kotlin.math.sqrt(best))
    }

    private fun grid(from: Float, to: Float, n: Int): List<Float> =
        (0 until n).map { from + (to - from) * it / (n - 1) }
}
