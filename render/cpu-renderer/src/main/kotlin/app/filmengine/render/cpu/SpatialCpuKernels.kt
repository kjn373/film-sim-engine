package app.filmengine.render.cpu

import app.filmengine.color.ColorSpaces
import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.node.Gaussian
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Spatial reference kernels. Loop order and edge handling (clamp) deliberately
 * mirror the GPU passes — both consume the same Gaussian.weights().
 */
object SpatialCpuKernels {

    /** Separable Gaussian: horizontal then vertical, clamp-to-edge, RGBA. */
    internal fun blurSeparable(src: ImageBuffer, weights: FloatArray): ImageBuffer {
        val radius = weights.size - 1
        val w = src.width
        val h = src.height
        val tmp = ImageBuffer.alloc(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (i in -radius..radius) {
                    val si = (y * w + (x + i).coerceIn(0, w - 1)) * 4
                    val wt = weights[abs(i)]
                    r += wt * src.data[si]
                    g += wt * src.data[si + 1]
                    b += wt * src.data[si + 2]
                    a += wt * src.data[si + 3]
                }
                val di = (y * w + x) * 4
                tmp.data[di] = r; tmp.data[di + 1] = g; tmp.data[di + 2] = b; tmp.data[di + 3] = a
            }
        }
        val out = ImageBuffer.alloc(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (i in -radius..radius) {
                    val si = ((y + i).coerceIn(0, h - 1) * w + x) * 4
                    val wt = weights[abs(i)]
                    r += wt * tmp.data[si]
                    g += wt * tmp.data[si + 1]
                    b += wt * tmp.data[si + 2]
                    a += wt * tmp.data[si + 3]
                }
                val di = (y * w + x) * 4
                out.data[di] = r; out.data[di + 1] = g; out.data[di + 2] = b; out.data[di + 3] = a
            }
        }
        return out
    }

    private fun brightPass(src: ImageBuffer, threshold: Float): ImageBuffer =
        pointwise(src) { px -> for (i in 0..2) px[i] = max(px[i] - threshold, 0f) }

    private fun addScaled(base: ImageBuffer, add: ImageBuffer, rs: Float, gs: Float, bs: Float): ImageBuffer {
        val out = base.copy()
        var i = 0
        while (i < out.data.size) {
            out.data[i] += add.data[i] * rs
            out.data[i + 1] += add.data[i + 1] * gs
            out.data[i + 2] += add.data[i + 2] * bs
            i += 4
        }
        return out
    }

    val gaussianBlur = CpuKernel { src, step ->
        blurSeparable(src, Gaussian.weights(step.params.getValue("sigma")))
    }

    val bloom = CpuKernel { src, step ->
        val blurred = blurSeparable(
            brightPass(src, step.params.getValue("threshold")),
            Gaussian.weights(step.params.getValue("sigma")),
        )
        val k = step.params.getValue("intensity")
        addScaled(src, blurred, k, k, k)
    }

    /** Halation tint: strongly red, faintly orange — the film-base glow signature. */
    val halation = CpuKernel { src, step ->
        val blurred = blurSeparable(
            brightPass(src, step.params.getValue("threshold")),
            Gaussian.weights(step.params.getValue("sigma")),
        )
        val s = step.params.getValue("strength")
        addScaled(src, blurred, 1.0f * s, 0.35f * s, 0.10f * s)
    }

    /**
     * Integer-hash noise, bit-identical to the GLSL implementation (uint arithmetic
     * wraps exactly like Kotlin Int). Luminance response peaks in the midtones.
     */
    val grain = CpuKernel { src, step ->
        val amount = step.params.getValue("amount")
        val seed = step.params.getValue("seed").toInt()
        val out = src.copy()
        val w = src.width
        for (y in 0 until src.height) {
            for (x in 0 until w) {
                var hsh = x * 1664525 + y * 1013904223 + seed
                hsh = hsh xor (hsh ushr 16)
                hsh *= 0x45d9f3b
                hsh = hsh xor (hsh ushr 16)
                hsh *= 0x45d9f3b
                hsh = hsh xor (hsh ushr 16)
                val n = (hsh and 0xFFFFFF) / 16777216f

                val i = (y * w + x) * 4
                val r = out.data[i]; val g = out.data[i + 1]; val b = out.data[i + 2]
                val luma = (ColorSpaces.LUMA_R * r + ColorSpaces.LUMA_G * g + ColorSpaces.LUMA_B * b)
                    .coerceIn(0f, 1f)
                val response = 2f * sqrt(luma * (1f - luma))
                val f = 1f + amount * (n - 0.5f) * response
                out.data[i] = r * f
                out.data[i + 1] = g * f
                out.data[i + 2] = b * f
            }
        }
        out
    }
}
