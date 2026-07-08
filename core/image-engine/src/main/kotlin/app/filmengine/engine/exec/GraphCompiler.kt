package app.filmengine.engine.exec

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.graph.ColorState
import app.filmengine.engine.graph.GraphValidationException
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph

/** One executable step: node id + type + fully resolved (defaulted, clamped) params. */
data class Step(
    val nodeId: String,
    val type: String,
    val params: Map<String, Float>,
    val options: Map<String, String> = emptyMap(),
    val inputState: ColorState = ColorState.SCENE_LINEAR,
    /** Present only on fused steps of type [BakedLut.TYPE]. */
    val lut: BakedLut? = null,
)

/**
 * Payload of a fused pointwise run: an N³ RGBA lattice, r fastest then g then b —
 * the memory order of a GL 3D texture. Input domain is shaped scene-linear
 * (log2(x/black + 1), exact at 0); see PlanFusion in cpu-renderer for the bake.
 */
class BakedLut(val size: Int, val data: FloatArray) {
    init {
        require(size >= 2) { "LUT size must be at least 2" }
        require(data.size == size * size * size * 4) {
            "Expected ${size * size * size * 4} floats for a $size³ RGBA LUT, got ${data.size}"
        }
    }

    companion object {
        const val TYPE = "baked_lut"
        const val KEY_SIZE = "lut_size"
        const val KEY_SHAPER_BLACK = "shaper_black"
        const val KEY_SHAPER_WHITE = "shaper_white"
    }
}

data class ExecutionPlan(
    val steps: List<Step>,
    val outputState: ColorState,
)

/** Backend contract. Implementations: cpu-renderer (reference), gpu-renderer (production). */
interface RenderBackend {
    fun render(plan: ExecutionPlan, source: ImageBuffer): ImageBuffer
}

/**
 * ProcessGraph -> ExecutionPlan:
 * orders nodes (walks back from the output), eliminates dead nodes (anything not on
 * the output path), skips disabled nodes, validates color states, resolves params.
 */
class GraphCompiler(private val registry: NodeRegistry = NodeRegistry.builtin) {

    fun compile(graph: ProcessGraph): ExecutionPlan {
        val byId = graph.nodes.associateBy { it.id }
        if (byId.size != graph.nodes.size) {
            throw GraphValidationException("Duplicate node ids")
        }
        val incoming = mutableMapOf<String, String>()
        for (e in graph.edges) {
            if (e.from !in byId || e.to !in byId) {
                throw GraphValidationException("Edge references unknown node: $e")
            }
            if (incoming.put(e.to, e.from) != null) {
                throw GraphValidationException("Node ${e.to} has multiple inputs (unsupported)")
            }
        }
        if (graph.outputNodeId !in byId) {
            throw GraphValidationException("Output node ${graph.outputNodeId} not in graph")
        }

        // Walk back from the output; everything off this path is dead and dropped.
        val chain = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        var cursor: String? = graph.outputNodeId
        while (cursor != null) {
            if (!visited.add(cursor)) throw GraphValidationException("Cycle detected at $cursor")
            chain.addFirst(cursor)
            cursor = incoming[cursor]
        }

        var state = graph.sourceState
        val steps = mutableListOf<Step>()
        for (id in chain) {
            val node = byId.getValue(id)
            if (!node.enabled) continue
            val desc = registry.descriptor(node.type)
            if (desc.input != state) {
                throw GraphValidationException(
                    "Node $id (${node.type}) expects ${desc.input} input but receives $state"
                )
            }
            val inputState = state
            state = desc.output
            for (key in node.options.keys) {
                if (key !in desc.optionKeys) {
                    throw GraphValidationException("Unknown option '$key' for node type ${desc.type}")
                }
            }
            steps += Step(node.id, node.type, resolveParams(node.params, desc), node.options, inputState)
        }
        return ExecutionPlan(steps, state)
    }

    private fun resolveParams(
        given: Map<String, Float>,
        desc: app.filmengine.engine.graph.NodeDescriptor,
    ): Map<String, Float> {
        val known = desc.params.associateBy { it.key }
        for (key in given.keys) {
            if (key !in known) {
                throw GraphValidationException("Unknown param '$key' for node type ${desc.type}")
            }
        }
        return desc.params.associate { spec ->
            spec.key to (given[spec.key] ?: spec.default).coerceIn(spec.min, spec.max)
        }
    }
}
