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
 * job renders the full-quality graph (same definition as the live preview,
 * FULL quality level) and saves the result as a new MediaStore entry.
 */
class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val stockId = inputData.getString(KEY_STOCK) ?: return Result.failure()

        val bitmap = MediaImages.load(applicationContext, uri, MAX_EDGE) ?: return Result.failure()
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

        val out = Bitmap.createBitmap(CaptureRender.encode(rendered), w, h, Bitmap.Config.ARGB_8888)
        val name = MediaImages.displayName(applicationContext, uri) ?: "FE_processed"
        MediaImages.saveJpeg(applicationContext, out, "${name}_$stockId")
        return Result.success()
    }

    companion object {
        private const val KEY_URI = "jpeg_uri"
        private const val KEY_STOCK = "stock_id"

        // ponytail: processed preview capped at 2560px long edge (heap-friendly);
        // true full-res output is the editor's tiled ExportWorker (B8).
        private const val MAX_EDGE = 2560

        fun enqueue(context: Context, jpegUri: Uri, stockId: String) {
            val request = OneTimeWorkRequestBuilder<RenderWorker>()
                .setInputData(workDataOf(KEY_URI to jpegUri.toString(), KEY_STOCK to stockId))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
