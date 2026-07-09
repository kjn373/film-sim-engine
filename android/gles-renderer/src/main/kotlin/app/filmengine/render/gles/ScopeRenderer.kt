package app.filmengine.render.gles

import android.opengl.GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
import android.opengl.GLES31.GL_COMPILE_STATUS
import android.opengl.GLES31.GL_COMPUTE_SHADER
import android.opengl.GLES31.GL_DYNAMIC_READ
import android.opengl.GLES31.GL_LINK_STATUS
import android.opengl.GLES31.GL_MAP_READ_BIT
import android.opengl.GLES31.GL_SHADER_STORAGE_BARRIER_BIT
import android.opengl.GLES31.GL_SHADER_STORAGE_BUFFER
import android.opengl.GLES31.GL_TEXTURE0
import android.opengl.GLES31.GL_TEXTURE_2D
import android.opengl.GLES31.GL_TRUE
import android.opengl.GLES31.glActiveTexture
import android.opengl.GLES31.glAttachShader
import android.opengl.GLES31.glBindBuffer
import android.opengl.GLES31.glBindBufferBase
import android.opengl.GLES31.glBindTexture
import android.opengl.GLES31.glBufferData
import android.opengl.GLES31.glBufferSubData
import android.opengl.GLES31.glCompileShader
import android.opengl.GLES31.glCreateProgram
import android.opengl.GLES31.glCreateShader
import android.opengl.GLES31.glDeleteBuffers
import android.opengl.GLES31.glDeleteProgram
import android.opengl.GLES31.glDeleteShader
import android.opengl.GLES31.glDispatchCompute
import android.opengl.GLES31.glGenBuffers
import android.opengl.GLES31.glGetProgramInfoLog
import android.opengl.GLES31.glGetProgramiv
import android.opengl.GLES31.glGetShaderInfoLog
import android.opengl.GLES31.glGetShaderiv
import android.opengl.GLES31.glGetUniformLocation
import android.opengl.GLES31.glLinkProgram
import android.opengl.GLES31.glMapBufferRange
import android.opengl.GLES31.glMemoryBarrier
import android.opengl.GLES31.glShaderSource
import android.opengl.GLES31.glUniform1i
import android.opengl.GLES31.glUnmapBuffer
import android.opengl.GLES31.glUseProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One readback of the scope compute pass (B6). All arrays are raw bin counts.
 *
 * Histograms are 256 bins over the display-referred [0,1] signal. The waveform
 * is a [WAVE_W]×[WAVE_H] grid, row-major: column = image x position, row 0 =
 * signal level 0 (black). Overlay drawing flips rows so white is at the top.
 */
class ScopeData(
    val luma: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val waveform: IntArray,
) {
    companion object {
        const val BINS = 256
        const val WAVE_W = 128
        const val WAVE_H = 64
    }
}

/**
 * Histogram + waveform via a GLES 3.1 compute shader (ARCHITECTURE.md §11):
 * atomic-add bins into one SSBO, sampling every other pixel in each dimension
 * (¼ of the pixels). Runs on the GL thread against the final preview texture.
 *
 * Requires GLES 3.1 compute; on an ES 3.0-only context the program build fails
 * once and [collect] returns null forever after (scopes silently unavailable).
 */
class ScopeRenderer {

    private var program = 0
    private var ssbo = 0
    private var unavailable = false

    private val zeros: ByteBuffer =
        ByteBuffer.allocateDirect(TOTAL_BYTES).order(ByteOrder.nativeOrder())
    private val ints = IntArray(TOTAL_INTS)

