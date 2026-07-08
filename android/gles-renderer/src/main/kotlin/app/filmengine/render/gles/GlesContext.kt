package app.filmengine.render.gles

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES31
import android.view.Surface

/**
 * Holds an EGL 1.4 context targeting GLES 3.1 with a 1×1 pbuffer surface.
 * Used when the caller does not bring their own surface (e.g., offline render).
 *
 * For live-preview (B4), the caller will create a window surface tied to a
 * SurfaceTexture and pass it to [makeCurrentTo].
 */
object GlesContext {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    val available: Boolean by lazy {
        try {
            init()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun makeCurrent() {
        check(available) { "No GLES 3.1 context available" }
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    fun makeCurrentTo(surface: EGLSurface) {
        check(available) { "No GLES 3.1 context available" }
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    /** Create a window-backed EGL surface for live preview rendering. */
    fun createWindowSurface(surface: Surface): EGLSurface {
        check(available)
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, chooseConfig(display), surface, attribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return eglSurface
    }

    fun swapBuffers(surface: EGLSurface) {
        EGL14.eglSwapBuffers(display, surface)
    }

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    /** Exposes the display for callers that need to create their own window surfaces. */
    fun display(): EGLDisplay {
        check(available)
        return display
    }

    /** Exposes the config for callers creating compatible surfaces. */
    fun config(): EGLConfig {
        check(available)
        return chooseConfig(display)
    }

    private fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val major = IntArray(1)
        val minor = IntArray(1)
        check(EGL14.eglInitialize(display, major, 0, minor, 0)) {
            "eglInitialize failed"
        }

        val config = chooseConfig(display)

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE,
        )
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        check(numConfigs[0] > 0) { "No EGL config matching GLES 3.1 + RGBA8" }
        return configs[0]!!
    }
}
