package app.filmengine.recipe

import app.filmengine.engine.graph.ColorState
import app.filmengine.engine.graph.Edge
import app.filmengine.engine.graph.NodeInstance
import app.filmengine.engine.graph.ProcessGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecipeCodecTest {

    private fun sampleGraph() = ProcessGraph(
        nodes = listOf(
            NodeInstance("exp", "exposure", mapOf("stops" to 0.5f)),
            NodeInstance(
                "film", "film_sim", mapOf("push" to 1f),
                enabled = false, options = mapOf("stock" to "negato-400"),
            ),
            NodeInstance("out", "srgb_output"),
        ),
        edges = listOf(Edge("exp", "film"), Edge("film", "out")),
        outputNodeId = "out",
    )

    @Test
    fun `encode-decode round trip is identity`() {
        assertEquals(sampleGraph(), RecipeCodec.decode(RecipeCodec.encode(sampleGraph())))
    }

    @Test
    fun `pinned v1 fixture decodes - field names are a contract`() {
        // If this test breaks, a wire-format field was renamed without a migration.
        val v1 = """
            {
              "schemaVersion": 1,
              "graph": {
                "nodes": [
                  {"id": "e", "type": "exposure", "params": {"stops": 1.0}, "enabled": true, "options": {}},
                  {"id": "o", "type": "srgb_output"}
                ],
                "edges": [{"from": "e", "to": "o"}],
                "outputNodeId": "o",
                "sourceState": "SCENE_LINEAR"
              }
            }
        """.trimIndent()
        val graph = RecipeCodec.decode(v1)
        assertEquals(2, graph.nodes.size)
        assertEquals("exposure", graph.nodes[0].type)
        assertEquals(1.0f, graph.nodes[0].params.getValue("stops"))
        assertEquals(ColorState.SCENE_LINEAR, graph.sourceState)
        assertEquals("o", graph.outputNodeId)
    }

    @Test
    fun `unknown fields are ignored - same-major forward compatibility`() {
        val futureMinor = """
            {
              "schemaVersion": 1,
              "futureTopLevelThing": {"x": 1},
              "graph": {
                "nodes": [{"id": "o", "type": "srgb_output", "futureNodeField": 42}],
                "edges": [],
                "outputNodeId": "o",
                "sourceState": "SCENE_LINEAR"
              }
            }
        """.trimIndent()
        assertEquals("o", RecipeCodec.decode(futureMinor).outputNodeId)
    }

    @Test
    fun `newer schema version is rejected with an upgrade message`() {
        val doc = RecipeCodec.encode(sampleGraph()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")
        val e = assertFailsWith<RecipeFormatException> { RecipeCodec.decode(doc) }
        assertTrue("update" in e.message!!, "message should tell the user to update: ${e.message}")
    }

    @Test
    fun `garbage and structurally-wrong documents are rejected`() {
        assertFailsWith<RecipeFormatException> { RecipeCodec.decode("not json at all") }
        assertFailsWith<RecipeFormatException> { RecipeCodec.decode("""{"noVersion": true}""") }
        assertFailsWith<RecipeFormatException> {
            RecipeCodec.decode("""{"schemaVersion": 1, "graph": {"nodes": "nope"}}""")
        }
        assertFailsWith<RecipeFormatException> {
            RecipeCodec.decode(
                """{"schemaVersion": 1, "graph": {"nodes": [], "edges": [], "outputNodeId": "o", "sourceState": "BOGUS"}}"""
            )
        }
    }
}
