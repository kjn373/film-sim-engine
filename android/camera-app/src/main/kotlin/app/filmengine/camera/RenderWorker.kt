package app.filmengine.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

        val bitmap = loadBitmap(uri) ?: return Result.failure()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        // ponytail: CPU backend — the GLES context is owned by the live-preview
        // thread; a dedicated export EGL context comes with B8 tiled export.
        val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)
        val rendered = TiledRenderer(backend)
            .render(CaptureRender.plan(stockId), CaptureRender.decode(pixels, w, h))

        val out = Bitmap.createBitmap(CaptureRender.encode(rendered), w, h, Bitmap.Config.ARGB_8888)
        save(out, "${baseName(uri)}_$stockId")
        return Result.success()
    }

    /** EXIF-oriented, downsampled decode. Software allocation so pixels are readable. */
    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val resolver = applicationContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(
                    sampleSize(maxOf(info.size.width, info.size.height))
                )
            }
        } else {
            // ponytail: pre-P fallback ignores EXIF rotation (API 26/27 only)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()

    private fun sampleSize(maxDim: Int): Int {
        var n = 1
        while (maxDim / (n * 2) >= MAX_EDGE) n *= 2
        return n
    }

    private fun baseName(uri: Uri): String =
        applicationContext.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.substringBeforeLast('.')
            ?: "FE_processed"

    private fun save(bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FilmEngine")
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ?: error("Cannot open $uri for writing")
    }

    companion object {
        private const val KEY_URI = "jpeg_uri"
        private const val KEY_STOCK = "stock_id"

        // ponytail: processed preview capped at 2560px long edge (heap-friendly);
        // true full-res output is B8's tiled export.
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
