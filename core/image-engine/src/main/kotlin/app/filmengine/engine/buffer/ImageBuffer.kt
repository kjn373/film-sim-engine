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

    companion object {
        fun alloc(width: Int, height: Int): ImageBuffer =
            ImageBuffer(width, height, FloatArray(width * height * 4))
    }
}
