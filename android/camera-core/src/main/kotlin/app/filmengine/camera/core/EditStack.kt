package app.filmengine.camera.core

import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeDescriptor
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.FilmNodes

/**
 * One editor layer: exactly one engine node the user can toggle and tune.
 * The stack is ordered top-of-code = first applied (source side).
 */
data class EditLayer(
    val type: String,
    val params: Map<String, Float> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

/**
 * The editor's layer model (B8): a linear stack of layers ↔ a chain
 * [ProcessGraph] ending in `srgb_output`. The mapping is bijective, so
 * version history can store the recipe wire format (RecipeCodec JSON of the
 * graph) — the editor reconstructs the stack from any stored version, and a
 * stored version IS a sharable recipe. Layer enabled ↔ node enabled: the
 * compiler already skips disabled nodes, so toggling never rebuilds anything.
 *
 * Pure Kotlin — JVM-testable, no Android types.
 */
object EditStack {

    /** Same combined registry the preview uses: builtin + film nodes. */
    val registry: NodeRegistry = NodeRegistry.builtin + NodeRegistry(FilmNodes.all)

    private val compiler = GraphCompiler(registry)

    /** Node types offered in the editor palette, in suggested stack order. */
    val palette: List<String> = listOf(
        "exposure", "white_balance", "tone_curve", "tone_map", "saturation",
        "film_sim", "halation", "bloom", "gaussian_blur", "grain",
    )

    fun descriptor(type: String): NodeDescriptor = registry.descriptor(type)

    /** A new layer of the given type with descriptor defaults. */
    fun defaultLayer(type: String): EditLayer = EditLayer(
        type = type,
        params = descriptor(type).params.associate { it.key to it.default },
        options = if (type == "film_sim") mapOf("stock" to "chroma-100") else emptyMap(),
    )

    /** Stack → chain graph (always terminated by `srgb_output`). */
    fun toGraph(layers: List<EditLayer>): ProcessGraph {
        val nodes = mutableListOf<NodeInstance>()
        val edges = mutableListOf<Edge>()
        layers.forEachIndexed { i, l ->
            nodes += NodeInstance("l$i", l.type, l.params, l.enabled, l.options)
            if (i > 0) edges += Edge("l${i - 1}", "l$i")
        }
        nodes += NodeInstance("out", "srgb_output")
        if (layers.isNotEmpty()) edges += Edge("l${layers.size - 1}", "out")
        return ProcessGraph(nodes, edges, outputNodeId = "out")
    }

    /**
     * Chain graph → stack. Inverse of [toGraph] for any chain ending in
     * `srgb_output` (the terminal output node is not a layer).
     * Throws on non-chain graphs — the editor only ever stores chains.
     */
    fun fromGraph(graph: ProcessGraph): List<EditLayer> {
        val next = graph.edges.associate { it.from to it.to }
        require(next.size == graph.edges.size) { "Not a chain: branching graph" }
        val heads = graph.nodes.map { it.id }.toSet() - graph.edges.map { it.to }.toSet()
        require(heads.size == 1) { "Not a chain: ${heads.size} head nodes" }
        val byId = graph.nodes.associateBy { it.id }
        val layers = mutableListOf<EditLayer>()
        var id: String? = heads.single()
        while (id != null) {
            val node = byId.getValue(id)
            if (node.type != "srgb_output") {
                layers += EditLayer(node.type, node.params, node.options, node.enabled)
            }
            id = next[id]
        }
        return layers
    }

    /** Compile the stack for rendering (validation + defaults + clamping). */
    fun compile(layers: List<EditLayer>): ExecutionPlan = compiler.compile(toGraph(layers))
}
