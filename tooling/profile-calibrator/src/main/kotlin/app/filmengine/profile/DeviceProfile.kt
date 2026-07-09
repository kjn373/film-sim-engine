package app.filmengine.profile

import app.filmengine.color.Mat3
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-device sensor calibration (ARCHITECTURE.md §16). All raw values are in
 * sensor DN (digital numbers): the develop path normalizes with
 * (dn - blackLevel(iso)) / (whiteLevel - blackLevel(iso)).
 */
@Serializable
data class SensorCalibration(
    val whiteLevel: Float,
    /** ISO → black level in DN. Interpolated between measured points. */
    val blackLevels: Map<Int, Float>,
    /** The DNG dual-illuminant model: matrices at two CCTs, interpolated in mired. */
    val illuminant1Cct: Float = 6504f, // D65
    val illuminant2Cct: Float = 2856f, // Standard Illuminant A
    /** Row-major 3×3, normalized camera RGB → CIE XYZ, at illuminant 1. */
    val colorMatrix1: List<Float>,
    val colorMatrix2: List<Float>,
) {
    init {
        require(colorMatrix1.size == 9 && colorMatrix2.size == 9) { "color matrices must be 3×3" }
    }

    /** Camera RGB → XYZ at the estimated scene CCT (linear interpolation in 1/CCT). */
    fun colorMatrix(cct: Float): Mat3 {
        val m1 = 1f / illuminant1Cct
        val m2 = 1f / illuminant2Cct
        val w = if (m1 == m2) 0f else ((1f / cct - m1) / (m2 - m1)).coerceIn(0f, 1f)
        return Mat3(FloatArray(9) { colorMatrix1[it] + (colorMatrix2[it] - colorMatrix1[it]) * w })
    }

    /** Black level at [iso], linearly interpolated between measured ISOs, clamped at the ends. */
    fun blackLevel(iso: Int): Float {
        val points = blackLevels.entries.sortedBy { it.key }
        require(points.isNotEmpty()) { "no black levels in profile" }
        if (iso <= points.first().key) return points.first().value
        if (iso >= points.last().key) return points.last().value
        val hi = points.first { it.key >= iso }
        val lo = points.last { it.key <= iso }
        if (lo.key == hi.key) return lo.value
        val t = (iso - lo.key).toFloat() / (hi.key - lo.key)
        return lo.value + (hi.value - lo.value) * t
    }
}

/** Read-noise model at one ISO: variance = scale·signal + offset (signal in normalized 0..1). */
@Serializable
data class NoisePoint(val iso: Int, val scale: Float, val offset: Float)

/** Per-lens optics. Coefficients are for even radial polynomials in normalized radius. */
@Serializable
data class LensCalibration(
    val id: String,
    /** Brown-Conrady k1..k3: r_distorted = r·(1 + k1·r² + k2·r⁴ + k3·r⁶). */
    val distortion: List<Float> = emptyList(),
    /** Vignette gain falloff: gain(r) = 1 + k1·r² + k2·r⁴ + k3·r⁶. */
    val vignette: List<Float> = emptyList(),
)

@Serializable
data class DeviceProfile(
    val schemaVersion: Int = DeviceProfileCodec.SCHEMA_VERSION,
    val model: String,
    val sensorId: String = "0",
    /** "calibrated" (chart-measured via profile-calibrator) or "harvested" (Camera2 metadata). */
    val source: String,
    val sensor: SensorCalibration,
    val noise: List<NoisePoint> = emptyList(),
    val lenses: List<LensCalibration> = emptyList(),
)

object DeviceProfileCodec {
    const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true // same-major forward compat, mirroring RecipeCodec
        prettyPrint = true
    }

    fun encode(profile: DeviceProfile): String = json.encodeToString(DeviceProfile.serializer(), profile)

    fun decode(text: String): DeviceProfile {
        val profile = json.decodeFromString(DeviceProfile.serializer(), text)
        require(profile.schemaVersion <= SCHEMA_VERSION) {
            "profile schemaVersion ${profile.schemaVersion} is newer than supported $SCHEMA_VERSION"
        }
        return profile
    }
}
