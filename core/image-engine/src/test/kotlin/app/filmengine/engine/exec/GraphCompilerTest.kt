package app.filmengine.engine.exec

import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.GraphValidationException
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.ProcessGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraphCompilerTest {
    private val compiler = GraphCompiler()

    private fun node(id: String, type: String, params: Map<String, Float> = emptyMap(), enabled: Boolean = true) =
        NodeInstance(id, type, params, enabled)

    @Test
    fun `orders by edges regardless of node list order`() {
        val graph = ProcessGraph(
            nodes = listOf(
                node("out", "srgb_output"),
                node("sat", "saturation"),
                node("exp", "exposure"),
            ),
            edges = listOf(Edge("exp", "sat"), Edge("sat", "out")),
            outputNodeId = "out",
        )
        assertEquals(listOf("exp", "sat", "out"), compiler.compile(graph).steps.map { it.nodeId })
    }

    @Test
    fun `disabled nodes are skipped`() {
        val graph = ProcessGraph(
            nodes = listOf(
                node("exp", "exposure"),
                node("sat", "saturation", enabled = false),
                node("out", "srgb_output"),
            ),
            edges = listOf(Edge("exp", "sat"), Edge("sat", "out")),
            outputNodeId = "out",
        )
        assertEquals(listOf("exp", "out"), compiler.compile(graph).steps.map { it.nodeId })
    }

    @Test
    fun `dead nodes not on the output path are eliminated`() {
        val graph = ProcessGraph(
            nodes = listOf(
                node("exp", "exposure"),
                node("orphan", "saturation"),
                node("out", "srgb_output"),
            ),
            edges = listOf(Edge("exp", "out")),
            outputNodeId = "out",
        )
        assertEquals(listOf("exp", "out"), compiler.compile(graph).steps.map { it.nodeId })
    }

    @Test
    fun `rejects scene-referred node after output transform`() {
        val graph = ProcessGraph(
            nodes = listOf(node("out", "srgb_output"), node("exp", "exposure")),
            edges = listOf(Edge("out", "exp")),
            outputNodeId = "exp",
        )
        assertFailsWith<GraphValidationException> { compiler.compile(graph) }
    }

    @Test
    fun `rejects unknown node type and unknown param`() {
        assertFailsWith<GraphValidationException> {
            compiler.compile(
                ProcessGraph(listOf(node("x", "does_not_exist")), emptyList(), "x")
            )
        }
        assertFailsWith<GraphValidationException> {
            compiler.compile(
                ProcessGraph(listOf(node("e", "exposure", mapOf("nope" to 1f))), emptyList(), "e")
            )
        }
    }

    @Test
    fun `params get defaults and are clamped to range`() {
        val plan = compiler.compile(
            ProcessGraph(
                nodes = listOf(
                    node("e", "exposure", mapOf("stops" to 99f)), // above max 8
                    node("s", "saturation"),                      // default 1
                ),
                edges = listOf(Edge("e", "s")),
                outputNodeId = "s",
            )
        )
        assertEquals(8f, plan.steps[0].params.getValue("stops"))
        assertEquals(1f, plan.steps[1].params.getValue("amount"))
    }

    @Test
    fun `rejects cycles`() {
        val graph = ProcessGraph(
            nodes = listOf(node("a", "exposure"), node("b", "saturation")),
            edges = listOf(Edge("a", "b"), Edge("b", "a")),
            outputNodeId = "b",
        )
        assertFailsWith<GraphValidationException> { compiler.compile(graph) }
    }
}