    /**
     * Bin the given texture and read the result back. Synchronous — the map
     * stalls until the dispatch completes. Call at the [ScopeGate]-reduced
     * rate, never every frame.
     */
    // ponytail: sync readback (~stall per collect); double-buffered SSBO +
    // fence if it ever shows in traces at the 4-frame cadence.
    fun collect(texture: Int, width: Int, height: Int): ScopeData? {
        if (unavailable) return null
        if (texture == 0 || width == 0 || height == 0) return null
        if (program == 0) {
            try {
                init()
            } catch (_: RuntimeException) {
                unavailable = true
                return null
            }
        }

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        zeros.position(0)
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, TOTAL_BYTES, zeros)

        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture)
        glUniform1i(glGetUniformLocation(program, "img"), 0)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo)

        // stride-2 sampling → half resolution in each dimension
        val gx = (width / 2 + GROUP - 1) / GROUP
        val gy = (height / 2 + GROUP - 1) / GROUP
        glDispatchCompute(gx, gy, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)

        val mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, TOTAL_BYTES, GL_MAP_READ_BIT)
            as? ByteBuffer ?: return null
        mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(ints)
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

        val b = ScopeData.BINS
        return ScopeData(
            luma = ints.copyOfRange(0, b),
            red = ints.copyOfRange(b, 2 * b),
            green = ints.copyOfRange(2 * b, 3 * b),
            blue = ints.copyOfRange(3 * b, 4 * b),
            waveform = ints.copyOfRange(4 * b, TOTAL_INTS),
        )
    }

    /** Release GPU resources. Safe to call multiple times. */
    fun release() {
        if (program != 0) glDeleteProgram(program)
        program = 0
        if (ssbo != 0) glDeleteBuffers(1, intArrayOf(ssbo), 0)
        ssbo = 0
    }

    private fun init() {
        val shader = glCreateShader(GL_COMPUTE_SHADER)
        glShaderSource(shader, COMPUTE_SRC)
        glCompileShader(shader)
        val compiled = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GL_TRUE) {
            "Scope compute compile failed: ${glGetShaderInfoLog(shader)}"
        }
        val p = glCreateProgram()
        glAttachShader(p, shader)
        glLinkProgram(p)
        glDeleteShader(shader)
        val linked = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, linked, 0)
        check(linked[0] == GL_TRUE) { "Scope compute link failed: ${glGetProgramInfoLog(p)}" }
        program = p

        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        ssbo = ids[0]
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        zeros.position(0)
        glBufferData(GL_SHADER_STORAGE_BUFFER, TOTAL_BYTES, zeros, GL_DYNAMIC_READ)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
    }

    companion object {
        private const val GROUP = 16
        private const val TOTAL_INTS =
            4 * ScopeData.BINS + ScopeData.WAVE_W * ScopeData.WAVE_H
        private const val TOTAL_BYTES = TOTAL_INTS * 4

        /**
         * SSBO layout: [0,256) luma · [256,512) R · [512,768) G · [768,1024) B ·
         * then WAVE_H rows × WAVE_W columns of waveform counts.
         *
         * Luma weights are Rec.709/sRGB — the input is the *display-referred*
         * final texture (post `srgb_output`), not the Rec.2020 working space.
         */
        private val COMPUTE_SRC = """
            #version 310 es
            precision highp float;
            precision highp int;
            layout(local_size_x = $GROUP, local_size_y = $GROUP) in;
            uniform highp sampler2D img;
            layout(std430, binding = 0) buffer ScopeBuf { uint bins[]; };

            void main() {
                ivec2 size = textureSize(img, 0);
                ivec2 p = ivec2(gl_GlobalInvocationID.xy) * 2;
                if (p.x >= size.x || p.y >= size.y) return;
                vec3 c = clamp(texelFetch(img, p, 0).rgb, 0.0, 1.0);
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                atomicAdd(bins[uint(luma * 255.0 + 0.5)], 1u);
                atomicAdd(bins[256u + uint(c.r * 255.0 + 0.5)], 1u);
                atomicAdd(bins[512u + uint(c.g * 255.0 + 0.5)], 1u);
                atomicAdd(bins[768u + uint(c.b * 255.0 + 0.5)], 1u);
                uint col = min(uint(p.x) * ${ScopeData.WAVE_W}u / uint(size.x), ${ScopeData.WAVE_W - 1}u);
                uint row = uint(luma * ${ScopeData.WAVE_H - 1}.0 + 0.5);
                atomicAdd(bins[1024u + row * ${ScopeData.WAVE_W}u + col], 1u);
            }
        """.trimIndent()
    }
}
