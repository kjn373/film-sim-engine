package app.filmengine.render.cpu

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.BakedLut
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.FilmNodes
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanFusionTest {
    private val registry = NodeRegistry(BuiltinNodes.all + FilmNodes.all)
    private val kernels = BuiltinCpuKernels.all + FilmCpuKernels.all
    private val compiler = GraphCompiler(registry)
    private val backend = CpuBackend(kernels)

    private fun chain(vararg nodes: NodeInstance) = ProcessGraph(
        nodes = nodes.toList(),
        edges = nodes.toList().zipWithNext().map { (a, b) -> Edge(a.id, b.id) },
        outputNodeId = nodes.last().id,
    )

    private fun pointwiseGraph() = chain(
        NodeInstance("exp", "exposure", mapOf("stops" to 0.4f)),
        NodeInstance("wb", "white_balance", mapOf("r_gain" to 1.1f, "b_gain" to 0.92f)),
        NodeInstance("film", "film_sim", options = mapOf("stock" to "negato-400")),
        NodeInstance("sat", "saturation", mapOf("amount" to 1.2f)),
        NodeInstance("out", "srgb_output"),
    )

    private fun hdrTestImage(w: Int = 64, h: Int = 48): ImageBuffer {
        val img = ImageBuffer.alloc(w, h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 0.18f * 2f.pow(-6f + 10f * x / (w - 1))
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

    @Test
    fun `a pointwise chain fuses to one LUT step plus the exact output transform`() {
        val fused = PlanFusion.fuse(compiler.compile(pointwiseGraph()), registry, kernels)
        assertEquals(listOf(BakedLut.TYPE, "srgb_output"), fused.steps.map { it.type })
        assertTrue(fused.steps[0].lut != null)
        assertTrue("exp" in fused.steps[0].nodeId && "sat" in fused.steps[0].nodeId)
        assertEquals(fused.outputState, compiler.compile(pointwiseGraph()).outputState)
    }

    @Test
    fun `spatial and positional nodes break runs correctly`() {
        val graph = chain(
            NodeInstance("exp", "exposure", mapOf("stops" to 0.3f)),
            NodeInstance("blur", "gaussian_blur", mapOf("sigma" to 2f)),
            NodeInstance("film", "film_sim", options = mapOf("stock" to "chroma-100")),
            NodeInstance("sat", "saturation", mapOf("amount" to 1.1f)),
            NodeInstance("grn", "grain", mapOf("amount" to 0.3f)),
            NodeInstance("out", "srgb_output"),
        )
        val fused = PlanFusion.fuse(compiler.compile(graph), registry, kernels)
        // exposure alone (run of 1) stays direct; film+sat fuse; grain is positional;
        // srgb_output alone stays direct.
        assertEquals(
            listOf("exposure", "gaussian_blur", BakedLut.TYPE, "grain", "srgb_output"),
            fused.steps.map { it.type },
        )
        assertTrue("film" in fused.steps[2].nodeId && "sat" in fused.steps[2].nodeId)
    }

    @Test
    fun `fused output matches unfused within LUT tolerance`() {
        val plan = compiler.compile(pointwiseGraph())
        val fused = PlanFusion.fuse(plan, registry, kernels)
        val src = hdrTestImage()
        val direct = backend.render(plan, src)
        val viaLut = backend.render(fused, src)
        var worst = 0f
        var sum = 0.0
        for (i in direct.data.indices) {
            val d = abs(direct.data[i] - viaLut.data[i])
            if (d > worst) worst = d
            sum += d
        }
        val mean = (sum / direct.data.size).toFloat()
        assertTrue(worst < 0.015f, "worst fused deviation $worst exceeds 0.015")
        assertTrue(mean < 0.003f, "mean fused deviation $mean exceeds 0.003")
    }

    @Test
    fun `black maps exactly through a fused chain`() {
        val plan = compiler.compile(pointwiseGraph())
        val fused = PlanFusion.fuse(plan, registry, kernels)
        val black = ImageBuffer(1, 1, floatArrayOf(0f, 0f, 0f, 1f))
        val direct = backend.render(plan, black)
        val viaLut = backend.render(fused, black)
        for (c in 0..2) {
            assertTrue(
                abs(direct.data[c] - viaLut.data[c]) < 1e-4f,
                "black drifted: direct=${direct.data[c]} lut=${viaLut.data[c]}",
            )
        }
    }

    @Test
    fun `plans without fusable runs are untouched`() {
        val graph = chain(
            NodeInstance("grn", "grain", mapOf("amount" to 0.3f)),
            NodeInstance("blur", "gaussian_blur", mapOf("sigma" to 2f)),
        )
        val plan = compiler.compile(graph)
        val fused = PlanFusion.fuse(plan, registry, kernels)
        assertEquals(plan.steps, fused.steps)
    }
}
