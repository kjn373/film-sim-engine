package app.filmengine.engine.node

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min

/**
 * Gaussian kernel weights shared by every backend — CPU and GPU must consume the
 * exact same float weights or the parity suite has no chance of holding.
 */
object Gaussian {
    /** Hard cap so GLSL can declare `float weights[MAX_RADIUS + 1]`. */
    const val MAX_RADIUS = 24

    /** Half-kernel weights `[0..radius]`, normalized so the full kernel sums to 1. */
    fun weights(sigma: Float): FloatArray {
        require(sigma > 0f) { "sigma must be positive" }
        val radius = min(ceil(3f * sigma).toInt(), MAX_RADIUS).coerceAtLeast(1)
        val w = FloatArray(radius + 1)
        val twoSigmaSq = 2f * sigma * sigma
        for (i in 0..radius) w[i] = exp(-(i * i) / twoSigmaSq)
        var sum = w[0]
        for (i in 1..radius) sum += 2f * w[i]
        for (i in 0..radius) w[i] /= sum
        return w
    }
}
