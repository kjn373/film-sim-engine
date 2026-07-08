package app.filmengine.film

import app.filmengine.engine.graph.ColorState
import app.filmengine.engine.graph.NodeDescriptor
import app.filmengine.engine.graph.ParamSpec

object FilmNodes {
    /**
     * Film simulation. Scene-linear in, scene-linear out (tone mapping is part of
     * the stock's characteristic curve). Option "stock" selects a FilmStock by id.
     */
    val FILM_SIM = NodeDescriptor(
        type = "film_sim",
        params = listOf(
            ParamSpec("push", 0f, -4f, 4f),
            ParamSpec("strength", 1f, 0f, 1f),
        ),
        input = ColorState.SCENE_LINEAR,
        output = ColorState.SCENE_LINEAR,
        optionKeys = listOf("stock"),
        fusable = true,
    )

    /** Red-orange glow around speculars — light bouncing off the film base. */
    val HALATION = NodeDescriptor(
        type = "halation",
        params = listOf(
            ParamSpec("threshold", 1f, 0f, 8f),
            ParamSpec("strength", 0.6f, 0f, 4f),
            ParamSpec("sigma", 6f, 0.5f, 24f),
        ),
        input = ColorState.SCENE_LINEAR,
        output = ColorState.SCENE_LINEAR,
        spatialRadius = { p -> app.filmengine.engine.node.Gaussian.radius(p.getValue("sigma")) },
    )

    /** Procedural, luminance-coupled monochromatic grain (strongest in midtones). */
    // ponytail: single-pixel noise — grain size/octaves and scanned tiles come with
    // premium packs (ARCHITECTURE.md §6).
    val GRAIN = NodeDescriptor(
        type = "grain",
        params = listOf(
            ParamSpec("amount", 0.25f, 0f, 1f),
            ParamSpec("seed", 0f, 0f, 65535f),
            // Injected by TiledRenderer so hash coordinates stay image-absolute;
            // 0 for whole-image renders.
            ParamSpec(app.filmengine.engine.exec.TiledRenderer.TILE_OX, 0f, 0f, 65535f),
            ParamSpec(app.filmengine.engine.exec.TiledRenderer.TILE_OY, 0f, 0f, 65535f),
        ),
        input = ColorState.SCENE_LINEAR,
        output = ColorState.SCENE_LINEAR,
    )

    val all = listOf(FILM_SIM, HALATION, GRAIN)
}
