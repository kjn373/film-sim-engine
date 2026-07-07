package app.filmengine.cli

import app.filmengine.color.ColorSpaces
import app.filmengine.color.Srgb
import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.BuiltinStocks
import app.filmengine.film.FilmNodes
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private val USAGE = """
    Usage: cli-renderer <input.png|jpg | chart> <output.png> [stock] [push]
      stock: ${BuiltinStocks.all.joinToString { it.id }} (default ${BuiltinStocks.all.first().id})
      push:  exposure push/pull in stops (default 0)
      input "chart" renders a synthetic exposure/hue test chart instead of a file.
""".trimIndent()

fun main(args: Array<String>) {
    if (args.size < 2) {
        println(USAGE)
        exitProcess(1)
    }
    val stockId = args.getOrElse(2) { BuiltinStocks.all.first().id }
    val push = args.getOrElse(3) { "0" }.toFloatOrNull() ?: run {
        println("push must be a number, got '${args[3]}'\n\n$USAGE")
        exitProcess(1)
    }
    val src = if (args[0] == "chart") testChart()
    else ImageIO.read(File(args[0])) ?: error("Cannot read image: ${args[0]}")

    val out = renderImage(src, stockId, push)
    ImageIO.write(out, "png", File(args[1]))
    println("Rendered ${args[0]} -> ${args[1]} (stock=$stockId, push=$push)")
}

/** Decode sRGB image -> linear Rec.2020 -> film sim -> sRGB output transform. */
fun renderImage(src: BufferedImage, stockId: String, push: Float = 0f): BufferedImage {
    val registry = NodeRegistry(BuiltinNodes.all + FilmNodes.all)
    val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)
    val graph = ProcessGraph(
        nodes = listOf(
            NodeInstance(
                "film", "film_sim",
                params = mapOf("push" to push),
                options = mapOf("stock" to stockId),
            ),
            NodeInstance("out", "srgb_output"),
        ),
        edges = listOf(Edge("film", "out")),
        outputNodeId = "out",
    )
    val plan = GraphCompiler(registry).compile(graph)
    return encode(backend.render(plan, decode(src)))
}

fun decode(img: BufferedImage): ImageBuffer {
    val w = img.width
    val h = img.height
    val rgbs = img.getRGB(0, 0, w, h, null, 0, w) // bulk read; converts any storage type to sRGB ints
    val buf = ImageBuffer.alloc(w, h)
    val px = FloatArray(3)
    for (p in rgbs.indices) {
        val rgb = rgbs[p]
        px[0] = Srgb.eotf(((rgb shr 16) and 0xFF) / 255f)
        px[1] = Srgb.eotf(((rgb shr 8) and 0xFF) / 255f)
        px[2] = Srgb.eotf((rgb and 0xFF) / 255f)
        ColorSpaces.SRGB_TO_REC2020.transform(px)
        val i = p * 4
        buf.data[i] = px[0]
        buf.data[i + 1] = px[1]
        buf.data[i + 2] = px[2]
        buf.data[i + 3] = 1f
    }
    return buf
}

/** Expects already sRGB-encoded [0,1] data (i.e. the graph ended in srgb_output). */
fun encode(buf: ImageBuffer): BufferedImage {
    val img = BufferedImage(buf.width, buf.height, BufferedImage.TYPE_INT_RGB)
    val rgbs = IntArray(buf.width * buf.height)
    for (p in rgbs.indices) {
        val i = p * 4
        val r = (buf.data[i] * 255f).roundToInt().coerceIn(0, 255)
        val g = (buf.data[i + 1] * 255f).roundToInt().coerceIn(0, 255)
        val b = (buf.data[i + 2] * 255f).roundToInt().coerceIn(0, 255)
        rgbs[p] = (r shl 16) or (g shl 8) or b
    }
    img.setRGB(0, 0, buf.width, buf.height, rgbs, 0, buf.width)
    return img
}

/** Exposure ramp (-6..+4 stops of grey) over a hue/value sweep — for eyeballing stocks. */
fun testChart(width: Int = 512, height: Int = 384): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val rampRows = height / 3
    for (x in 0 until width) {
        val stops = -6f + 10f * x / (width - 1)
        val linear = (0.18f * 2f.pow(stops)).coerceIn(0f, 1f)
        val v = (Srgb.oetf(linear) * 255f).roundToInt().coerceIn(0, 255)
        val grey = (v shl 16) or (v shl 8) or v
        for (y in 0 until rampRows) img.setRGB(x, y, grey)
    }
    for (y in rampRows until height) {
        val brightness = 1f - 0.8f * (y - rampRows) / (height - rampRows - 1)
        for (x in 0 until width) {
            val hue = x.toFloat() / width
            img.setRGB(x, y, Color.HSBtoRGB(hue, 0.9f, brightness))
        }
    }
    return img
}
