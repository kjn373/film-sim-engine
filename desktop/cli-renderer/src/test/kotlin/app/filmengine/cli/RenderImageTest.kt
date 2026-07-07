package app.filmengine.cli

import app.filmengine.film.BuiltinStocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderImageTest {

    @Test
    fun `every builtin stock renders the chart`() {
        val chart = testChart(64, 48)
        for (stock in BuiltinStocks.all) {
            val out = renderImage(chart, stock.id)
            assertEquals(chart.width, out.width)
            assertEquals(chart.height, out.height)
        }
    }

    @Test
    fun `mono stock output is grey everywhere`() {
        val out = renderImage(testChart(64, 48), "mono-400")
        for (y in 0 until out.height step 7) {
            for (x in 0 until out.width step 7) {
                val rgb = out.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                // channel equality within rounding
                assertTrue(
                    Math.abs(r - g) <= 1 && Math.abs(g - b) <= 1,
                    "pixel ($x,$y) not neutral: $r $g $b"
                )
            }
        }
    }

    @Test
    fun `colour stock changes the image`() {
        val chart = testChart(64, 48)
        val out = renderImage(chart, "chroma-100")
        var diff = 0
        for (y in 0 until out.height) for (x in 0 until out.width) {
            if (out.getRGB(x, y) != chart.getRGB(x, y)) diff++
        }
        assertTrue(diff > chart.width * chart.height / 2, "film sim barely changed the image ($diff px)")
    }

    @Test
    fun `decode is independent of BufferedImage storage type`() {
        // ImageIO returns TYPE_3BYTE_BGR / TYPE_4BYTE_ABGR for real files, while
        // testChart builds TYPE_INT_RGB — decode must treat them identically.
        val rgb = testChart(16, 12)
        val bgr = java.awt.image.BufferedImage(16, 12, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
        bgr.graphics.drawImage(rgb, 0, 0, null)
        val a = decode(rgb).data
        val b = decode(bgr).data
        for (i in a.indices) {
            assertTrue(Math.abs(a[i] - b[i]) < 1e-4f, "index $i: ${a[i]} vs ${b[i]}")
        }
    }

    @Test
    fun `decode - output transform - encode round trip is identity within quantization`() {
        val chart = testChart(32, 24)
        val graph = app.filmengine.engine.graph.ProcessGraph(
            listOf(app.filmengine.engine.graph.NodeInstance("out", "srgb_output")),
            emptyList(), "out",
        )
        val plan = app.filmengine.engine.exec.GraphCompiler().compile(graph)
        val backend = app.filmengine.render.cpu.CpuBackend()
        val roundTripped = encode(backend.render(plan, decode(chart)))
        for (y in 0 until chart.height) {
            for (x in 0 until chart.width) {
                val a = chart.getRGB(x, y)
                val b = roundTripped.getRGB(x, y)
                for (shift in listOf(16, 8, 0)) {
                    val ca = (a shr shift) and 0xFF
                    val cb = (b shr shift) and 0xFF
                    assertTrue(Math.abs(ca - cb) <= 1, "pixel ($x,$y) shift $shift: $ca vs $cb")
                }
            }
        }
    }
}
