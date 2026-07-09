package app.filmengine.camera.core

import app.filmengine.color.ColorSpaces
import app.filmengine.color.Srgb
import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.ExecutionPlan

/**
 * Pure pixel/plan logic for the capture pipeline's full-quality render
 * (B5: capture → MediaStore → WorkManager render). Kept free of Android
 * types so the whole path is JVM-testable against the CPU backend.
 *
 * The int-pixel layout is ARGB packed — what both `Bitmap.getPixels`
 * and `BufferedImage.getRGB` produce.
 */
object CaptureRender {

    /**
     * The full-quality plan is the preview graph at [QualityLevel.FULL] —
     * one graph definition, two compilation profiles (ARCHITECTURE.md §10).
     */
    fun plan(stockId: String?): ExecutionPlan = PreviewGraph.compile(stockId, QualityLevel.FULL)

    /** sRGB-encoded ARGB ints → linear Rec.2020 [ImageBuffer]. */
    fun decode(argb: IntArray, width: Int, height: Int): ImageBuffer {
        require(argb.size == width * height) { "pixel count ${argb.size} != $width x $height" }
        val buf = ImageBuffer.alloc(width, height)
        val px = FloatArray(3)
        for (p in argb.indices) {
            val c = argb[p]
            px[0] = Srgb.eotf(((c shr 16) and 0xFF) / 255f)
            px[1] = Srgb.eotf(((c shr 8) and 0xFF) / 255f)
            px[2] = Srgb.eotf((c and 0xFF) / 255f)
            ColorSpaces.SRGB_TO_REC2020.transform(px)
            val i = p * 4
            buf.data[i] = px[0]
            buf.data[i + 1] = px[1]
            buf.data[i + 2] = px[2]
            buf.data[i + 3] = 1f
        }
        return buf
    }

    /** Rendered output (already sRGB-encoded by `srgb_output`) → opaque ARGB ints. */
    fun encode(buf: ImageBuffer): IntArray {
        val out = IntArray(buf.width * buf.height)
        for (p in out.indices) {
            val i = p * 4
            val r = (buf.data[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (buf.data[i + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (buf.data[i + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }
}
