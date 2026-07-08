package app.filmengine.camera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGraphTest {

    @Test
    fun `passthrough plan has exposure and srgb_output only`() {
        val plan = PreviewGraph.compile(null, QualityLevel.FULL)
        val types = plan.steps.map { it.type }
        assertEquals(listOf("exposure", "srgb_output"), types)
    }

    @Test
    fun `FULL quality includes film_sim + halation + grain`() {
        val plan = PreviewGraph.compile("chroma-100", QualityLevel.FULL)
        val types = plan.steps.map { it.type }
        assertTrue("film_sim missing", "film_sim" in types)
        assertTrue("halation missing", "halation" in types)
        assertTrue("grain missing", "grain" in types)
        assertTrue("srgb_output missing", "srgb_output" in types)
    }

    @Test
    fun `REDUCED_GRAIN halves grain amount`() {
        val plan = PreviewGraph.compile("negato-400", QualityLevel.REDUCED_GRAIN)
        val grain = plan.steps.find { it.type == "grain" }!!
        assertEquals(0.125f, grain.params.getValue("amount"), 1e-6f)
    }

    @Test
    fun `NO_GRAIN drops grain but keeps halation`() {
        val plan = PreviewGraph.compile("chroma-100", QualityLevel.NO_GRAIN)
        val types = plan.steps.map { it.type }
        assertTrue("halation should be present", "halation" in types)
        assertTrue("grain should be absent", "grain" !in types)
    }

    @Test
    fun `HALF_RES_SPATIAL drops all spatial nodes`() {
        val plan = PreviewGraph.compile("chroma-100", QualityLevel.HALF_RES_SPATIAL)
        val types = plan.steps.map { it.type }
        assertTrue("halation should be absent", "halation" !in types)
        assertTrue("grain should be absent", "grain" !in types)
        assertTrue("film_sim should remain", "film_sim" in types)
    }

    @Test
    fun `MINIMAL is lightest chain`() {
        val plan = PreviewGraph.compile("mono-400", QualityLevel.MINIMAL)
        val types = plan.steps.map { it.type }
        assertEquals(listOf("exposure", "film_sim", "srgb_output"), types)
    }

    @Test
    fun `film_sim step carries the stock option`() {
        val plan = PreviewGraph.compile("negato-800t", QualityLevel.FULL)
        val filmStep = plan.steps.find { it.type == "film_sim" }!!
        assertEquals("negato-800t", filmStep.options["stock"])
    }

    @Test
    fun `every stock compiles without error`() {
        val stockIds = listOf(
            "chroma-64", "chroma-100", "chroma-200n",
            "negato-160", "negato-400", "negato-800t",
            "mono-100", "mono-400", "mono-3200p",
        )
        for (id in stockIds) {
            for (q in QualityLevel.entries) {
                val plan = PreviewGraph.compile(id, q)
                assertTrue("Plan for $id@$q should have steps", plan.steps.isNotEmpty())
            }
        }
    }
}
