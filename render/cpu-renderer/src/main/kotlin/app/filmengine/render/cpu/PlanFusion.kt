package app.filmengine.render.cpu

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.BakedLut
import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.Step
import app.filmengine.engine.graph.ColorState
import app.filmengine.engine.graph.NodeRegistry
import kotlin.math.log2
import kotlin.math.pow

/**
 * Pass fusion (ARCHITECTURE.md D2): collapses each maximal run of >= 2 consecutive
 * fusable pointwise steps into one baked-3D-LUT step. Baking renders an N³ colour
 * lattice through the actual CPU kernels, so the LUT is exact at lattice points by
 * construction; between them the cost is trilinear interpolation error.
 *
 * Domain: scene-linear is unbounded, so lattice coordinates are shaped with
 * s = log2(x/black + 1) / log2(white/black + 1) — monotonic, EXACT at x = 0
 * (fused chains preserve true black), log-dense in the shadows. Inputs above
 * `white` (64 linear = +6 stops over 1.0) clamp to the LUT ceiling.
 */
object PlanFusion {

    fun fuse(
        plan: ExecutionPlan,
        registry: NodeRegistry,
        kernels: Map<String, CpuKernel>,
        lutSize: Int = 33,
        shaperBlack: Float = 0.001f,
        shaperWhite: Float = 64f,
    ): ExecutionPlan {
        val out = mutableListOf<Step>()
        val run = mutableListOf<Step>()

        fun flush() {
            if (run.size >= 2) {
                out += bake(run.toList(), kernels, lutSize, shaperBlack, shaperWhite)
            } else {
                out += run
            }
            run.clear()
        }

        for (step in plan.steps) {
            val fusable = step.lut == null && registry.descriptor(step.type).fusable
            // A run must START in scene-linear (the shaper's domain); once started,
            // any fusable continuation is fine — the bake evaluates the whole run.
            val canJoin = fusable && (run.isNotEmpty() || step.inputState == ColorState.SCENE_LINEAR)
            if (canJoin) {
                run += step
            } else {
                flush()
                out += step
            }
        }
        flush()
        return ExecutionPlan(out, plan.outputState)
    }

    private fun bake(
        steps: List<Step>,
        kernels: Map<String, CpuKernel>,
        n: Int,
        black: Float,
        white: Float,
    ): Step {
        val k = log2(white / black + 1f)
        val lattice = ImageBuffer.alloc(n * n * n, 1)
        var i = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    lattice.data[i] = black * (2f.pow(r / (n - 1f) * k) - 1f)
                    lattice.data[i + 1] = black * (2f.pow(g / (n - 1f) * k) - 1f)
                    lattice.data[i + 2] = black * (2f.pow(b / (n - 1f) * k) - 1f)
                    lattice.data[i + 3] = 1f
                    i += 4
                }
            }
        }
        val rendered = CpuBackend(kernels).render(ExecutionPlan(steps, ColorState.SCENE_LINEAR), lattice)
        return Step(
            nodeId = "fused:" + steps.joinToString("+") { it.nodeId },
            type = BakedLut.TYPE,
            params = mapOf(
                BakedLut.KEY_SIZE to n.toFloat(),
                BakedLut.KEY_SHAPER_BLACK to black,
                BakedLut.KEY_SHAPER_WHITE to white,
            ),
            inputState = steps.first().inputState,
            lut = BakedLut(n, rendered.data),
        )
    }

    /** CPU application of a baked LUT: shaper + manual trilinear, mirroring the GLSL. */
    val cpuKernel = CpuKernel { src, step ->
        val lut = step.lut ?: error("baked_lut step '${step.nodeId}' has no LUT payload")
        val n = lut.size
        val black = step.params.getValue(BakedLut.KEY_SHAPER_BLACK)
        val white = step.params.getValue(BakedLut.KEY_SHAPER_WHITE)
        val k = log2(white / black + 1f)
        val d = lut.data

        fun at(x: Int, y: Int, z: Int, c: Int) = d[((z * n + y) * n + x) * 4 + c]

        pointwise(src) { px ->
            val fx = log2(px[0].coerceIn(0f, white) / black + 1f) / k * (n - 1)
            val fy = log2(px[1].coerceIn(0f, white) / black + 1f) / k * (n - 1)
            val fz = log2(px[2].coerceIn(0f, white) / black + 1f) / k * (n - 1)
            val x0 = fx.toInt().coerceIn(0, n - 2)
            val y0 = fy.toInt().coerceIn(0, n - 2)
            val z0 = fz.toInt().coerceIn(0, n - 2)
            val tx = fx - x0
            val ty = fy - y0
            val tz = fz - z0
            for (c in 0..2) {
                val c00 = at(x0, y0, z0, c) * (1 - tx) + at(x0 + 1, y0, z0, c) * tx
                val c10 = at(x0, y0 + 1, z0, c) * (1 - tx) + at(x0 + 1, y0 + 1, z0, c) * tx
                val c01 = at(x0, y0, z0 + 1, c) * (1 - tx) + at(x0 + 1, y0, z0 + 1, c) * tx
                val c11 = at(x0, y0 + 1, z0 + 1, c) * (1 - tx) + at(x0 + 1, y0 + 1, z0 + 1, c) * tx
                px[c] = (c00 * (1 - ty) + c10 * ty) * (1 - tz) + (c01 * (1 - ty) + c11 * ty) * tz
            }
        }
    }
}
