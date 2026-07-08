package app.filmengine.camera.core

import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.GraphCompiler
import app.filmengine.engine.graph.BuiltinNodes
import app.filmengine.engine.graph.ColorState
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.NodeRegistry
import app.filmengine.engine.graph.ProcessGraph
import app.filmengine.film.FilmNodes

/**
 * Builds a [ProcessGraph] for the live camera preview and compiles it into an
 * [ExecutionPlan].  The graph and its compilation are parameterized by the
 * selected film stock and the current [QualityLevel] from the [PerfMonitor].
 *
 * One graph definition, two compilation profiles (preview vs. export) — the
 * color transform is always identical; only the optional spatial nodes differ.
 * (ARCHITECTURE.md §10: "preview and export can never drift apart visually
 * except in defined quality knobs.")
 */
object PreviewGraph {

    /**
     * Combined registry: core built-in nodes + film-engine nodes.
     * The `+` operator merges the two without either module importing the other.
     */
    private val registry: NodeRegistry =
        NodeRegistry.builtin + NodeRegistry(FilmNodes.all)

    private val compiler = GraphCompiler(registry)

    /**
     * Build a preview [ExecutionPlan] for the given stock and quality level.
     *
     * @param stockId  Film stock id (e.g. "chroma-100"), or `null` for a
     *                 passthrough chain (exposure → sRGB output only).
     * @param quality  Current degradation level; controls which spatial nodes
     *                 are included and at what strength.
     * @return a compiled [ExecutionPlan] ready for [PreviewRenderer.renderFrame].
     */
    fun compile(stockId: String?, quality: QualityLevel): ExecutionPlan {
        val graph = if (stockId == null) passthroughGraph() else stockGraph(stockId, quality)
        return compiler.compile(graph)
    }

    // ── graph builders ──────────────────────────────────────────────────────

    /** Passthrough: exposure (neutral) → sRGB output.  Useful for "None". */
    private fun passthroughGraph(): ProcessGraph {
        val nodes = listOf(
            NodeInstance("exp", "exposure", mapOf("stops" to 0f)),
            NodeInstance("out", "srgb_output"),
        )
        return ProcessGraph(
            nodes = nodes,
            edges = listOf(Edge("exp", "out")),
            outputNodeId = "out",
            sourceState = ColorState.SCENE_LINEAR,
        )
    }

    /** Film-simulation chain, quality-gated spatial nodes. */
    private fun stockGraph(stockId: String, quality: QualityLevel): ProcessGraph {
        val nodes = mutableListOf<NodeInstance>()
        val edges = mutableListOf<Edge>()
        var prev: String

        // ── exposure (neutral, first node — engine expects scene-linear in) ──
        nodes += NodeInstance("exp", "exposure", mapOf("stops" to 0f))
        prev = "exp"

        // ── film simulation (always present — this IS the feature) ──────────
        nodes += NodeInstance(
            "film", "film_sim",
            mapOf("push" to 0f, "strength" to 1f),
            options = mapOf("stock" to stockId),
        )
        edges += Edge(prev, "film")
        prev = "film"

        // ── halation (spatial, quality-gated) ───────────────────────────────
        if (quality <= QualityLevel.NO_GRAIN) {
            nodes += NodeInstance(
                "hal", "halation",
                mapOf("threshold" to 1f, "strength" to 0.6f, "sigma" to 6f),
            )
            edges += Edge(prev, "hal")
            prev = "hal"
        }

        // ── grain (spatial, quality-gated + amount scaling) ─────────────────
        val grainAmount = when (quality) {
            QualityLevel.FULL -> 0.25f
            QualityLevel.REDUCED_GRAIN -> 0.125f
            else -> null // dropped
        }
        if (grainAmount != null) {
            nodes += NodeInstance(
                "grain", "grain",
                mapOf("amount" to grainAmount, "seed" to 0f),
            )
            edges += Edge(prev, "grain")
            prev = "grain"
        }

        // ── sRGB output (always last) ───────────────────────────────────────
        nodes += NodeInstance("out", "srgb_output")
        edges += Edge(prev, "out")

        return ProcessGraph(
            nodes = nodes,
            edges = edges,
            outputNodeId = "out",
            sourceState = ColorState.SCENE_LINEAR,
        )
    }
}
