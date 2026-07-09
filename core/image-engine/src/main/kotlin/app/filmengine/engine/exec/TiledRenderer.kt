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
        val out = ImageBuffer.alloc(source.width, source.height)
        renderStreaming(
            plan, source.width, source.height,
            fetch = { x, y, w, h -> source.crop(x, y, w, h) },
            write = { x, y, tile -> out.blit(tile, 0, 0, x, y, tile.width, tile.height) },
        )
        return out
    }

    /**
     * Streaming variant: tiles are fetched and delivered through callbacks, so
     * neither the full float source nor the full float output ever exists in
     * memory — a 48MP export otherwise needs ~1.5 GB of ImageBuffer (B8).
     *
     * [fetch] must return the *margin-expanded* region it is asked for (the
     * renderer computes margins itself — callers just crop/decode that rect);
     * [write] receives exact tile-sized buffers at their absolute position.
     */
    fun renderStreaming(
        plan: ExecutionPlan,
        width: Int,
        height: Int,
        fetch: (x: Int, y: Int, w: Int, h: Int) -> ImageBuffer,
        write: (x: Int, y: Int, tile: ImageBuffer) -> Unit,
    ) {
        val margin = plan.tileMargin
        var y0 = 0
        while (y0 < height) {
            val h0 = minOf(tileSize, height - y0)
            var x0 = 0
            while (x0 < width) {
                val w0 = minOf(tileSize, width - x0)
                val mx = maxOf(0, x0 - margin)
                val my = maxOf(0, y0 - margin)
                val mx1 = minOf(width, x0 + w0 + margin)
                val my1 = minOf(height, y0 + h0 + margin)

                val shifted = ExecutionPlan(
                    plan.steps.map {
                        it.copy(params = it.params + mapOf(TILE_OX to mx.toFloat(), TILE_OY to my.toFloat()))
                    },
                    plan.outputState,
                    plan.tileMargin,
                )
                val rendered = backend.render(shifted, fetch(mx, my, mx1 - mx, my1 - my))
                val tile = ImageBuffer.alloc(w0, h0)
                tile.blit(rendered, srcX = x0 - mx, srcY = y0 - my, dstX = 0, dstY = 0, w = w0, h = h0)
                write(x0, y0, tile)
                x0 += tileSize
            }
            y0 += tileSize
        }
    }

    companion object {
        /** Absolute pixel offset of the current tile — read by position-dependent kernels. */
        const val TILE_OX = "tile_ox"
        const val TILE_OY = "tile_oy"
    }
}
