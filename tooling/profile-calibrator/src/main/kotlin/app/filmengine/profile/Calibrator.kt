package app.filmengine.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-frame statistics measured on-device (camera-core's CalibrationStats) —
 * the wire format between the phone and this tool. All signal values in DN.
 */
@Serializable
data class FrameStats(
    /** "dark" (lens capped), "chart" (ColorChecker fills the frame) or "flat" (defocused even field). */
    val kind: String,
    val iso: Int,
    val exposureNs: Long,
    /** Estimated illuminant CCT for chart frames (from the shooter, e.g. 6500 daylight / 2850 halogen). */
    val cct: Float = 6504f,
    /** Whole-frame per-channel means [R, G, B] (Gr/Gb averaged). */
    val means: List<Float>,
    /** Whole-frame per-channel variances [R, G, B]. */
    val variances: List<Float>,
    /** Chart frames: 24 × [r, g, b] patch means, chart order top-left → bottom-right. */
    val patches: List<List<Float>> = emptyList(),
    /** Flat frames: (normalized radius, gain relative to center) samples. */
    val radialGains: List<List<Float>> = emptyList(),
)

@Serializable
data class CalibrationReport(
    val model: String,
    val sensorId: String = "0",
    val whiteLevel: Float,
    val frames: List<FrameStats>,
)

object CalibrationReportCodec {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    fun decode(text: String): CalibrationReport =
        json.decodeFromString(CalibrationReport.serializer(), text)
    fun encode(report: CalibrationReport): String =
        json.encodeToString(CalibrationReport.serializer(), report)
}

/** Turns a capture report into a first-party [DeviceProfile] (ARCHITECTURE.md §16). */
object Calibrator {

    fun calibrate(
        report: CalibrationReport,
        reference: List<FloatArray> = ColorCheckerReference.XYZ_D65,
    ): DeviceProfile {
        val darks = report.frames.filter { it.kind == "dark" }
        val charts = report.frames.filter { it.kind == "chart" }
        require(charts.isNotEmpty()) { "report has no chart frames — cannot solve color matrices" }

        // black levels: mean of dark-frame channel means per ISO
        val blackLevels = darks.groupBy { it.iso }.mapValues { (_, frames) ->
            frames.flatMap { it.means }.average().toFloat()
        }.ifEmpty { mapOf(charts.first().iso to 0f) } // ponytail: no darks → assume 0 DN black

        fun black(iso: Int): Float {
            val points = blackLevels.entries.sortedBy { it.key }
            return when {
                iso <= points.first().key -> points.first().value
                iso >= points.last().key -> points.last().value
                else -> {
                    val lo = points.last { it.key <= iso }
                    val hi = points.first { it.key >= iso }
                    if (lo.key == hi.key) lo.value
                    else lo.value + (hi.value - lo.value) * (iso - lo.key).toFloat() / (hi.key - lo.key)
                }
            }
        }

        // noise ladder: regress variance vs mean per ISO across every frame kind,
        // each channel contributing one (signal, variance) point in normalized units
        val noise = report.frames.groupBy { it.iso }.map { (iso, frames) ->
            val span = report.whiteLevel - black(iso)
            val points = frames.flatMap { f ->
                f.means.indices.map { c ->
                    ((f.means[c] - black(iso)) / span) to (f.variances[c] / (span * span))
                }
            }
            val (scale, offset) = NoiseFitter.fit(points)
            NoisePoint(iso, scale, offset)
        }.sortedBy { it.iso }

        // dual-illuminant matrices: lowest-CCT chart cluster → illuminant 2 (StdA side),
        // highest → illuminant 1 (D65 side); a single-illuminant set fills both slots
        fun solveAt(cct: Float): List<Float> {
            val group = charts.filter { it.cct == cct }
            val patches = (0 until ColorCheckerReference.PATCH_COUNT).map { i ->
                val rgb = FloatArray(3)
                for (f in group) {
                    require(f.patches.size == ColorCheckerReference.PATCH_COUNT) {
                        "chart frame at ISO ${f.iso} has ${f.patches.size} patches, expected 24"
                    }
                    val span = report.whiteLevel - black(f.iso)
                    for (c in 0..2) rgb[c] += ((f.patches[i][c] - black(f.iso)) / span) / group.size
                }
                ColorMatrixSolver.Patch(rgb, reference[i])
            }
            return ColorMatrixSolver.solve(patches).m.toList()
        }

        val ccts = charts.map { it.cct }.distinct().sorted()
        val cct1 = ccts.last()
        val cct2 = ccts.first()

        // vignette: all flat-frame radial samples pooled into one fit
        val radial = report.frames.filter { it.kind == "flat" }
            .flatMap { it.radialGains }
            .map { it[0] to it[1] - 1f }
        val lenses = if (radial.size >= 3) {
            listOf(LensCalibration(id = "0", vignette = RadialPolyFitter.fit(radial).toList()))
        } else emptyList() // ponytail: distortion needs dot-grid correspondences — RadialPolyFitter is ready, the capture flow isn't

        return DeviceProfile(
            model = report.model,
            sensorId = report.sensorId,
            source = "calibrated",
            sensor = SensorCalibration(
                whiteLevel = report.whiteLevel,
                blackLevels = blackLevels,
                illuminant1Cct = cct1,
                illuminant2Cct = cct2,
                colorMatrix1 = solveAt(cct1),
                colorMatrix2 = solveAt(cct2),
            ),
            noise = noise,
            lenses = lenses,
        )
    }
}

/** CLI: `profile-calibrator <calibration_report.json> [deviceprofile.json]`. */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: profile-calibrator <calibration_report.json> [deviceprofile.json]" }
    val report = CalibrationReportCodec.decode(File(args[0]).readText())
    val profile = Calibrator.calibrate(report)
    val out = File(args.getOrElse(1) { "deviceprofile.json" })
    out.writeText(DeviceProfileCodec.encode(profile))
    println("Wrote ${out.absolutePath} (${profile.sensor.blackLevels.size} black levels, ${profile.noise.size} noise points)")
}
