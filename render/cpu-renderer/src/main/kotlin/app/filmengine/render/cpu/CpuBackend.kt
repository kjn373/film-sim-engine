package app.filmengine.render.cpu

import app.filmengine.color.ColorSpaces
import app.filmengine.color.Srgb
import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.RenderBackend
import kotlin.math.pow

/**
 * Reference CPU backend. Optimized for clarity, not speed — this is the correctness
 * baseline the GPU backend is parity-tested against (ARCHITECTURE.md §19).
 */
class CpuBackend(
    private val kernels: Map<String, CpuKernel> = BuiltinCpuKernels.all,
) : RenderBackend {

    override fun render(plan: ExecutionPlan, source: ImageBuffer): ImageBuffer {
        // copy() on the empty path so the result never aliases the caller's buffer
        if (plan.steps.isEmpty()) return source.copy()
        return plan.steps.fold(source) { img, step ->
            val kernel = kernels[step.type]
                ?: error("No CPU kernel registered for node type '${step.type}'")
            kernel.apply(img, step)
        }
    }
}

/** Contract: must return a fresh buffer and leave [src] unmodified. */
fun interface CpuKernel {
    fun apply(src: ImageBuffer, step: app.filmengine.engine.exec.Step): ImageBuffer
}

/** Shared per-pixel RGB loop: copies [src], applies [f] to each pixel's first 3 channels. */
internal inline fun pointwise(src: ImageBuffer, f: (FloatArray) -> Unit): ImageBuffer {
    val out = src.copy()
    val d = out.data
    val px = FloatArray(3)
    var i = 0
    while (i < d.size) {
        px[0] = d[i]; px[1] = d[i + 1]; px[2] = d[i + 2]
        f(px)
        d[i] = px[0]; d[i + 1] = px[1]; d[i + 2] = px[2]
        i += 4
    }
    return out
}

object BuiltinCpuKernels {

    val exposure = CpuKernel { src, step ->
        val gain = 2f.pow(step.params.getValue("stops"))
        pointwise(src) { px -> px[0] *= gain; px[1] *= gain; px[2] *= gain }
    }

    val whiteBalance = CpuKernel { src, step ->
        val r = step.params.getValue("r_gain")
        val b = step.params.getValue("b_gain")
        pointwise(src) { px -> px[0] *= r; px[2] *= b }
    }

    val colorMatrix = CpuKernel { src, step ->
        val p = step.params
        val mat = app.filmengine.color.Mat3(FloatArray(9) { p.getValue("m${it / 3}${it % 3}") })
        pointwise(src) { px -> mat.transform(px) }
    }

    /** Slope through 18% grey: out = pivot * (x/pivot)^contrast, per channel. */
    val toneCurve = CpuKernel { src, step ->
        val c = step.params.getValue("contrast")
        val pivot = 0.18f
        pointwise(src) { px ->
            for (i in 0..2) {
                px[i] = if (px[i] <= 0f) 0f else pivot * (px[i] / pivot).pow(c)
            }
        }
    }

    val saturation = CpuKernel { src, step ->
        val amt = step.params.getValue("amount")
        pointwise(src) { px ->
            val luma = ColorSpaces.LUMA_R * px[0] + ColorSpaces.LUMA_G * px[1] + ColorSpaces.LUMA_B * px[2]
            for (i in 0..2) px[i] = luma + (px[i] - luma) * amt
        }
    }

    /** Linear Rec.2020 working space -> encoded sRGB, clamped to [0,1]. */
    val srgbOutput = CpuKernel { src, _ ->
        pointwise(src) { px ->
            ColorSpaces.REC2020_TO_SRGB.transform(px)
            for (i in 0..2) px[i] = Srgb.oetf(px[i].coerceIn(0f, 1f))
        }
    }

    val all: Map<String, CpuKernel> = mapOf(
        "exposure" to exposure,
        "white_balance" to whiteBalance,
        "color_matrix" to colorMatrix,
        "tone_curve" to toneCurve,
        "saturation" to saturation,
        "srgb_output" to srgbOutput,
        "gaussian_blur" to SpatialCpuKernels.gaussianBlur,
        "bloom" to SpatialCpuKernels.bloom,
    )
}
