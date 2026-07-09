package app.filmengine.camera.core

import androidx.camera.core.AspectRatio

/**
 * Selectable capture aspect ratio. CameraX natively produces 4:3 and 16:9;
 * 1:1 is captured at 4:3 (the closest native) and centre-cropped square in the
 * render worker (there is no native square sensor output).
 *
 * [displayRatio] is width / height for a portrait viewfinder, used to letterbox
 * the on-screen preview so what you see matches what is saved.
 */
enum class AspectRatioMode(
    val label: String,
    val cameraXRatio: Int,
    val displayRatio: Float,
    val cropSquare: Boolean,
) {
    RATIO_4_3("4:3", AspectRatio.RATIO_4_3, 3f / 4f, false),
    RATIO_16_9("16:9", AspectRatio.RATIO_16_9, 9f / 16f, false),
    RATIO_1_1("1:1", AspectRatio.RATIO_4_3, 1f, true),
}
