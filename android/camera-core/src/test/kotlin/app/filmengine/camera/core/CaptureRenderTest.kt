package app.filmengine.camera.core

import app.filmengine.engine.exec.TiledRenderer
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRenderTest {

    @Test
    fun `decode maps sRGB extremes to linear extremes`() {
        val buf = CaptureRender.decode(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 2, 1)
        // White: sRGB 1.0 → linear 1.0, grey-invariant through Rec.2020
        for (c in 0..2) assertEquals(1f, buf.data[c], 1e-4f)
        // Black: exactly zero
        for (c in 0..2) assertEquals(0f, buf.data[4 + c], 0f)
        assertEquals(1f, buf.data[3], 0f) // opaque alpha
    }

    @Test
    fun `decode keeps grey neutral through the Rec2020 transform`() {
        val buf = CaptureRender.decode(intArrayOf(0xFF808080.toInt()), 1, 1)
        assertEquals(buf.data[0], buf.data[1], 1e-6f)
        assertEquals(buf.data[1], buf.data[2], 1e-6f)
    }

    @Test
    fun `encode quantizes with rounding and clamps out-of-range`() {
        val buf = app.filmengine.engine.buffer.ImageBuffer.alloc(2, 1)
        // exactly 0.5 in each channel; second pixel out of range both ways
        floatArrayOf(0.5f, 0.5f, 0.5f, 1f, 1.5f, -0.5f, 1f, 1f).copyInto(buf.data)
        val out = CaptureRender.encode(buf)
        assertEquals(0xFF808080.toInt(), out[0])
        assertEquals(0xFFFF00FF.toInt(), out[1])
    }

    @Test
    fun `decode rejects mismatched pixel count`() {
        try {
            CaptureRender.decode(IntArray(3), 2, 2)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    /** The RenderWorker path end-to-end on the JVM: decode → FULL plan → encode. */
    @Test
    fun `full-quality plan renders a decoded image through the CPU backend`() {
        val w = 64
        val h = 48
        val argb = IntArray(w * h) { p ->
            val v = (p * 255 / (w * h - 1))
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val backend = CpuBackend(BuiltinCpuKernels.all + FilmCpuKernels.all)
        val rendered = TiledRenderer(backend)
            .render(CaptureRender.plan("chroma-100"), CaptureRender.decode(argb, w, h))
        val out = CaptureRender.encode(rendered)
        assertEquals(w * h, out.size)
        // Output is opaque and not degenerate (film curve keeps a full ramp non-constant)
        assertTrue(out.all { it ushr 24 == 0xFF })
        assertTrue(out.distinct().size > 16)
    }
}
