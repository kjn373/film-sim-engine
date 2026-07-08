package app.filmengine.render.gles

import android.opengl.GLES31.*
import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.RenderBackend
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android GLES 3.1 backend — structural twin of the desktop [GlRenderBackend].
 *
 * Each plan step is one fullscreen fragment pass, ping-ponging between three
 * RGBA32F framebuffers (three targets so a step's input can survive for
 * composite passes that read `orig`).
 *
 * ## OES camera-texture input
 *
 * When [oesTextureId] is non-zero, the first action in [render] blits the
 * external OES texture into the ping-pong chain via the `oes_input` kernel.
 * This decouples the hardware-texture lifetime and format from the engine
 * pipeline — every subsequent step works on a normal `GL_TEXTURE_2D`.
 *
 * ## Texture precision
 *
 * Uses RGBA32F for correctness parity with the desktop backend. Falls back
 * to RGBA16F if the framebuffer-completeness check fails (GLES 3.1 without
 * `EXT_color_buffer_float`). Either format holds scene-linear HDR data.
 */
class GlesRenderBackend(
    private val kernels: Map<String, GlesKernel> = GlesKernels.all,
    /** Non-zero = use this OES texture as the source instead of the [ImageBuffer]. */
    private val oesTextureId: Int = 0,
    /** `SurfaceTexture.getTransformMatrix()` result — required when [oesTextureId] != 0. */
    private val oesTransform: FloatArray? = null,
) : RenderBackend {

    private val programs = HashMap<String, Int>()
    private var vao = 0
    private var oesProgram = 0

    /**
     * Chosen internal format — [GL_RGBA32F] on GLES 3.2+ / `EXT_color_buffer_float`,
     * [GL_RGBA16F] otherwise. Determined lazily on first render.
     */
    private var internalFormat: Int = 0

    override fun render(plan: ExecutionPlan, source: ImageBuffer): ImageBuffer {
        GlesContext.makeCurrent()
        if (vao == 0) {
            val ids = IntArray(1)
            glGenVertexArrays(1, ids, 0)
            vao = ids[0]
        }

        val w = if (oesTextureId != 0) source.width else source.width
        val h = if (oesTextureId != 0) source.height else source.height

        if (internalFormat == 0) internalFormat = probeInternalFormat(w, h)

        val texs = IntArray(3)
        glGenTextures(3, texs, 0)
        for (tex in texs) initTexture(tex, w, h)

        val fbos = IntArray(3)
        glGenFramebuffers(3, fbos, 0)
        try {
            for (i in texs.indices) {
                glBindFramebuffer(GL_FRAMEBUFFER, fbos[i])
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texs[i], 0)
                check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                    "FBO incomplete for ${if (internalFormat == GL_RGBA32F) "RGBA32F" else "RGBA16F"}"
                }
            }

            // ── source ingestion ────────────────────────────────────────────
            if (oesTextureId != 0) {
                // Blit OES camera texture → texs[0] via the OES input shader
                val transform = oesTransform
                    ?: error("oesTransform required when oesTextureId is set")
                if (oesProgram == 0) {
                    oesProgram = buildProgram(GlesKernels.OES_INPUT_FRAGMENT)
                }
                glBindFramebuffer(GL_FRAMEBUFFER, fbos[0])
                glViewport(0, 0, w, h)
                glBindVertexArray(vao)
                glUseProgram(oesProgram)
                GlesKernels.oesInputBind(oesProgram, oesTextureId, transform)
                glDrawArrays(GL_TRIANGLES, 0, 3)
            } else {
                upload(texs[0], source)
            }

            // ── pipeline execution ──────────────────────────────────────────
            // Texel row 0 = image row 0 on upload AND readback, and
            // gl_FragCoord.y indexes the same rows — CPU and GPU agree on
            // orientation with no flip. (Display flip is the Android surface's
            // problem, not the engine's.)
            glViewport(0, 0, w, h)
            glBindVertexArray(vao)
            var cur = 0
            for (step in plan.steps) {
                val kernel = kernels[step.type]
                    ?: error("No GLES kernel registered for node type '${step.type}'")
                val stepInput = cur
                var passInput = cur
                for ((pi, pass) in kernel.passes.withIndex()) {
                    val program = programs.getOrPut("${step.type}#$pi") { buildProgram(pass.fragmentSrc) }
                    val dst = (0..2).first { it != stepInput && it != passInput }
                    glBindFramebuffer(GL_FRAMEBUFFER, fbos[dst])
                    glUseProgram(program)

                    glActiveTexture(GL_TEXTURE0)
                    glBindTexture(GL_TEXTURE_2D, texs[passInput])
                    glUniform1i(glGetUniformLocation(program, "src"), 0)

                    val origLoc = glGetUniformLocation(program, "orig")
                    if (origLoc >= 0) {
                        glActiveTexture(GL_TEXTURE1)
                        glBindTexture(GL_TEXTURE_2D, texs[stepInput])
                        glUniform1i(origLoc, 1)
                        glActiveTexture(GL_TEXTURE0)
                    }
                    val texelLoc = glGetUniformLocation(program, "texelSize")
                    if (texelLoc >= 0) glUniform2f(texelLoc, 1f / w, 1f / h)

                    pass.bind(program, step)
                    glDrawArrays(GL_TRIANGLES, 0, 3)
                    passInput = dst
                }
                cur = passInput
            }

            return readback(fbos[cur], w, h)
        } finally {
            glDeleteTextures(3, texs, 0)
            glDeleteFramebuffers(3, fbos, 0)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Try RGBA32F; if the FBO comes back incomplete fall back to RGBA16F.
     * The probe texture is deleted after the check.
     */
    private fun probeInternalFormat(w: Int, h: Int): Int {
        val tex = IntArray(1)
        glGenTextures(1, tex, 0)
        glBindTexture(GL_TEXTURE_2D, tex[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, 4, 0, GL_RGBA, GL_FLOAT, null)

        val fbo = IntArray(1)
        glGenFramebuffers(1, fbo, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0)
        val ok32 = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE

        glDeleteFramebuffers(1, fbo, 0)
        glDeleteTextures(1, tex, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        return if (ok32) GL_RGBA32F else GL_RGBA16F
    }

    private fun initTexture(tex: Int, w: Int, h: Int) {
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, GL_RGBA, GL_FLOAT, null)
    }

    private fun upload(tex: Int, source: ImageBuffer) {
        val buf = GlesKernels.floatBuffer(source.data)
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, source.width, source.height, GL_RGBA, GL_FLOAT, buf)
    }

    private fun readback(fbo: Int, w: Int, h: Int): ImageBuffer {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        val buf = ByteBuffer.allocateDirect(w * h * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, buf)
        val out = ImageBuffer.alloc(w, h)
        buf.get(out.data)
        return out
    }

    private fun buildProgram(fragmentSrc: String): Int {
        val vs = compile(GL_VERTEX_SHADER, GlesKernels.VERTEX)
        val fs = compile(GL_FRAGMENT_SHADER, fragmentSrc)
        val program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        glLinkProgram(program)
        glDeleteShader(vs)
        glDeleteShader(fs)
        val linked = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
        check(linked[0] == GL_TRUE) {
            val log = glGetProgramInfoLog(program)
            "Shader link failed: $log"
        }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, src)
        glCompileShader(shader)
        val compiled = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GL_TRUE) {
            val log = glGetShaderInfoLog(shader)
            "Shader compile failed: $log\n--- source ---\n$src"
        }
        return shader
    }

    // ── constants ────────────────────────────────────────────────────────────

    companion object {
        // GLES 3.0 has GL_RGBA16F but the constant is in GLES30 — replicate here
        // to avoid importing two GLES static-import sets.
        private const val GL_RGBA32F = 0x8814
        private const val GL_RGBA16F = 0x881A
        private const val GL_TEXTURE_3D = 0x806F
    }
}
