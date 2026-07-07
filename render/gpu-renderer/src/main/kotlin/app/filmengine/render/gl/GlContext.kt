package app.filmengine.render.gl

import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL

/**
 * Hidden-window GL 3.3 core context — the desktop twin of the Android GLES backend.
 * Exists so GPU kernels can be parity-tested against the CPU reference off-device.
 */
internal object GlContext {
    private var window = 0L

    val available: Boolean by lazy {
        try {
            glfwInit() && run {
                glfwDefaultWindowHints()
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
                window = glfwCreateWindow(16, 16, "filmengine-gl", 0L, 0L)
                window != 0L
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun makeCurrent() {
        check(available) { "No OpenGL 3.3 context available on this machine" }
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
    }
}
