package app.filmengine.engine.exec

import app.filmengine.engine.buffer.ImageBuffer

/**
 * Renders a plan in tiles so large exports fit in memory (ARCHITECTURE.md §5.3).
 * Each tile is expanded by the plan's [ExecutionPlan.tileMargin] so spatial nodes
 * see their full neighborhood — tiled output is EXACTLY equal to a whole-image
 * render, not approximately. Position-dependent nodes (grain) receive the tile's
 * absolute offset via the [TILE_OX]/[TILE_OY] params so their hashes stay
 * image-absolute.
 */
class TiledRenderer(
    private val backend: RenderBackend,
    private val tileSize: Int = 512,
) {
    init {
        require(tileSize >= 16) { "tileSize must be at least 16" }
    }

    fun render(plan: ExecutionPlan, source: ImageBuffer): ImageBuffer {
        if (source.width <= tileSize && source.height <= tileSize) {
            return backend.render(plan, source)
        }
        val margin = plan.tileMargin
        val out = ImageBuffer.alloc(source.width, source.height)
        var y0 = 0
        while (y0 < source.height) {
            val h0 = minOf(tileSize, source.height - y0)
            var x0 = 0
            while (x0 < source.width) {
                val w0 = minOf(tileSize, source.width - x0)
                val mx = maxOf(0, x0 - margin)
                val my = maxOf(0, y0 - margin)
                val mx1 = minOf(source.width, x0 + w0 + margin)
                val my1 = minOf(source.height, y0 + h0 + margin)

                val shifted = ExecutionPlan(
                    plan.steps.map {
                        it.copy(params = it.params + mapOf(TILE_OX to mx.toFloat(), TILE_OY to my.toFloat()))
                    },
                    plan.outputState,
                    plan.tileMargin,
                )
                val rendered = backend.render(shifted, source.crop(mx, my, mx1 - mx, my1 - my))
                out.blit(rendered, srcX = x0 - mx, srcY = y0 - my, dstX = x0, dstY = y0, w = w0, h = h0)
                x0 += tileSize
            }
            y0 += tileSize
        }
        return out
    }

    companion object {
        /** Absolute pixel offset of the current tile — read by position-dependent kernels. */
        const val TILE_OX = "tile_ox"
        const val TILE_OY = "tile_oy"
    }
}
