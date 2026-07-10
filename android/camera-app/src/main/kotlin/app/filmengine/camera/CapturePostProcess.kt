package app.filmengine.camera

import android.content.Context
import android.graphics.Bitmap
import app.filmengine.camera.core.CaptureRender
import app.filmengine.engine.exec.TiledRenderer
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels
import java.io.File

/**
 * B5 capture pipeline (revised): the JPEG lands in a private cache file, never
 * MediaStore directly — this applies the selected film graph and/or crop and
 * makes the ONE MediaStore insert with final bytes. Runs synchronously (on a
 * background dispatcher, called from the capture coroutine) rather than via
 * WorkManager: a deferred background job left a real window where the gallery
 * showed the unfiltered photo, and WorkManager constraints (battery, Doze) can
 * delay execution far past when the user expects to see the result.
 */
object CapturePostProcess {

    // ponytail: capped at 2560px long edge (heap-friendly for the whole-image
    // float buffer); true full-res is the editor's streaming ExportWorker (B8).
    // Raise only alongside a tiled JPEG-region decode here.
    private const val MAX_EDGE = 2560

    /** Processes [file] in place and deletes it; returns the final MediaStore Uri. */
    fun process(context: Context, file: File, stockId: String?, cropSquare: Boolean) {
        try {
            val name = file.nameWithoutExtension
            if (stockId == null && !cropSquare) {
                MediaImages.insertJpegBytes(context, file, name)
                return
            }
            var bitmap = MediaImages.loadFile(file, MAX_EDGE) ?: error("Could not decode capture")
            if (cropSquare) bitmap = MediaImages.centerCropSquare(bitmap)

            val out = if (stockId != null) {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                // ponytail: CPU backend — the GLES context is owned by the live-preview
                // thread; a dedicated export EGL context comes with a GPU export path.
                val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)
                val rendered = TiledRenderer(backend)
                    .render(CaptureRender.plan(stockId), CaptureRender.decode(pixels, w, h))
                Bitmap.createBitmap(CaptureRender.encode(rendered), w, h, Bitmap.Config.ARGB_8888)
            } else {
                bitmap
            }
            MediaImages.saveJpeg(context, out, name)
        } finally {
            file.delete()
        }
    }
}
