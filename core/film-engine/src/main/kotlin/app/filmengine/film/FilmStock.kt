package app.filmengine.film

import app.filmengine.color.Mat3
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Parametric H&D-style characteristic curve, evaluated in log2 exposure around
 * 18% grey. Anchored logistic: monotonic, bounded by [black, white], and exact
 * at the grey pivot (curve(0.18) == 0.18 at push 0).
 *
 * gamma    — slope at the pivot (contrast)
 * toe      — >1 lifts/softens shadows, <1 crushes them faster
 * shoulder — >1 lengthens highlight roll-off, <1 hardens it
 */
// ponytail: analytic logistic, not a fitted spline — film-lab replaces this with
// per-stock fitted curves when real scan data lands (ARCHITECTURE.md §6).
data class CharacteristicCurve(
    val gamma: Float,
    val toe: Float,
    val shoulder: Float,
    val black: Float,
    val white: Float,
) {
    init {
        require(gamma > 0f && toe > 0f && shoulder > 0f) { "Curve params must be positive" }
        require(black < MID_GREY && white > MID_GREY) { "black < 0.18 < white required" }
    }

    private val s0 = (MID_GREY - black) / (white - black)
    private val k = 1f / s0 - 1f

    fun eval(x: Float, pushStops: Float = 0f): Float {
        val e = log2(max(x, 1e-6f) / MID_GREY) + pushStops
        val t = e * gamma
        val tt = if (t >= 0f) t / shoulder else t / toe
        val s = 1f / (1f + k * 2f.pow(-tt))
        return black + (white - black) * s
    }

    companion object {
        const val MID_GREY = 0.18f
    }
}

/**
 * A film stock: spectral sensitivity mixing (pre-development), per-channel
 * characteristic curve, dye crosstalk (post-development), and saturation.
 * Both matrices must have rows summing to 1 so neutral greys stay neutral.
 */
class FilmStock(
    val id: String,
    val name: String,
    val curve: CharacteristicCurve,
    val sensitivity: Mat3 = Mat3.IDENTITY,
    val dye: Mat3 = Mat3.IDENTITY,
    val saturation: Float = 1f,
) {
    init {
        require(saturation >= 0f) { "$id: saturation must be >= 0" }
        for ((label, mat) in listOf("sensitivity" to sensitivity, "dye" to dye)) {
            for (row in 0..2) {
                val sum = mat.m[row * 3] + mat.m[row * 3 + 1] + mat.m[row * 3 + 2]
                require(kotlin.math.abs(sum - 1f) < 1e-4f) {
                    "$id: $label row $row sums to $sum — rows must sum to 1 to preserve neutrals"
                }
            }
        }
    }
}

object BuiltinStocks {
    /** Slide-film look: steep, crushed shadows, vivid. */
    val CHROMA_100 = FilmStock(
        id = "chroma-100",
        name = "Chroma 100",
        curve = CharacteristicCurve(gamma = 1.2f, toe = 0.8f, shoulder = 1.0f, black = 0.001f, white = 1.0f),
        dye = Mat3(
            floatArrayOf(
                1.02f, 0.00f, -0.02f,
                -0.01f, 1.02f, -0.01f,
                -0.02f, 0.00f, 1.02f,
            )
        ),
        saturation = 1.25f,
    )

    /** Warm colour negative: soft shadows, long highlight roll-off. */
    val NEGATO_400 = FilmStock(
        id = "negato-400",
        name = "Negato 400",
        curve = CharacteristicCurve(gamma = 0.85f, toe = 1.4f, shoulder = 1.5f, black = 0.006f, white = 0.92f),
        dye = Mat3(
            floatArrayOf(
                1.04f, -0.02f, -0.02f,
                0.01f, 0.98f, 0.01f,
                -0.01f, 0.03f, 0.98f,
            )
        ),
        saturation = 0.95f,
    )

    /** Panchromatic B&W, slightly red-weighted sensitivity. */
    val MONO_400 = FilmStock(
        id = "mono-400",
        name = "Mono 400",
        curve = CharacteristicCurve(gamma = 1.05f, toe = 1.1f, shoulder = 1.2f, black = 0.002f, white = 0.98f),
        sensitivity = Mat3(
            floatArrayOf(
                0.36f, 0.54f, 0.10f,
                0.36f, 0.54f, 0.10f,
                0.36f, 0.54f, 0.10f,
            )
        ),
        saturation = 0f,
    )

