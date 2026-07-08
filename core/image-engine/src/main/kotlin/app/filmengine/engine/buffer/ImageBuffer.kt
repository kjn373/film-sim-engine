package app.filmengine.engine.buffer

/**
 * CPU-side image: interleaved RGBA, Float per channel.
 * Color interpretation (linear/display, gamut) is tracked by the graph, not the buffer.
 */
class ImageBuffer(val width: Int, val height: Int, val data: FloatArray) {
    init {
        require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
        require(data.size == width * height * 4) {
            "Expected ${width * height * 4} floats, got ${data.size}"
        }
    }

    fun copy(): ImageBuffer = ImageBuffer(width, height, data.copyOf())

    /** RGBA at (x, y). */
    fun pixel(x: Int, y: Int): FloatArray {
        val i = (y * width + x) * 4
        return floatArrayOf(data[i], data[i + 1], data[i + 2], data[i + 3])
    }

    /** Copies the rectangle (x, y, w, h) into a new buffer. */
    fun crop(x: Int, y: Int, w: Int, h: Int): ImageBuffer {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height) {
            "Crop ($x,$y ${w}x$h) outside ${width}x$height"
        }
        val out = alloc(w, h)
        for (row in 0 until h) {
            val srcStart = ((y + row) * width + x) * 4
            data.copyInto(out.data, row * w * 4, srcStart, srcStart + w * 4)
        }
        return out
    }

    /** Copies a (w, h) rectangle from [src] at (srcX, srcY) into this at (dstX, dstY). */
    fun blit(src: ImageBuffer, srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        require(srcX >= 0 && srcY >= 0 && srcX + w <= src.width && srcY + h <= src.height) { "blit source out of range" }
        require(dstX >= 0 && dstY >= 0 && dstX + w <= width && dstY + h <= height) { "blit destination out of range" }
        for (row in 0 until h) {
            val srcStart = ((srcY + row) * src.width + srcX) * 4
            src.data.copyInto(data, ((dstY + row) * width + dstX) * 4, srcStart, srcStart + w * 4)
        }
    }

    companion object {
        fun alloc(width: Int, height: Int): ImageBuffer =
            ImageBuffer(width, height, FloatArray(width * height * 4))
    }
}
