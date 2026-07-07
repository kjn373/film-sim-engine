package app.filmengine.render.gl

import app.filmengine.engine.buffer.ImageBuffer
import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.engine.exec.RenderBackend
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33.*

/**
 * Desktop OpenGL backend: each plan step is one fullscreen fragment pass,
 * ping-ponging between two RGBA32F framebuffers. Correctness twin of the
 * future Android GLES backend — same shader sources, same pass structure.
 */
// ponytail: textures/FBOs are created per render() call, not pooled — pooling
// (ARCHITECTURE.md §5.3) matters for live preview, not for parity testing.
class GlRenderBackend(
    private val kernels: Map<String, GlKernel> = GlKernels.all,
) : RenderBackend {

    private val programs = HashMap<String, Int>()
    private var vao = 0

    override fun render(plan: ExecutionPlan, source: ImageBuffer): ImageBuffer {
        GlContext.makeCurrent()
        if (vao == 0) vao = glGenVertexArrays()

        val w = source.width
        val h = source.height
        val texs = IntArray(2) { makeTexture(w, h) }
        val fbos = IntArray(2) { glGenFramebuffers() }
        try {
            for (i in 0..1) {
                glBindFramebuffer(GL_FRAMEBUFFER, fbos[i])
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texs[i], 0)
                check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                    "RGBA32F framebuffer incomplete"
                }
            }

            // ponytail: no y-flip anywhere — all current passes are pointwise, so
            // orientation cancels between upload and readback. Revisit with the
            // first spatial node (bloom/blur).
            upload(texs[0], source)

            glViewport(0, 0, w, h)
            glBindVertexArray(vao)
            var cur = 0
            for (step in plan.steps) {
                val kernel = kernels[step.type]
                    ?: error("No GL kernel registered for node type '${step.type}'")
                val program = programs.getOrPut(step.type) { buildProgram(kernel.fragmentSrc) }
                val dst = 1 - cur
                glBindFramebuffer(GL_FRAMEBUFFER, fbos[dst])
                glUseProgram(program)
                glActiveTexture(GL_TEXTURE0)
                glBindTexture(GL_TEXTURE_2D, texs[cur])
                glUniform1i(glGetUniformLocation(program, "src"), 0)
                kernel.bind(program, step)
                glDrawArrays(GL_TRIANGLES, 0, 3)
                cur = dst
            }

            return readback(fbos[cur], w, h)
        } finally {
            glDeleteTextures(texs)
            glDeleteFramebuffers(fbos)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        }
    }

    private fun makeTexture(w: Int, h: Int): Int {
        val tex = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, w, h, 0, GL_RGBA, GL_FLOAT, 0L)
        return tex
    }

    private fun upload(tex: Int, source: ImageBuffer) {
        val buf = BufferUtils.createFloatBuffer(source.data.size)
        buf.put(source.data).flip()
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, source.width, source.height, GL_RGBA, GL_FLOAT, buf)
    }

    private fun readback(fbo: Int, w: Int, h: Int): ImageBuffer {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        val buf = BufferUtils.createFloatBuffer(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, buf)
        val out = ImageBuffer.alloc(w, h)
        buf.get(out.data)
        return out
    }

    private fun buildProgram(fragmentSrc: String): Int {
        val vs = compile(GL_VERTEX_SHADER, GlKernels.VERTEX)
        val fs = compile(GL_FRAGMENT_SHADER, fragmentSrc)
        val program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        glLinkProgram(program)
        glDeleteShader(vs)
        glDeleteShader(fs)
        check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) {
            "Shader link failed: ${glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, src)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE) {
            "Shader compile failed: ${glGetShaderInfoLog(shader)}\n--- source ---\n$src"
        }
        return shader
    }
}
