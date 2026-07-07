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

    companion object {
        val builtin = NodeRegistry(BuiltinNodes.all)
    }
}

object BuiltinNodes {
    private val S = ColorState.SCENE_LINEAR

    val EXPOSURE = NodeDescriptor(
        "exposure", listOf(ParamSpec("stops", 0f, -8f, 8f)), S, S
    )
    val WHITE_BALANCE = NodeDescriptor(
        "white_balance",
        listOf(ParamSpec("r_gain", 1f, 0.2f, 5f), ParamSpec("b_gain", 1f, 0.2f, 5f)),
        S, S
    )
    val COLOR_MATRIX = NodeDescriptor(
        "color_matrix",
        listOf("m00" to 1f, "m01" to 0f, "m02" to 0f,
               "m10" to 0f, "m11" to 1f, "m12" to 0f,
               "m20" to 0f, "m21" to 0f, "m22" to 1f)
            .map { (k, d) -> ParamSpec(k, d, -4f, 4f) },
        S, S
    )
    /** Slope through an 18% grey pivot — placeholder until the filmic spline (M2). */
    val TONE_CURVE = NodeDescriptor(
        "tone_curve", listOf(ParamSpec("contrast", 1f, 0.2f, 3f)), S, S
    )
    val SATURATION = NodeDescriptor(
        "saturation", listOf(ParamSpec("amount", 1f, 0f, 3f)), S, S
    )
    /** Working space (linear Rec.2020) -> encoded sRGB. Terminal transform. */
    val SRGB_OUTPUT = NodeDescriptor(
        "srgb_output", emptyList(), S, ColorState.DISPLAY_SRGB
    )

    val all = listOf(EXPOSURE, WHITE_BALANCE, COLOR_MATRIX, TONE_CURVE, SATURATION, SRGB_OUTPUT)
}
