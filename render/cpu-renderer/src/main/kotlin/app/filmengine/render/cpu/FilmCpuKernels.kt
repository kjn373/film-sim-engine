package app.filmengine.render.cpu

import app.filmengine.color.ColorSpaces
import app.filmengine.film.BuiltinStocks

object FilmCpuKernels {

    val filmSim = CpuKernel { src, step ->
        val stock = BuiltinStocks.byId(
            step.options["stock"] ?: error("film_sim node '${step.nodeId}' requires a 'stock' option")
        )
        val push = step.params.getValue("push")
        val strength = step.params.getValue("strength")

        pointwise(src) { px ->
            val r0 = px[0]; val g0 = px[1]; val b0 = px[2]

            // Spectral sensitivity (pre-development crosstalk)
            stock.sensitivity.transform(px)

            // Per-channel characteristic curve
            px[0] = stock.curve.eval(px[0], push)
            px[1] = stock.curve.eval(px[1], push)
            px[2] = stock.curve.eval(px[2], push)

            // Dye crosstalk (post-development), then saturation
            stock.dye.transform(px)
            val luma = ColorSpaces.LUMA_R * px[0] + ColorSpaces.LUMA_G * px[1] + ColorSpaces.LUMA_B * px[2]
            for (i in 0..2) px[i] = luma + (px[i] - luma) * stock.saturation

            // Mix with the unfilmed input by strength
            px[0] = r0 + (px[0] - r0) * strength
            px[1] = g0 + (px[1] - g0) * strength
            px[2] = b0 + (px[2] - b0) * strength
        }
    }

    val all: Map<String, CpuKernel> = mapOf(
        "film_sim" to filmSim,
        "halation" to SpatialCpuKernels.halation,
        "grain" to SpatialCpuKernels.grain,
    )
}