    /** Punchy fine-grain slide: steep, hard toe, heavy saturation. */
    val CHROMA_64 = FilmStock(
        id = "chroma-64",
        name = "Chroma 64",
        curve = CharacteristicCurve(gamma = 1.35f, toe = 0.7f, shoulder = 0.9f, black = 0.0008f, white = 1.0f),
        dye = Mat3(
            floatArrayOf(
                1.05f, -0.02f, -0.03f,
                -0.02f, 1.05f, -0.03f,
                -0.03f, -0.01f, 1.04f,
            )
        ),
        saturation = 1.4f,
    )

    /** Neutral slide: honest colour, gentle shoulder — the portrait slide. */
    val CHROMA_200N = FilmStock(
        id = "chroma-200n",
        name = "Chroma 200 Neutral",
        curve = CharacteristicCurve(gamma = 1.1f, toe = 0.9f, shoulder = 1.15f, black = 0.0015f, white = 1.0f),
        dye = Mat3(
            floatArrayOf(
                1.01f, 0.00f, -0.01f,
                0.00f, 1.00f, 0.00f,
                -0.01f, 0.01f, 1.00f,
            )
        ),
        saturation = 1.1f,
    )

    /** Soft warm portrait negative: low contrast, long roll-off, skin-friendly. */
    val NEGATO_160 = FilmStock(
        id = "negato-160",
        name = "Negato 160",
        curve = CharacteristicCurve(gamma = 0.8f, toe = 1.5f, shoulder = 1.7f, black = 0.008f, white = 0.90f),
        dye = Mat3(
            floatArrayOf(
                1.03f, -0.01f, -0.02f,
                0.02f, 0.97f, 0.01f,
                0.00f, 0.02f, 0.98f,
            )
        ),
        saturation = 0.9f,
    )

    /** Tungsten-balanced night negative: cool cast, lifted shadows — pair with halation. */
    val NEGATO_800T = FilmStock(
        id = "negato-800t",
        name = "Negato 800T",
        curve = CharacteristicCurve(gamma = 0.88f, toe = 1.3f, shoulder = 1.4f, black = 0.005f, white = 0.93f),
        dye = Mat3(
            floatArrayOf(
                0.98f, 0.01f, 0.01f,
                0.00f, 0.99f, 0.01f,
                0.01f, -0.03f, 1.02f,
            )
        ),
        saturation = 1.0f,
    )

    /** Fine-grain contrasty B&W, green-weighted sensitivity. */
    val MONO_100 = FilmStock(
        id = "mono-100",
        name = "Mono 100",
        curve = CharacteristicCurve(gamma = 1.2f, toe = 0.95f, shoulder = 1.05f, black = 0.001f, white = 0.99f),
        sensitivity = Mat3(
            floatArrayOf(
                0.30f, 0.60f, 0.10f,
                0.30f, 0.60f, 0.10f,
                0.30f, 0.60f, 0.10f,
            )
        ),
        saturation = 0f,
    )

    /** Push-process low-light B&W: soft, lifted, flat — pair with heavy grain. */
    val MONO_3200P = FilmStock(
        id = "mono-3200p",
        name = "Mono 3200 Push",
        curve = CharacteristicCurve(gamma = 0.9f, toe = 1.5f, shoulder = 1.6f, black = 0.004f, white = 0.95f),
        sensitivity = Mat3(
            floatArrayOf(
                0.38f, 0.52f, 0.10f,
                0.38f, 0.52f, 0.10f,
                0.38f, 0.52f, 0.10f,
            )
        ),
        saturation = 0f,
    )

    val all = listOf(
        CHROMA_64, CHROMA_100, CHROMA_200N,
        NEGATO_160, NEGATO_400, NEGATO_800T,
        MONO_100, MONO_400, MONO_3200P,
    )

    fun byId(id: String): FilmStock =
        all.find { it.id == id } ?: error("Unknown film stock '$id'. Available: ${all.map { it.id }}")
}
