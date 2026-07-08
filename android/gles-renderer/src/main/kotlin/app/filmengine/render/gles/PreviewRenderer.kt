package app.filmengine.render.gles

import android.opengl.GLES31.*
import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.Step

/**
 * Zero-allocation steady-state renderer for the camera preview pipeline.
 *
 * All GPU resources (ping-pong textures, FBOs, shader programs) are allocated
 * once in [resize] and reused every frame. [renderFrame] never calls `glGen*`.
 *
 * The final result is blitted to **FBO 0** (the EGL window surface) via a
 * fullscreen textured quad — no `glReadPixels`. The caller swaps buffers.
 *
 * Threading: every method must be called on the GL thread that owns the current
 * EGL context. This is enforced by the [PreviewPipeline] coordinator.
 */
class PreviewRenderer(
    private val kernels: Map<String, GlesKernel> = GlesKernels.all,
) {
    private var textures: IntArray? = null
    private var fbos: IntArray? = null
    private var width = 0
    private var height = 0

    private var vao = 0
    private var oesProgram = 0
    private var blitProgram = 0
    private val programs = HashMap<String, Int>()

    private var internalFormat = 0

    // ── lifecycle ────────────────────────────────────────────────────────────

    /**
     * (Re)allocate ping-pong resources for the given resolution.
     * Called once on start and again on surface-size changes — never per-frame.
     */
    fun resize(w: Int, h: Int) {
        if (w == width && h == height && textures != null) return
        release()

        width = w
        height = h

        if (vao == 0) {
            val ids = IntArray(1)
            glGenVertexArrays(1, ids, 0)
            vao = ids[0]
        }

        if (internalFormat == 0) internalFormat = probeInternalFormat()

        val t = IntArray(3)
        glGenTextures(3, t, 0)
        for (tex in t) initTexture(tex)
        textures = t

        val f = IntArray(3)
        glGenFramebuffers(3, f, 0)
        for (i in f.indices) {
            glBindFramebuffer(GL_FRAMEBUFFER, f[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t[i], 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                "Preview FBO incomplete"
            }
        }
        fbos = f
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /**
     * Render one frame of the engine pipeline and present it to the window surface
     * (FBO 0). The caller must have made the window EGL surface current before
     * calling this, and must call `eglSwapBuffers` after.
     */
    fun renderFrame(
        plan: ExecutionPlan,
        oesTextureId: Int,
        oesTransform: FloatArray,
    ) {
        val t = textures ?: return
        val f = fbos ?: return

        glViewport(0, 0, width, height)
        glBindVertexArray(vao)

        // ── OES input → tex[0] ──────────────────────────────────────────
        if (oesProgram == 0) oesProgram = buildProgram(GlesKernels.OES_INPUT_FRAGMENT)
        glBindFramebuffer(GL_FRAMEBUFFER, f[0])
        glUseProgram(oesProgram)
        GlesKernels.oesInputBind(oesProgram, oesTextureId, oesTransform)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        // ── pipeline steps ──────────────────────────────────────────────
        var cur = 0
        for (step in plan.steps) {
            val kernel = kernels[step.type]
                ?: error("No GLES kernel for node type '${step.type}'")
            val stepInput = cur
            var passInput = cur
            for ((pi, pass) in kernel.passes.withIndex()) {
                val program = programs.getOrPut("${step.type}#$pi") {
                    buildProgram(pass.fragmentSrc)
                }
                val dst = (0..2).first { it != stepInput && it != passInput }
                glBindFramebuffer(GL_FRAMEBUFFER, f[dst])
                glUseProgram(program)

                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D, t[passInput])
                glUniform1i(glGetUniformLocation(program, "src"), 0)

                val origLoc = glGetUniformLocation(program, "orig")
                if (origLoc >= 0) {
                    glActiveTexture(GL_TEXTURE1)
                    glBindTexture(GL_TEXTURE_2D, t[stepInput])
                    glUniform1i(origLoc, 1)
                    glActiveTexture(GL_TEXTURE0)
                }
                val texelLoc = glGetUniformLocation(program, "texelSize")
                if (texelLoc >= 0) glUniform2f(texelLoc, 1f / width, 1f / height)

                pass.bind(program, step)
                glDrawArrays(GL_TRIANGLES, 0, 3)
                passInput = dst
            }
            cur = passInput
        }

        // ── blit final result → FBO 0 (window surface) ─────────────────
        if (blitProgram == 0) blitProgram = buildProgram(BLIT_FRAGMENT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glUseProgram(blitProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, t[cur])
        glUniform1i(glGetUniformLocation(blitProgram, "src"), 0)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Release all GPU resources. Safe to call multiple times. */
    fun release() {
        textures?.let { glDeleteTextures(3, it, 0) }
        textures = null
        fbos?.let { glDeleteFramebuffers(3, it, 0) }
        fbos = null
        width = 0
        height = 0
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun probeInternalFormat(): Int {
        val tex = IntArray(1)
        glGenTextures(1, tex, 0)
        glBindTexture(GL_TEXTURE_2D, tex[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, 4, 0, GL_RGBA, GL_FLOAT, null)
        val fbo = IntArray(1)
        glGenFramebuffers(1, fbo, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0)
        val ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
        glDeleteFramebuffers(1, fbo, 0)
        glDeleteTextures(1, tex, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return if (ok) GL_RGBA32F else GL_RGBA16F
    }

    private fun initTexture(tex: Int) {
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GL_RGBA, GL_FLOAT, null)
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
        check(linked[0] == GL_TRUE) { "Shader link failed: ${glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, src)
        glCompileShader(shader)
        val compiled = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GL_TRUE) {
            "Shader compile failed: ${glGetShaderInfoLog(shader)}\n--- source ---\n$src"
        }
        return shader
    }

    companion object {
        private const val GL_RGBA32F = 0x8814
        private const val GL_RGBA16F = 0x881A

        /** Simple blit shader — samples src and writes directly. */
        private val BLIT_FRAGMENT = """
            #version 300 es
            precision highp float;
            in vec2 uv;
            out vec4 fragColor;
            uniform sampler2D src;
            void main() {
                fragColor = texture(src, uv);
            }
        """.trimIndent()
    }
}
