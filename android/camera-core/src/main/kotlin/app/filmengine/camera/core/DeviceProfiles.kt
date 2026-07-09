package app.filmengine.camera.core

import app.filmengine.color.Mat3
import app.filmengine.profile.DeviceProfile
import app.filmengine.profile.SensorCalibration

/**
 * The §16 runtime fallback: build a DeviceProfile from the device's own DNG
 * metadata (Camera2 static characteristics) when no first-party calibrated
 * profile exists. Pure Kotlin — the controller extracts the raw values.
 */
object DeviceProfiles {

    /** EXIF/DNG CalibrationIlluminant code → CCT (the codes Camera2 reports). */
    fun illuminantCct(code: Int): Float = when (code) {
        1, 4, 9 -> 5500f     // daylight, flash, fine weather
        2 -> 4200f           // fluorescent
        3, 17 -> 2856f       // tungsten, standard A
        10 -> 6500f          // cloudy
        11 -> 7500f          // shade
        18 -> 4874f          // standard B
        19 -> 6774f          // standard C
        20 -> 5503f          // D55
        21 -> 6504f          // D65
        22 -> 7504f          // D75
        23 -> 5003f          // D50
        24 -> 3200f          // ISO studio tungsten
        else -> 6504f
    }

    /**
     * [xyzToCam1]/[xyzToCam2] are Camera2's SENSOR_COLOR_TRANSFORM matrices
     * (XYZ → camera RGB, row-major); the profile stores their inverses.
     */
    fun harvest(
        model: String,
        sensorId: String,
        whiteLevel: Float,
        blackLevel: Float,
        illuminant1Code: Int,
        illuminant2Code: Int,
        xyzToCam1: FloatArray?,
        xyzToCam2: FloatArray?,
    ): DeviceProfile {
        fun camToXyz(m: FloatArray?): List<Float> =
            (m?.let { Mat3(it).inverse() } ?: Mat3.IDENTITY).m.toList()
        return DeviceProfile(
            model = model,
            sensorId = sensorId,
            source = "harvested",
            sensor = SensorCalibration(
                whiteLevel = whiteLevel,
                // static metadata has no per-ISO black — one point, interpolation clamps
                blackLevels = mapOf(100 to blackLevel),
                illuminant1Cct = illuminantCct(illuminant1Code),
                illuminant2Cct = illuminantCct(illuminant2Code),
                colorMatrix1 = camToXyz(xyzToCam1),
                colorMatrix2 = camToXyz(xyzToCam2),
            ),
        )
    }
}
