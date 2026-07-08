package app.filmengine.camera.core

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class ExposureMode { AUTO, MANUAL, SHUTTER_PRIORITY, ISO_PRIORITY }

data class ExposureCaps(
    val isoMin: Int,
    val isoMax: Int,
    val minShutterNs: Long,
    val maxShutterNs: Long,
)

data class ExposureSettings(val iso: Int, val shutterNs: Long)

/**
 * [offsetStops]: how far the achieved exposure sits from the metered target after
 * clamping — 0 when the request was satisfiable, positive = brighter than target.
 */
data class SolvedExposure(val settings: ExposureSettings, val offsetStops: Float)

/**
 * Deterministic exposure state machine (ARCHITECTURE.md §10): given the AE-metered
 * reference and the user's fixed variable, solve the free one so that
 * iso · shutter stays at the metered product shifted by exposure compensation.
 * Pure logic — no camera types — so every mode is unit-tested on the JVM.
 */
class ExposureSolver(private val caps: ExposureCaps) {

    fun solve(
        mode: ExposureMode,
        metered: ExposureSettings,
        userIso: Int = metered.iso,
        userShutterNs: Long = metered.shutterNs,
        ecStops: Float = 0f,
    ): SolvedExposure {
        val target = metered.iso.toDouble() * metered.shutterNs.toDouble() * 2.0.pow(ecStops.toDouble())
        return when (mode) {
            ExposureMode.AUTO -> SolvedExposure(metered, 0f)

            ExposureMode.MANUAL -> settled(clampIso(userIso), clampShutter(userShutterNs), target)

            ExposureMode.SHUTTER_PRIORITY -> {
                val shutter = clampShutter(userShutterNs)
                val iso = clampIso((target / shutter).roundToInt())
                settled(iso, shutter, target)
            }

            ExposureMode.ISO_PRIORITY -> {
                val iso = clampIso(userIso)
                val shutter = clampShutter((target / iso).toLong())
                settled(iso, shutter, target)
            }
        }
    }

    private fun settled(iso: Int, shutterNs: Long, target: Double): SolvedExposure {
        val achieved = iso.toDouble() * shutterNs.toDouble()
        val offset = (ln(achieved / target) / ln(2.0)).toFloat()
        return SolvedExposure(ExposureSettings(iso, shutterNs), offset)
    }

    private fun clampIso(iso: Int) = iso.coerceIn(caps.isoMin, caps.isoMax)
    private fun clampShutter(ns: Long) = ns.coerceIn(caps.minShutterNs, caps.maxShutterNs)
}
