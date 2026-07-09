package app.filmengine.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.filmengine.camera.core.CaptureRender
import app.filmengine.camera.core.EditStack
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.exec.TiledRenderer
import app.filmengine.recipe.RecipeCodec
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels
import app.filmengine.render.cpu.PlanFusion

/**
 * B8 export: render the edited graph over the source image at export
 * resolution, tiled and streaming — source tiles come from a
 * [BitmapRegionDecoder] and results land directly in the output bitmap, so
 * the full float image never exists in memory (TiledRenderer.renderStreaming).
 *
 * The plan is pass-fused (A4 gates: worst < 0.015, mean < 0.003) — on the CPU
 * an unfused film chain at full resolution would blow WorkManager's 10-minute
 * ceiling on large sensors.
 */
// ponytail: CPU backend; a dedicated export EGL context (GPU tiles) is the
// upgrade path once export time on-device says so.
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        // Graph JSON travels via a cache file — WorkManager Data caps at 10KB.
        val graphFile = inputData.getString(KEY_GRAPH_FILE)?.let { java.io.File(it) }
            ?: return Result.failure()
        val graphJson = runCatching { graphFile.readText() }.getOrNull()
            ?: return Result.failure()
        graphFile.delete()
        val format = inputData.getString(KEY_FORMAT) ?: FORMAT_JPEG

        val graph = RecipeCodec.decode(graphJson)
        val kernels = BuiltinCpuKernels.all + FilmCpuKernels.all
        val plan = PlanFusion.fuse(
            GraphCompiler(EditStack.registry).compile(graph), EditStack.registry, kernels,
        )

        val resolver = applicationContext.contentResolver
        val out = resolver.openInputStream(uri)?.use { stream ->
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(stream, false)
                ?: return Result.failure()
            try {
                renderTiled(plan, decoder)
            } finally {
                decoder.recycle()
            }
        } ?: return Result.failure()

        val oriented = applyExifRotation(uri, out)
        val name = (MediaImages.displayName(applicationContext, uri) ?: "FE") + "_edit"
        if (format == FORMAT_HEIF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            MediaImages.saveHeif(applicationContext, oriented, name)
        } else {
            MediaImages.saveJpeg(applicationContext, oriented, name)
        }
        return Result.success()
    }

    private fun renderTiled(
        plan: app.filmengine.engine.exec.ExecutionPlan,
        decoder: BitmapRegionDecoder,
    ): Bitmap {
        val n = MediaImages.sampleSize(maxOf(decoder.width, decoder.height), MAX_EDGE)
        val w = decoder.width / n
        val h = decoder.height / n
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)

        TiledRenderer(backend).renderStreaming(
            plan, w, h,
            fetch = { x, y, tw, th ->
                val rect = Rect(x * n, y * n, minOf(decoder.width, (x + tw) * n), minOf(decoder.height, (y + th) * n))
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = n
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var tile = decoder.decodeRegion(rect, opts)
                // subsampled region size can be off by a pixel — snap to exact
                if (tile.width != tw || tile.height != th) {
                    tile = Bitmap.createScaledBitmap(tile, tw, th, true)
                }
                val px = IntArray(tw * th)
                tile.getPixels(px, 0, tw, 0, 0, tw, th)
                tile.recycle()
                CaptureRender.decode(px, tw, th)
            },
            write = { x, y, tile ->
                out.setPixels(CaptureRender.encode(tile), 0, tile.width, x, y, tile.width, tile.height)
            },
        )
        return out
    }

    /** BitmapRegionDecoder ignores EXIF orientation — rotate the finished output. */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            applicationContext.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).rotationDegrees
            }
        }.getOrNull() ?: 0
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    companion object {
        private const val KEY_URI = "source_uri"
        private const val KEY_GRAPH_FILE = "graph_file"
        private const val KEY_FORMAT = "format"
        const val FORMAT_JPEG = "jpeg"
        const val FORMAT_HEIF = "heif"

        // ponytail: export capped at 6144px long edge — the output int bitmap
        // must fit the heap; raise alongside a streaming encoder if needed.
        private const val MAX_EDGE = 6144

        fun enqueue(context: Context, sourceUri: Uri, graphJson: String, format: String) {
            val graphFile = java.io.File.createTempFile("export_graph", ".json", context.cacheDir)
            graphFile.writeText(graphJson)
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URI to sourceUri.toString(),
                        KEY_GRAPH_FILE to graphFile.absolutePath,
                        KEY_FORMAT to format,
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
