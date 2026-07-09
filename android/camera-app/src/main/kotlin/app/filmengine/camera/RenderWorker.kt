package app.filmengine.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.filmengine.camera.core.CaptureRender
import app.filmengine.engine.exec.TiledRenderer
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels

/**
 * B5 background half: the captured JPEG is already safe in MediaStore; this
 * job applies the selected film graph and/or the chosen crop, then **overwrites
 * the original entry in place** — so the photo in the gallery is the one the
 * user framed with the filter on, not a separate un-filtered file.
 */
class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val stockId = inputData.getString(KEY_STOCK)          // null = crop only, no film sim
        val cropSquare = inputData.getBoolean(KEY_CROP_SQUARE, false)
        if (stockId == null && !cropSquare) return Result.success() // nothing to do

        var bitmap = MediaImages.load(applicationContext, uri, MAX_EDGE) ?: return Result.failure()
        if (cropSquare) bitmap = MediaImages.centerCropSquare(bitmap)
        val w = bitmap.width
        val h = bitmap.height

        val out = if (stockId != null) {
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

        MediaImages.overwriteJpeg(applicationContext, uri, out)
        return Result.success()
    }

    companion object {
        private const val KEY_URI = "jpeg_uri"
        private const val KEY_STOCK = "stock_id"
        private const val KEY_CROP_SQUARE = "crop_square"

        // ponytail: capture render capped at 2560px long edge (heap-friendly for
        // the whole-image float buffer); true full-res is the editor's streaming
        // ExportWorker (B8). Raise only alongside a tiled JPEG-region decode here.
        private const val MAX_EDGE = 2560

        /** Enqueue post-processing. Pass [stockId] = null for a crop-only pass. */
        fun enqueue(context: Context, jpegUri: Uri, stockId: String?, cropSquare: Boolean) {
            val request = OneTimeWorkRequestBuilder<RenderWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URI to jpegUri.toString(),
                        KEY_STOCK to stockId,
                        KEY_CROP_SQUARE to cropSquare,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
