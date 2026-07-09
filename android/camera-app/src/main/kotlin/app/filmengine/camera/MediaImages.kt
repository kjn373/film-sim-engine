package app.filmengine.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.heifwriter.HeifWriter

/**
 * MediaStore image I/O shared by the capture render job (B5) and the editor
 * (B8). All methods are blocking — call from a worker/IO context.
 */
object MediaImages {

    /** EXIF-oriented, downsampled decode. Software allocation so pixels are readable. */
    fun load(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(
                    sampleSize(maxOf(info.size.width, info.size.height), maxEdge)
                )
            }
        } else {
            // ponytail: pre-P fallback ignores EXIF rotation (API 26/27 only)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxEdge)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()

    fun sampleSize(maxDim: Int, maxEdge: Int): Int {
        var n = 1
        while (maxDim / (n * 2) >= maxEdge) n *= 2
        return n
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.substringBeforeLast('.')

    fun saveJpeg(context: Context, bitmap: Bitmap, name: String): Uri {
        val uri = insert(context, name, "image/jpeg")
        context.contentResolver.openOutputStream(uri)
            ?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ?: error("Cannot open $uri for writing")
        return uri
    }

    /** Rewrite an existing MediaStore JPEG in place (we own the entry). */
    fun overwriteJpeg(context: Context, uri: Uri, bitmap: Bitmap) {
        context.contentResolver.openOutputStream(uri, "wt")
            ?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ?: error("Cannot open $uri for writing")
    }

    /** Centre square crop (for the 1:1 aspect ratio). Returns a new bitmap. */
    fun centerCropSquare(src: Bitmap): Bitmap {
        val edge = minOf(src.width, src.height)
        if (edge == src.width && edge == src.height) return src
        return Bitmap.createBitmap(src, (src.width - edge) / 2, (src.height - edge) / 2, edge, edge)
    }

    /** HEIF via the hardware codec. heifwriter needs API 28+ (manifest-overridden). */
    @RequiresApi(Build.VERSION_CODES.P)
    fun saveHeif(context: Context, bitmap: Bitmap, name: String): Uri {
        val uri = insert(context, name, "image/heic")
        context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
            val writer = HeifWriter.Builder(
                pfd.fileDescriptor, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP,
            ).setQuality(90).build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(WRITE_TIMEOUT_MS)
            writer.close()
        } ?: error("Cannot open $uri for writing")
        return uri
    }

    private fun insert(context: Context, name: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FilmEngine")
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
    }

    private const val WRITE_TIMEOUT_MS = 10_000L
}
