package app.filmengine.camera.core

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.Preview
import app.filmengine.engine.exec.ExecutionPlan
import app.filmengine.render.gles.GlesContext
import app.filmengine.render.gles.PreviewRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates the live film-simulation preview:
 *
 *  1. A dedicated GL [HandlerThread] owns the EGL context.
 *  2. CameraX's Preview use-case feeds frames into a [SurfaceTexture] (OES
 *     texture) created on the GL thread.
 *  3. On every frame-available callback the pipeline runs the engine graph
 *     via [PreviewRenderer] and presents the result through the EGL window
 *     surface backed by the display [Surface] from the app's SurfaceView.
 *  4. [PerfMonitor] tracks frame timing and triggers quality-level changes
 *     which recompile the [ExecutionPlan] with fewer spatial nodes.
 *
 * Threading contract: [start], [stop], [setStock], [onSurfaceChanged] may be
 * called from any thread — they post to the GL handler.  [frameTimeMs] and
 * [qualityLevel] are observable from the UI thread.
 */
class PreviewPipeline {

    // ── observable state (UI thread reads) ───────────────────────────────────
    private val _frameTimeMs = MutableStateFlow(0f)
    val frameTimeMs: StateFlow<Float> = _frameTimeMs

    private val _qualityLevel = MutableStateFlow(QualityLevel.FULL)
    val qualityLevel: StateFlow<QualityLevel> = _qualityLevel

    // ── GL-thread-only state ────────────────────────────────────────────────
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var eglSurface: EGLSurface? = null

    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraOesTexId = 0
    private val oesTransform = FloatArray(16)

    private var renderer: PreviewRenderer? = null
    private val perfMonitor = PerfMonitor()

    private var currentStockId: String? = null
    private var currentPlan: ExecutionPlan? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Start the preview pipeline.
     *
     * @param displaySurface  The [Surface] from the app's SurfaceView — the
     *                        pipeline renders into this.
     * @param width           Display surface width in pixels.
     * @param height          Display surface height in pixels.
     */
    fun start(displaySurface: Surface, width: Int, height: Int) {
        val thread = HandlerThread("FilmEngine-GL").also { it.start() }
        val handler = Handler(thread.looper)
        glThread = thread
        glHandler = handler
        surfaceWidth = width
        surfaceHeight = height

        handler.post {
            // ── EGL / GLES setup ────────────────────────────────────────
            GlesContext.makeCurrent()   // ensure context initialised

            eglSurface = GlesContext.createWindowSurface(displaySurface)
            GlesContext.makeCurrentTo(eglSurface!!)

            renderer = PreviewRenderer().also { it.resize(width, height) }

            // ── camera OES texture ──────────────────────────────────────
            val texIds = IntArray(1)
            GLES31.glGenTextures(1, texIds, 0)
            cameraOesTexId = texIds[0]

            val st = SurfaceTexture(cameraOesTexId)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ onFrame() }, handler)
            cameraSurfaceTexture = st

            // Compile initial plan (passthrough until a stock is selected)
            recompile()
        }
    }

    /**
     * Returns a [Preview.SurfaceProvider] for CameraX. The camera preview
     * frames are directed to the pipeline's OES [SurfaceTexture].
     *
     * Must be called after [start] has posted its init (the surface texture
     * is created asynchronously). The provider will wait for readiness.
     */
    fun surfaceProvider(): Preview.SurfaceProvider =
        Preview.SurfaceProvider { request ->
            glHandler?.post {
                val st = cameraSurfaceTexture ?: return@post
                val resolution = request.resolution
                st.setDefaultBufferSize(resolution.width, resolution.height)
                val surface = Surface(st)
                request.provideSurface(
                    surface,
                    { it.run() },  // executor: run inline on GL thread
                    { surface.release() },
                )
            }
        }

    /** Update the display surface size (e.g. on configuration change). */
    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        glHandler?.post {
            renderer?.resize(width, height)
        }
    }

    /**
     * Change the active film stock. Pass `null` for passthrough (no film sim).
     * Recompiles the plan on the GL thread — takes effect on the next frame.
     */
    fun setStock(stockId: String?) {
        glHandler?.post {
            if (stockId == currentStockId) return@post
            currentStockId = stockId
            perfMonitor.reset()
            recompile()
        }
    }

    /** Tear down the pipeline and release all resources. */
    fun stop() {
        glHandler?.post {
            cameraSurfaceTexture?.release()
            cameraSurfaceTexture = null

            if (cameraOesTexId != 0) {
                GLES31.glDeleteTextures(1, intArrayOf(cameraOesTexId), 0)
                cameraOesTexId = 0
            }

            renderer?.release()
            renderer = null

            eglSurface?.let {
                GlesContext.makeCurrent()   // switch to pbuffer before destroying window surface
                GlesContext.destroySurface(it)
            }
            eglSurface = null

            currentPlan = null
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    // ── frame loop (GL thread) ──────────────────────────────────────────────

    private fun onFrame() {
        val st = cameraSurfaceTexture ?: return
        val surface = eglSurface ?: return
        val plan = currentPlan ?: return
        val r = renderer ?: return

        val t0 = System.nanoTime()

        st.updateTexImage()
        st.getTransformMatrix(oesTransform)

        GlesContext.makeCurrentTo(surface)
        r.renderFrame(plan, cameraOesTexId, oesTransform)
        GlesContext.swapBuffers(surface)

        val frameMs = (System.nanoTime() - t0) / 1_000_000f
        _frameTimeMs.value = frameMs

        if (perfMonitor.recordFrame(frameMs)) {
            // Quality level changed — recompile
            _qualityLevel.value = perfMonitor.currentLevel
            recompile()
        }
    }

    /** (Re)compile the execution plan at the current stock + quality level. */
    private fun recompile() {
        currentPlan = PreviewGraph.compile(currentStockId, perfMonitor.currentLevel)
        _qualityLevel.value = perfMonitor.currentLevel
    }
}
