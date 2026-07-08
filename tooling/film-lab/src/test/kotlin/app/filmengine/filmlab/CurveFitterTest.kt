package app.filmengine.filmlab

import app.filmengine.film.CharacteristicCurve
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class CurveFitterTest {

    private fun samplesFrom(curve: CharacteristicCurve): List<CurveFitter.Sample> =
        generateSequence(-6f) { it + 0.5f }.takeWhile { it <= 4f }.map { stops ->
            CurveFitter.Sample(stops, curve.eval(0.18f * 2f.pow(stops)))
        }.toList()

    @Test
    fun `recovers known curve parameters from clean samples`() {
        val truth = CharacteristicCurve(gamma = 1.1f, toe = 1.3f, shoulder = 0.9f, black = 0.002f, white = 0.97f)
        val fit = CurveFitter.fit(samplesFrom(truth), black = 0.002f, white = 0.97f)

        assertTrue(fit.rmse < 1e-3f, "rmse ${fit.rmse} too high")
        // The fitted curve must reproduce the truth curve everywhere, not just at samples.
        var e = -8f
        while (e <= 6f) {
            val x = 0.18f * 2f.pow(e)
            val d = abs(fit.curve.eval(x) - truth.eval(x))
            assertTrue(d < 5e-3f, "fitted curve deviates $d at $e stops")
            e += 0.25f
        }
    }

    @Test
    fun `fits every builtin stock's own curve back from its samples`() {
        for (stock in app.filmengine.film.BuiltinStocks.all) {
            val c = stock.curve
            val fit = CurveFitter.fit(samplesFrom(c), c.black, c.white)
            assertTrue(fit.rmse < 2e-3f, "${stock.id}: rmse ${fit.rmse}")
        }
    }

    @Test
    fun `rejects underdetermined input`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CurveFitter.fit(listOf(CurveFitter.Sample(0f, 0.18f)), 0.001f, 1f)
        }
    }
}
