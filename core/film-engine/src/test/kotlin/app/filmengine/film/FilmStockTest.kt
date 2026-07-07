package app.filmengine.film

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class FilmStockTest {

    @Test
    fun `characteristic curves are monotonic`() {
        for (stock in BuiltinStocks.all) {
            var prev = -1f
            var e = -10f
            while (e <= 10f) {
                val x = 0.18f * Math.pow(2.0, e.toDouble()).toFloat()
                val y = stock.curve.eval(x)
                assertTrue(y >= prev, "${stock.id}: curve not monotonic at $e stops ($y < $prev)")
                prev = y
                e += 0.1f
            }
        }
    }

    @Test
    fun `curves are anchored at 18 percent grey`() {
        for (stock in BuiltinStocks.all) {
            val y = stock.curve.eval(0.18f)
            assertTrue(abs(y - 0.18f) < 1e-4f, "${stock.id}: grey maps to $y")
        }
    }

    @Test
    fun `curves are bounded by black and white points`() {
        for (stock in BuiltinStocks.all) {
            val lo = stock.curve.eval(0f)
            val hi = stock.curve.eval(1000f)
            assertTrue(lo >= stock.curve.black - 1e-5f, "${stock.id}: floor $lo below black point")
            assertTrue(hi <= stock.curve.white + 1e-5f, "${stock.id}: ceiling $hi above white point")
        }
    }

    @Test
    fun `push shifts the curve by whole stops`() {
        val c = BuiltinStocks.NEGATO_400.curve
        // Evaluating at x with +1 push equals evaluating at 2x with no push.
        for (x in listOf(0.05f, 0.18f, 0.5f)) {
            assertTrue(abs(c.eval(x, 1f) - c.eval(2 * x, 0f)) < 1e-4f)
        }
    }

    @Test
    fun `toe, shoulder and gamma have independent, correct semantics`() {
        val base = CharacteristicCurve(gamma = 1f, toe = 1f, shoulder = 1f, black = 0.001f, white = 1f)
        val shadow = 0.02f
        val highlight = 1.5f

        // Softer toe lifts deep shadows and leaves highlights bit-identical.
        val softToe = base.copy(toe = 2f)
        assertTrue(softToe.eval(shadow) > base.eval(shadow), "toe did not lift shadows")
        assertTrue(softToe.eval(highlight) == base.eval(highlight), "toe leaked into highlights")

        // Longer shoulder pulls highlights down and leaves shadows bit-identical.
        val softShoulder = base.copy(shoulder = 2f)
        assertTrue(softShoulder.eval(highlight) < base.eval(highlight), "shoulder did not roll off highlights")
        assertTrue(softShoulder.eval(shadow) == base.eval(shadow), "shoulder leaked into shadows")

        // Higher gamma = more contrast around the pivot: darker shadows, brighter highlights.
        val contrasty = base.copy(gamma = 2f)
        assertTrue(contrasty.eval(shadow) < base.eval(shadow))
        assertTrue(contrasty.eval(highlight) > base.eval(highlight))
    }

    @Test
    fun `stock constructor rejects matrices that break neutrality`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            FilmStock(
                id = "broken", name = "Broken",
                curve = BuiltinStocks.CHROMA_100.curve,
                dye = app.filmengine.color.Mat3(
                    floatArrayOf(1.2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                ),
            )
        }
    }

    @Test
    fun `stock matrices preserve neutral greys`() {
        for (stock in BuiltinStocks.all) {
            for (m in listOf(stock.sensitivity.m, stock.dye.m)) {
                for (row in 0..2) {
                    val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
                    assertTrue(abs(sum - 1f) < 1e-5f, "${stock.id}: row $row sums to $sum")
                }
            }
        }
    }
}
