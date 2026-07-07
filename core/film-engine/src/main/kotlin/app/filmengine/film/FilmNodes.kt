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
    )

    val all = listOf(FILM_SIM)
}
