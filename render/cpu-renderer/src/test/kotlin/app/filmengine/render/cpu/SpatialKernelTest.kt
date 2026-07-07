package app.filmengine.render.cpu

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.engine.node.Gaussian
import app.filmengine.film.FilmNodes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SpatialKernelTest {
    private val compiler = GraphCompiler(NodeRegistry(BuiltinNodes.all + FilmNodes.all))
    private val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)

    private fun render(type: String, params: Map<String, Float>, src: ImageBuffer): ImageBuffer =
        backend.render(
            compiler.compile(ProcessGraph(listOf(NodeInstance("n", type, params)), emptyList(), "n")),
            src,
        )

    private fun constant(w: Int, h: Int, v: Float): ImageBuffer {
        val img = ImageBuffer.alloc(w, h)
        for (i in img.data.indices) img.data[i] = if (i % 4 == 3) 1f else v
        return img
    }

    @Test
    fun `blur preserves a constant image`() {
        val out = render("gaussian_blur", mapOf("sigma" to 4f), constant(24, 24, 0.3f))
        for (i in out.data.indices step 4) {
            assertTrue(abs(out.data[i] - 0.3f) < 1e-4f, "constant drifted to ${out.data[i]}")
        }
    }

    @Test
    fun `blur conserves the energy of an interior impulse`() {
        val src = ImageBuffer.alloc(33, 33)
        for (i in src.data.indices step 4) src.data[i + 3] = 1f
        val c = (16 * 33 + 16) * 4
        src.data[c] = 100f
        val out = render("gaussian_blur", mapOf("sigma" to 2f), src)
        var sum = 0f
        for (i in out.data.indices step 4) sum += out.data[i]
        assertTrue(abs(sum - 100f) < 0.1f, "impulse energy $sum != 100")
        assertTrue(out.data[c] < 100f, "impulse was not spread")
    }

    @Test
    fun `bloom below threshold is identity`() {
        val src = constant(16, 16, 0.4f)
        val out = render("bloom", mapOf("threshold" to 1f, "intensity" to 2f, "sigma" to 3f), src)
        for (i in out.data.indices) {
            assertTrue(abs(out.data[i] - src.data[i]) < 1e-6f, "dark image changed at $i")
        }
    }

    @Test
    fun `halation warms the surroundings of a specular`() {
        val src = constant(33, 33, 0.1f)
        val c = (16 * 33 + 16) * 4
        src.data[c] = 8f; src.data[c + 1] = 8f; src.data[c + 2] = 8f
        val out = render("halation", mapOf("threshold" to 1f, "strength" to 1f, "sigma" to 3f), src)
        val n = (16 * 33 + 20) * 4 // 4px to the right of the specular
        val dr = out.data[n] - src.data[n]
        val db = out.data[n + 2] - src.data[n + 2]
        assertTrue(dr > 0f, "no red glow added")
        assertTrue(dr > db * 5f, "glow is not red-dominant (dr=$dr, db=$db)")
    }

    @Test
    fun `grain - zero amount is identity, seeds are deterministic and distinct`() {
        val src = constant(16, 16, 0.18f)
        val zero = render("grain", mapOf("amount" to 0f), src)
        for (i in zero.data.indices) assertTrue(abs(zero.data[i] - src.data[i]) < 1e-7f)

        val a1 = render("grain", mapOf("amount" to 0.5f, "seed" to 1f), src)
        val a2 = render("grain", mapOf("amount" to 0.5f, "seed" to 1f), src)
        val b = render("grain", mapOf("amount" to 0.5f, "seed" to 2f), src)
        var identical = true
        var differs = false
        for (i in a1.data.indices) {
            if (a1.data[i] != a2.data[i]) identical = false
            if (a1.data[i] != b.data[i]) differs = true
        }
        assertTrue(identical, "same seed must reproduce exactly")
        assertTrue(differs, "different seeds must differ")
    }

    @Test
    fun `gaussian weights are normalized and capped`() {
        for (sigma in listOf(0.5f, 2f, 8f, 24f)) {
            val w = Gaussian.weights(sigma)
            assertTrue(w.size - 1 <= Gaussian.MAX_RADIUS)
            var sum = w[0]
            for (i in 1 until w.size) sum += 2f * w[i]
            assertTrue(abs(sum - 1f) < 1e-5f, "sigma $sigma weights sum to $sum")
        }
    }
}
