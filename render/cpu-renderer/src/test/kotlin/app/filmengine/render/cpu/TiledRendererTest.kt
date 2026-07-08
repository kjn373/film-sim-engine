package app.filmengine.render.cpu

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.exec.TiledRenderer
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.FilmNodes
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiledRendererTest {
    private val registry = NodeRegistry(BuiltinNodes.all + FilmNodes.all)
    private val kernels = BuiltinCpuKernels.all + FilmCpuKernels.all
    private val compiler = GraphCompiler(registry)
    private val backend = CpuBackend(kernels)

    private fun chain(vararg nodes: NodeInstance) = ProcessGraph(
        nodes = nodes.toList(),
        edges = nodes.toList().zipWithNext().map { (a, b) -> Edge(a.id, b.id) },
        outputNodeId = nodes.last().id,
    )

    private fun creativeGraph() = chain(
        NodeInstance("exp", "exposure", mapOf("stops" to 0.3f)),
        NodeInstance("film", "film_sim", options = mapOf("stock" to "chroma-100")),
        NodeInstance("hal", "halation", mapOf("threshold" to 0.8f, "strength" to 0.8f, "sigma" to 4f)),
        NodeInstance("blm", "bloom", mapOf("threshold" to 0.7f, "intensity" to 0.4f, "sigma" to 3f)),
        NodeInstance("grn", "grain", mapOf("amount" to 0.35f, "seed" to 3f)),
        NodeInstance("out", "srgb_output"),
    )

    private fun testImage(w: Int = 96, h: Int = 72): ImageBuffer {
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
    fun `margin accumulates over the plan's spatial steps`() {
        // halation sigma 4 -> radius 12; bloom sigma 3 -> radius 9
        assertEquals(21, compiler.compile(creativeGraph()).tileMargin)
    }

    @Test
    fun `tiled render is bit-identical to a whole-image render`() {
        val plan = compiler.compile(creativeGraph())
        val src = testImage()
        val whole = backend.render(plan, src)
        val tiled = TiledRenderer(backend, tileSize = 32).render(plan, src)
        for (i in whole.data.indices) {
            assertTrue(
                whole.data[i] == tiled.data[i],
                "index $i differs: whole=${whole.data[i]} tiled=${tiled.data[i]}",
            )
        }
    }

    @Test
    fun `tiled fused plan is bit-identical too`() {
        val plan = PlanFusion.fuse(compiler.compile(creativeGraph()), registry, kernels)
        assertEquals(21, plan.tileMargin, "fusion must preserve the margin")
        val src = testImage()
        val whole = backend.render(plan, src)
        val tiled = TiledRenderer(backend, tileSize = 32).render(plan, src)
        for (i in whole.data.indices) {
            assertTrue(whole.data[i] == tiled.data[i], "index $i differs")
        }
    }

    @Test
    fun `small images bypass tiling`() {
        val plan = compiler.compile(creativeGraph())
        val src = testImage(24, 20)
        val whole = backend.render(plan, src)
        val tiled = TiledRenderer(backend, tileSize = 32).render(plan, src)
        for (i in whole.data.indices) {
            assertTrue(whole.data[i] == tiled.data[i])
        }
    }
}
