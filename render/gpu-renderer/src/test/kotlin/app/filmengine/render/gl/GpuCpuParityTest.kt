package app.filmengine.render.gl

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.FilmNodes
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE contract between backends: every node type must produce the same image
 * on GPU as on the CPU reference, within [TOLERANCE] (float + driver noise).
 */
class GpuCpuParityTest {
    private val compiler = GraphCompiler(NodeRegistry(BuiltinNodes.all + FilmNodes.all))
    private val cpu = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)
    private val gpu = GlRenderBackend()

    /** HDR test card: -6..+4 stop exposure ramp horizontally, hue variation vertically. */
    private fun hdrTestImage(w: Int = 64, h: Int = 48): ImageBuffer {
        val img = ImageBuffer.alloc(w, h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val stops = -6f + 10f * x / (w - 1)
                val base = 0.18f * 2f.pow(stops)
                val phase = 2f * Math.PI.toFloat() * y / h
                img.data[i] = base * (0.55f + 0.45f * sin(phase))
                img.data[i + 1] = base * (0.55f + 0.45f * sin(phase + 2.1f))
                img.data[i + 2] = base * (0.55f + 0.45f * sin(phase + 4.2f))
                img.data[i + 3] = 1f
                i += 4
            }
        }
        return img
    }

    private fun assertParity(graph: ProcessGraph, label: String, tolerance: Float = TOLERANCE) {
        val src = hdrTestImage()
        val plan = compiler.compile(graph)
        val cpuOut = cpu.render(plan, src)
        val gpuOut = gpu.render(plan, src)
        var worst = 0f
        for (i in cpuOut.data.indices) {
            val d = abs(cpuOut.data[i] - gpuOut.data[i])
            if (d > worst) worst = d
        }
        assertTrue(worst < tolerance, "$label: max CPU/GPU deviation $worst exceeds $tolerance")
    }

    private fun single(type: String, params: Map<String, Float> = emptyMap(), options: Map<String, String> = emptyMap()) =
        ProcessGraph(listOf(NodeInstance("n", type, params, options = options)), emptyList(), "n")

    @Test
    fun `every pointwise node matches the CPU reference`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        assertParity(single("exposure", mapOf("stops" to 0.7f)), "exposure")
        assertParity(single("white_balance", mapOf("r_gain" to 1.3f, "b_gain" to 0.7f)), "white_balance")
        assertParity(
            single(
                "color_matrix",
                mapOf(
                    "m00" to 0.9f, "m01" to 0.08f, "m02" to 0.02f,
                    "m10" to 0.05f, "m11" to 0.9f, "m12" to 0.05f,
                    "m20" to 0.02f, "m21" to 0.08f, "m22" to 0.9f,
                )
            ),
            "color_matrix"
        )
        assertParity(single("tone_curve", mapOf("contrast" to 1.4f)), "tone_curve")
        assertParity(single("tone_map", mapOf("exposure_bias" to 0.5f)), "tone_map")
        assertParity(single("saturation", mapOf("amount" to 1.6f)), "saturation")
        assertParity(single("srgb_output"), "srgb_output")
    }

    @Test
    fun `every film stock matches the CPU reference`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        for (stockId in listOf("chroma-100", "negato-400", "mono-400")) {
            assertParity(
                single("film_sim", mapOf("push" to 0.5f), mapOf("stock" to stockId)),
                "film_sim/$stockId"
            )
        }
    }

    @Test
    fun `a full chain matches the CPU reference`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        val graph = ProcessGraph(
            nodes = listOf(
                NodeInstance("exp", "exposure", mapOf("stops" to 0.4f)),
                NodeInstance("wb", "white_balance", mapOf("r_gain" to 1.1f, "b_gain" to 0.92f)),
                NodeInstance("film", "film_sim", mapOf("strength" to 0.85f), options = mapOf("stock" to "negato-400")),
                NodeInstance("out", "srgb_output"),
            ),
            edges = listOf(Edge("exp", "wb"), Edge("wb", "film"), Edge("film", "out")),
            outputNodeId = "out",
        )
        assertParity(graph, "full-chain")
    }

    @Test
    fun `every spatial node matches the CPU reference`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        assertParity(single("gaussian_blur", mapOf("sigma" to 3f)), "gaussian_blur")
        assertParity(
            single("bloom", mapOf("threshold" to 0.8f, "intensity" to 0.7f, "sigma" to 4f)),
            "bloom"
        )
        assertParity(
            single("halation", mapOf("threshold" to 0.9f, "strength" to 1.2f, "sigma" to 5f)),
            "halation"
        )
        assertParity(single("grain", mapOf("amount" to 0.6f, "seed" to 7f)), "grain")
    }

    @Test
    fun `a fused LUT plan matches the CPU fused reference`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        val graph = ProcessGraph(
            nodes = listOf(
                NodeInstance("exp", "exposure", mapOf("stops" to 0.4f)),
                NodeInstance("film", "film_sim", options = mapOf("stock" to "chroma-100")),
                NodeInstance("sat", "saturation", mapOf("amount" to 1.2f)),
                NodeInstance("out", "srgb_output"),
            ),
            edges = listOf(Edge("exp", "film"), Edge("film", "sat"), Edge("sat", "out")),
            outputNodeId = "out",
        )
        val fused = app.filmengine.render.cpu.PlanFusion.fuse(
            compiler.compile(graph),
            NodeRegistry(BuiltinNodes.all + FilmNodes.all),
            BuiltinCpuKernels.all + FilmCpuKernels.all,
        )
        val src = hdrTestImage()
        val cpuOut = cpu.render(fused, src)
        val gpuOut = gpu.render(fused, src)
        var worst = 0f
        for (i in cpuOut.data.indices) {
            val d = abs(cpuOut.data[i] - gpuOut.data[i])
            if (d > worst) worst = d
        }
        // Looser than pointwise parity: hardware trilinear filtering precision varies.
        assertTrue(worst < 1.5e-2f, "fused plan CPU/GPU deviation $worst exceeds 1.5e-2")
    }

    @Test
    fun `a full creative chain with spatial nodes matches the CPU reference`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        val graph = ProcessGraph(
            nodes = listOf(
                NodeInstance("exp", "exposure", mapOf("stops" to 0.3f)),
                NodeInstance("film", "film_sim", options = mapOf("stock" to "chroma-100")),
                NodeInstance("hal", "halation", mapOf("threshold" to 0.8f, "strength" to 0.8f, "sigma" to 4f)),
                NodeInstance("blm", "bloom", mapOf("threshold" to 0.7f, "intensity" to 0.4f, "sigma" to 3f)),
                NodeInstance("grn", "grain", mapOf("amount" to 0.35f, "seed" to 3f)),
                NodeInstance("out", "srgb_output"),
            ),
            edges = listOf(
                Edge("exp", "film"), Edge("film", "hal"), Edge("hal", "blm"),
                Edge("blm", "grn"), Edge("grn", "out"),
            ),
            outputNodeId = "out",
        )
        assertParity(graph, "creative-chain")
    }

    @Test
    fun `tiled GPU rendering matches whole-image GPU rendering`() {
        assumeTrue(GlContext.available, "no OpenGL context on this machine")
        val graph = ProcessGraph(
            nodes = listOf(
                NodeInstance("blur", "gaussian_blur", mapOf("sigma" to 2f)),
                NodeInstance("grn", "grain", mapOf("amount" to 0.4f, "seed" to 5f)),
                NodeInstance("out", "srgb_output"),
            ),
            edges = listOf(Edge("blur", "grn"), Edge("grn", "out")),
            outputNodeId = "out",
        )
        val plan = compiler.compile(graph)
        val src = hdrTestImage()
        val whole = gpu.render(plan, src)
        val tiled = app.filmengine.engine.exec.TiledRenderer(gpu, tileSize = 24).render(plan, src)
        var worst = 0f
        for (i in whole.data.indices) {
            val d = abs(whole.data[i] - tiled.data[i])
            if (d > worst) worst = d
        }
        assertTrue(worst < 1e-6f, "tiled GPU deviates $worst from whole-image GPU")
    }

    private companion object {
        // Absolute, on data spanning ~0..3 linear: generous enough for driver
        // transcendental variance, tight enough to catch any real math drift.
        const val TOLERANCE = 2e-3f
    }
}
