package app.filmengine.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditStackTest {

    private val stack = listOf(
        EditStack.defaultLayer("exposure").copy(params = mapOf("stops" to 0.5f)),
        EditStack.defaultLayer("film_sim").copy(options = mapOf("stock" to "negato-400")),
        EditStack.defaultLayer("grain").copy(enabled = false),
    )

    @Test
    fun `layers round-trip through the graph unchanged`() {
        assertEquals(stack, EditStack.fromGraph(EditStack.toGraph(stack)))
    }

    @Test
    fun `graph is a chain terminated by srgb_output`() {
        val g = EditStack.toGraph(stack)
        assertEquals("srgb_output", g.nodes.last().type)
        assertEquals(g.nodes.size - 1, g.edges.size)
        assertEquals("out", g.outputNodeId)
    }

    @Test
    fun `empty stack compiles to output-only plan`() {
        val plan = EditStack.compile(emptyList())
        assertEquals(listOf("srgb_output"), plan.steps.map { it.type })
    }

    @Test
    fun `disabled layer survives round-trip but is skipped when compiled`() {
        val roundTripped = EditStack.fromGraph(EditStack.toGraph(stack))
        assertTrue(roundTripped.any { it.type == "grain" && !it.enabled })
        val plan = EditStack.compile(stack)
        assertTrue("grain" !in plan.steps.map { it.type })
    }

    @Test
    fun `every palette type produces a compilable default layer`() {
        for (type in EditStack.palette) {
            val plan = EditStack.compile(listOf(EditStack.defaultLayer(type)))
            assertTrue(plan.steps.isNotEmpty())
        }
    }
}
