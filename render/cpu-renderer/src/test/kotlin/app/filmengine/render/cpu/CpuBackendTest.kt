package app.filmengine.render.cpu

import app.filmengine.color.Srgb
import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.ProcessGraph
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class CpuBackendTest {
    private val compiler = GraphCompiler()
    private val backend = CpuBackend()

    /** 2x2 test card: 18% grey, deep shadow, warm tone, near-white. */
    private fun testCard(): ImageBuffer = ImageBuffer(
        2, 2,
        floatArrayOf(
            0.18f, 0.18f, 0.18f, 1f,
            0.02f, 0.02f, 0.02f, 1f,
            0.40f, 0.25f, 0.10f, 1f,
            0.95f, 0.95f, 0.95f, 1f,
        )
    )

    private fun render(graph: ProcessGraph, src: ImageBuffer) =
        backend.render(compiler.compile(graph), src)

    private fun assertPixel(expected: FloatArray, actual: FloatArray, tol: Float = 1e-4f) {
        for (i in 0..2) {
            assertTrue(
                abs(expected[i] - actual[i]) < tol,
                "channel $i: expected ${expected[i]}, got ${actual[i]}"
            )
        }
    }

    @Test
    fun `default-parameter nodes are identity`() {
        val graph = ProcessGraph(
            nodes = listOf(
                NodeInstance("e", "exposure"),
                NodeInstance("w", "white_balance"),
                NodeInstance("m", "color_matrix"),
                NodeInstance("t", "tone_curve"),
                NodeInstance("s", "saturation"),
            ),
            edges = listOf(Edge("e", "w"), Edge("w", "m"), Edge("m", "t"), Edge("t", "s")),
            outputNodeId = "s",
        )
        val src = testCard()
        val out = render(graph, src)
        for (y in 0..1) for (x in 0..1) assertPixel(src.pixel(x, y), out.pixel(x, y))
    }

    @Test
    fun `exposure plus one stop doubles linear values`() {
        val graph = ProcessGraph(
            listOf(NodeInstance("e", "exposure", mapOf("stops" to 1f))), emptyList(), "e"
        )
        val out = render(graph, testCard())
        assertPixel(floatArrayOf(0.36f, 0.36f, 0.36f), out.pixel(0, 0))
        assertPixel(floatArrayOf(0.80f, 0.50f, 0.20f), out.pixel(0, 1))
    }

    @Test
    fun `disabled node is a passthrough`() {
        val boosted = ProcessGraph(
            listOf(NodeInstance("e", "exposure", mapOf("stops" to 2f), enabled = false)),
            emptyList(), "e"
        )
        val out = render(boosted, testCard())
        assertPixel(testCard().pixel(0, 0), out.pixel(0, 0))
    }

    @Test
    fun `render never aliases the source buffer, even with an all-disabled plan`() {
        val src = testCard()
        val plan = compiler.compile(
            ProcessGraph(
                listOf(NodeInstance("e", "exposure", enabled = false)), emptyList(), "e"
            )
        )
        val out = backend.render(plan, src)
        out.data[0] = 99f
        assertTrue(src.data[0] == 0.18f, "mutating the output corrupted the source")
    }

    @Test
    fun `saturation zero collapses to luma grey`() {
        val graph = ProcessGraph(
            listOf(NodeInstance("s", "saturation", mapOf("amount" to 0f))), emptyList(), "s"
        )
        val px = render(graph, testCard()).pixel(0, 1) // the warm pixel
        assertTrue(abs(px[0] - px[1]) < 1e-5f && abs(px[1] - px[2]) < 1e-5f, "not grey: ${px.toList()}")
    }

    @Test
    fun `tone map is monotonic, anchored at zero and bounded`() {
        val values = floatArrayOf(0f, 0.01f, 0.1f, 0.18f, 0.5f, 1f, 2f, 4f, 8f, 20f)
        val src = ImageBuffer(values.size, 1, FloatArray(values.size * 4) { i ->
            if (i % 4 == 3) 1f else values[i / 4]
        })
        val out = backend.render(
            compiler.compile(
                ProcessGraph(listOf(NodeInstance("t", "tone_map")), emptyList(), "t")
            ),
            src,
        )
        var prev = -1f
        for (x in values.indices) {
            val v = out.pixel(x, 0)[0]
            assertTrue(v >= prev, "not monotonic at ${values[x]}: $v < $prev")
            assertTrue(v in 0f..1f, "out of bounds at ${values[x]}: $v")
            prev = v
        }
        assertTrue(out.pixel(0, 0)[0] == 0f, "zero must map to zero")
        assertTrue(out.pixel(values.size - 1, 0)[0] > 0.95f, "highlights must approach white")
    }

    @Test
    fun `output transform maps working-space grey to srgb-encoded grey`() {
        // Grey is gamut-invariant, so rec2020 0.18 grey must encode to oetf(0.18).
        val graph = ProcessGraph(
            listOf(NodeInstance("o", "srgb_output")), emptyList(), "o"
        )
        val out = render(graph, testCard())
        val expected = Srgb.oetf(0.18f)
        assertPixel(floatArrayOf(expected, expected, expected), out.pixel(0, 0))
    }

    @Test
    fun `golden - full chain on the test card`() {
        val graph = ProcessGraph(
            nodes = listOf(
                NodeInstance("exp", "exposure", mapOf("stops" to 0.5f)),
                NodeInstance("wb", "white_balance", mapOf("r_gain" to 1.1f, "b_gain" to 0.9f)),
                NodeInstance("tc", "tone_curve", mapOf("contrast" to 1.2f)),
                NodeInstance("sat", "saturation", mapOf("amount" to 1.15f)),
                NodeInstance("out", "srgb_output"),
            ),
            edges = listOf(Edge("exp", "wb"), Edge("wb", "tc"), Edge("tc", "sat"), Edge("sat", "out")),
            outputNodeId = "out",
        )
        val out = render(graph, testCard())

        // Independently computed expectation for the 18% grey pixel (hand-derived
        // formulas, not the kernel code): serves as the golden reference.
        fun chain(v0: Float, wbGain: Float): Float {
            val v1 = v0 * 1.41421356f            // +0.5 stop
            return v1 * wbGain                   // white balance
        }
        val pivot = 0.18f
        fun tone(v: Float) = pivot * (v / pivot).let { Math.pow(it.toDouble(), 1.2).toFloat() }
        val r0 = tone(chain(0.18f, 1.1f)); val g0 = tone(chain(0.18f, 1f)); val b0 = tone(chain(0.18f, 0.9f))
        val luma = 0.2627f * r0 + 0.6780f * g0 + 0.0593f * b0
        fun sat(v: Float) = luma + (v - luma) * 1.15f
        val m = app.filmengine.color.ColorSpaces.REC2020_TO_SRGB.m
        val rl = sat(r0); val gl = sat(g0); val bl = sat(b0)
        val expected = floatArrayOf(
            Srgb.oetf((m[0] * rl + m[1] * gl + m[2] * bl).coerceIn(0f, 1f)),
            Srgb.oetf((m[3] * rl + m[4] * gl + m[5] * bl).coerceIn(0f, 1f)),
            Srgb.oetf((m[6] * rl + m[7] * gl + m[8] * bl).coerceIn(0f, 1f)),
        )
        assertPixel(expected, out.pixel(0, 0), 1e-3f)

        // Structural sanity on the rest of the card: output must be in [0,1].
        for (v in out.data) assertTrue(v in 0f..1f, "out of range: $v")
    }

    @Test
    fun `highlight reconstruction desaturates clipped pixels and leaves the rest alone`() {
        val src = ImageBuffer(
            2, 1,
            floatArrayOf(
                1.0f, 0.6f, 0.5f, 1f,   // red channel at clip → pull toward mean
                0.4f, 0.3f, 0.2f, 1f,   // well below threshold → untouched
            )
        )
        val graph = ProcessGraph(
            listOf(NodeInstance("h", "highlight_reconstruction", mapOf("threshold" to 0.9f, "strength" to 1f))),
            emptyList(), "h",
        )
        val out = render(graph, src)
        val mean = (1.0f + 0.6f + 0.5f) / 3f
        assertPixel(floatArrayOf(mean, mean, mean), out.pixel(0, 0)) // max=1.0 → full blend
        assertPixel(floatArrayOf(0.4f, 0.3f, 0.2f), out.pixel(1, 0))
        // max channel came down, unclipped channels came up — reconstruction, not just clamping
        assertTrue(out.pixel(0, 0)[0] < 1.0f && out.pixel(0, 0)[1] > 0.6f)
    }

    @Test
    fun `shadow lift boosts shadows, preserves true black and barely moves highlights`() {
        val src = ImageBuffer(
            3, 1,
            floatArrayOf(
                0f, 0f, 0f, 1f,
                0.02f, 0.02f, 0.02f, 1f,
                0.9f, 0.9f, 0.9f, 1f,
            )
        )
        val graph = ProcessGraph(
            listOf(NodeInstance("s", "shadow_lift", mapOf("amount" to 1f, "range" to 0.1f))),
            emptyList(), "s",
        )
        val out = render(graph, src)
        assertPixel(floatArrayOf(0f, 0f, 0f), out.pixel(0, 0))                    // black fixed
        assertTrue(out.pixel(1, 0)[0] > 0.03f, "shadow not lifted")               // ~1.83x gain
        assertTrue(out.pixel(2, 0)[0] < 1.0f, "highlight moved too much")         // gain ~1.1
    }
}
