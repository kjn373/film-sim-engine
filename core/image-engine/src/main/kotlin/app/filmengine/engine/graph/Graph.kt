package app.filmengine.engine.graph

/**
 * Color state carried on every graph edge. The compiler rejects graphs that feed
 * display-referred data into scene-referred nodes (ARCHITECTURE.md §5.1).
 */
enum class ColorState { SCENE_LINEAR, DISPLAY_SRGB }

data class ParamSpec(
    val key: String,
    val default: Float,
    val min: Float,
    val max: Float,
)

/** Static description of a node type: its parameters and color-state contract. */
data class NodeDescriptor(
    val type: String,
    val params: List<ParamSpec>,
    val input: ColorState,
    val output: ColorState,
    /** Non-numeric option keys this type accepts (e.g. "stock" for film_sim). */
    val optionKeys: List<String> = emptyList(),
    /**
     * True for pure per-pixel color transforms (no neighborhood, no position) —
     * exactly the nodes PlanFusion may bake into a 3D LUT (ARCHITECTURE.md D2).
     */
    val fusable: Boolean = false,
    /**
     * How many pixels of neighborhood this node reads, given its resolved params.
     * Summed over a plan it becomes the tile overlap TiledRenderer needs for
     * tiled output to be exact.
     */
    val spatialRadius: (Map<String, Float>) -> Int = { 0 },
)

/** One node in a user's graph: a reference to a type plus parameter values. */
data class NodeInstance(
    val id: String,
    val type: String,
    val params: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true,
    val options: Map<String, String> = emptyMap(),
)

data class Edge(val from: String, val to: String)

/**
 * A processing graph as authored (by the layer stack or a recipe).
 * The source image enters at the chain's head; [outputNodeId] is the render target.
 */
// ponytail: single-input nodes only — the DAG degenerates to chains; multi-input
// ports arrive with layer blending/masks (M3).
data class ProcessGraph(
    val nodes: List<NodeInstance>,
    val edges: List<Edge>,
    val outputNodeId: String,
    val sourceState: ColorState = ColorState.SCENE_LINEAR,
)

class GraphValidationException(message: String) : Exception(message)

class NodeRegistry(descriptors: List<NodeDescriptor>) {
    private val byType = descriptors.associateBy { it.type }

    fun descriptor(type: String): NodeDescriptor =
        byType[type] ?: throw GraphValidationException("Unknown node type: $type")

    /** Merge two registries; the right-hand side wins on type collisions. */
    operator fun plus(other: NodeRegistry): NodeRegistry =
        NodeRegistry((byType + other.byType).values.toList())

    companion object {
        val builtin = NodeRegistry(BuiltinNodes.all)
    }
}

object BuiltinNodes {
    private val S = ColorState.SCENE_LINEAR

    val EXPOSURE = NodeDescriptor(
        "exposure", listOf(ParamSpec("stops", 0f, -8f, 8f)), S, S, fusable = true
    )
    val WHITE_BALANCE = NodeDescriptor(
        "white_balance",
        listOf(ParamSpec("r_gain", 1f, 0.2f, 5f), ParamSpec("b_gain", 1f, 0.2f, 5f)),
        S, S, fusable = true
    )
    val COLOR_MATRIX = NodeDescriptor(
        "color_matrix",
        listOf("m00" to 1f, "m01" to 0f, "m02" to 0f,
               "m10" to 0f, "m11" to 1f, "m12" to 0f,
               "m20" to 0f, "m21" to 0f, "m22" to 1f)
            .map { (k, d) -> ParamSpec(k, d, -4f, 4f) },
        S, S, fusable = true
    )
    /** Slope through an 18% grey pivot — placeholder until the filmic spline (M2). */
    val TONE_CURVE = NodeDescriptor(
        "tone_curve", listOf(ParamSpec("contrast", 1f, 0.2f, 3f)), S, S, fusable = true
    )
    val SATURATION = NodeDescriptor(
        "saturation", listOf(ParamSpec("amount", 1f, 0f, 3f)), S, S, fusable = true
    )
    /**
     * Working space (linear Rec.2020) -> encoded sRGB. Terminal transform.
     * Deliberately NOT fusable: its hard gamut clamp is a slope kink that the
     * OETF amplifies ~13x near black — a 33³ LUT interpolates ~0.09 of error
     * across that kink on saturated colors. It stays an exact analytic pass
     * until a proper gamut-compression node replaces the clamp.
     */
    val SRGB_OUTPUT = NodeDescriptor(
        "srgb_output", emptyList(), S, ColorState.DISPLAY_SRGB
    )

    /**
     * Neutral filmic display transform (ACES-fitted rational curve) — the
     * "look neutral" default for the non-film path. Film stocks tone-map
     * through their own characteristic curve instead.
     */
    val TONE_MAP = NodeDescriptor(
        "tone_map", listOf(ParamSpec("exposure_bias", 0f, -4f, 4f)), S, S, fusable = true
    )

    /**
     * RAW develop (A5b): desaturate channels toward neutral as they approach
     * sensor clip — turns magenta-clipped skies white. smoothstep from
     * `threshold` to 1.0 on the max channel, scaled by `strength`.
     */
    val HIGHLIGHT_RECONSTRUCTION = NodeDescriptor(
        "highlight_reconstruction",
        listOf(
            ParamSpec("threshold", 0.95f, 0.5f, 0.99f),
            ParamSpec("strength", 1f, 0f, 1f),
        ),
        S, S, fusable = true
    )
    /**
     * RAW develop (A5b): hyperbolic shadow gain, 1 + amount·range/(range+luma) —
     * multiplicative so true black stays black and hue is preserved.
     */
    val SHADOW_LIFT = NodeDescriptor(
        "shadow_lift",
        listOf(
            ParamSpec("amount", 0.5f, 0f, 4f),
            ParamSpec("range", 0.1f, 0.01f, 1f),
        ),
        S, S, fusable = true
    )

    val GAUSSIAN_BLUR = NodeDescriptor(
        "gaussian_blur", listOf(ParamSpec("sigma", 2f, 0.1f, 24f)), S, S,
        spatialRadius = { p -> app.filmengine.engine.node.Gaussian.radius(p.getValue("sigma")) },
    )
    /** Additive bloom: bright pass above threshold, blurred, added back. */
    val BLOOM = NodeDescriptor(
        "bloom",
        listOf(
            ParamSpec("threshold", 1f, 0f, 8f),
            ParamSpec("intensity", 0.5f, 0f, 4f),
            ParamSpec("sigma", 4f, 0.5f, 24f),
        ),
        S, S,
        spatialRadius = { p -> app.filmengine.engine.node.Gaussian.radius(p.getValue("sigma")) },
    )

    val all = listOf(
        EXPOSURE, WHITE_BALANCE, COLOR_MATRIX, TONE_CURVE, TONE_MAP, SATURATION, SRGB_OUTPUT,
        HIGHLIGHT_RECONSTRUCTION, SHADOW_LIFT,
        GAUSSIAN_BLUR, BLOOM,
    )
}
