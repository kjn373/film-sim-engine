package app.filmengine.render.cpu

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.BuiltinStocks
import app.filmengine.film.FilmNodes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilmKernelTest {
    private val compiler = GraphCompiler(NodeRegistry(BuiltinNodes.all + FilmNodes.all))
    private val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)

    private fun greyCard(v: Float) = ImageBuffer(1, 1, floatArrayOf(v, v, v, 1f))

    private fun filmGraph(stock: String, params: Map<String, Float> = emptyMap()) = ProcessGraph(
        listOf(NodeInstance("f", "film_sim", params, options = mapOf("stock" to stock))),
        emptyList(), "f",
    )

    @Test
    fun `18 percent grey survives every stock unchanged`() {
        // sensitivity/dye rows sum to 1 and the curve is grey-anchored, so this
        // must hold exactly — it is the neutrality contract of the film model.
        for (stock in BuiltinStocks.all) {
            val out = backend.render(compiler.compile(filmGraph(stock.id)), greyCard(0.18f))
            for (c in 0..2) {
                assertTrue(
                    abs(out.data[c] - 0.18f) < 1e-4f,
                    "${stock.id}: grey drifted to ${out.data.toList().take(3)}"
                )
            }
        }
    }

    @Test
    fun `strength zero is identity`() {
        val src = ImageBuffer(1, 1, floatArrayOf(0.4f, 0.25f, 0.1f, 1f))
        val out = backend.render(
            compiler.compile(filmGraph("chroma-100", mapOf("strength" to 0f))), src
        )
        for (c in 0..2) assertTrue(abs(out.data[c] - src.data[c]) < 1e-6f)
    }

    @Test
    fun `mono stock produces equal channels`() {
        val src = ImageBuffer(1, 1, floatArrayOf(0.5f, 0.2f, 0.05f, 1f))
        val out = backend.render(compiler.compile(filmGraph("mono-400")), src)
        assertTrue(abs(out.data[0] - out.data[1]) < 1e-5f)
        assertTrue(abs(out.data[1] - out.data[2]) < 1e-5f)
    }

    @Test
    fun `push brightens the output`() {
        val src = greyCard(0.1f)
        val normal = backend.render(compiler.compile(filmGraph("negato-400")), src)
        val pushed = backend.render(
            compiler.compile(filmGraph("negato-400", mapOf("push" to 1f))), src
        )
        assertTrue(pushed.data[1] > normal.data[1], "push did not brighten")
    }

    @Test
    fun `unknown stock id fails loudly`() {
        assertFailsWith<IllegalStateException> {
            backend.render(compiler.compile(filmGraph("kodak-gold")), greyCard(0.18f))
        }
    }

    @Test
    fun `missing stock option fails loudly instead of silently defaulting`() {
        val graph = ProcessGraph(
            listOf(NodeInstance("f", "film_sim")), emptyList(), "f",
        )
        assertFailsWith<IllegalStateException> {
            backend.render(compiler.compile(graph), greyCard(0.18f))
        }
    }

    @Test
    fun `golden - warm pixel through chroma-100 matches hand-derived model`() {
        val src = ImageBuffer(1, 1, floatArrayOf(0.40f, 0.25f, 0.10f, 1f))
        val out = backend.render(compiler.compile(filmGraph("chroma-100")), src)

        // Independent double-precision re-derivation of the stock model
        // (formulas transcribed from ARCHITECTURE.md §6, not from the kernel).
        val gamma = 1.2; val toe = 0.8; val shoulder = 1.0
        val black = 0.001; val white = 1.0
        val s0 = (0.18 - black) / (white - black)
        val k = 1.0 / s0 - 1.0
        fun curve(x: Double): Double {
            val t = (Math.log(x / 0.18) / Math.log(2.0)) * gamma
            val tt = if (t >= 0) t / shoulder else t / toe
            return black + (white - black) / (1.0 + k * Math.pow(2.0, -tt))
        }
        val r = curve(0.40); val g = curve(0.25); val b = curve(0.10)
        // dye matrix of chroma-100, then saturation 1.25
        val rd = 1.02 * r + 0.00 * g - 0.02 * b
        val gd = -0.01 * r + 1.02 * g - 0.01 * b
        val bd = -0.02 * r + 0.00 * g + 1.02 * b
        val luma = 0.2627 * rd + 0.6780 * gd + 0.0593 * bd
        val expected = listOf(
            luma + (rd - luma) * 1.25,
            luma + (gd - luma) * 1.25,
            luma + (bd - luma) * 1.25,
        )
        for (c in 0..2) {
            assertTrue(
                abs(out.data[c] - expected[c].toFloat()) < 1e-3f,
                "channel $c: got ${out.data[c]}, expected ${expected[c]}"
            )
        }
    }
}
