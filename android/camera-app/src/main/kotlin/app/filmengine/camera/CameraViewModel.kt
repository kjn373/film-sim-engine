package app.filmengine.camera

import android.content.Context
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.filmengine.camera.core.CameraCaps
import app.filmengine.camera.core.CameraController
import app.filmengine.camera.core.ExposureMode
import app.filmengine.camera.core.ExposureSettings
import app.filmengine.camera.core.ExposureSolver
import app.filmengine.camera.core.PreviewPipeline
import app.filmengine.camera.core.QualityLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExposureUi(
    val mode: ExposureMode = ExposureMode.AUTO,
    val iso: Int = 0,
    val shutterNs: Long = 0,
    val ecStops: Float = 0f,
    val offsetStops: Float = 0f,
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    val controller: CameraController,
    val pipeline: PreviewPipeline,
) : ViewModel() {

    private val _caps = MutableStateFlow<CameraCaps?>(null)
    val caps: StateFlow<CameraCaps?> = _caps

    private val _ui = MutableStateFlow(ExposureUi())
    val ui: StateFlow<ExposureUi> = _ui

    private val _selectedStock = MutableStateFlow<String?>("chroma-100")
    val selectedStock: StateFlow<String?> = _selectedStock

    private val _rawEnabled = MutableStateFlow(false)
    val rawEnabled: StateFlow<Boolean> = _rawEnabled

    /** Frame time from the pipeline (ms). */
    val frameTimeMs: StateFlow<Float> = pipeline.frameTimeMs

    /** Current quality level from the degradation ladder. */
    val qualityLevel: StateFlow<QualityLevel> = pipeline.qualityLevel

    /** AE reference frozen at the moment the user leaves AUTO. */
    private var meteredRef: ExposureSettings? = null
    private var userIso = 400
    private var userShutterNs = 16_666_666L
    private var ecStops = 0f

    /**
     * Bind CameraX to the lifecycle. The preview frames go through the
     * pipeline's SurfaceProvider (OES texture) instead of a PreviewView.
     */
    fun bind(owner: LifecycleOwner) {
        viewModelScope.launch {
            _caps.value = controller.bind(owner, pipeline.surfaceProvider())
        }
    }

    /** Called when the display SurfaceView is created / changed. */
    fun startPipeline(surface: Surface, width: Int, height: Int) {
        pipeline.start(surface, width, height)
        pipeline.setStock(_selectedStock.value)
    }

    fun stopPipeline() {
        pipeline.stop()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        pipeline.onSurfaceChanged(width, height)
    }

    fun setStock(stockId: String?) {
        _selectedStock.value = stockId
        pipeline.setStock(stockId)
    }

    fun setMode(mode: ExposureMode) {
        if (_ui.value.mode == ExposureMode.AUTO && mode != ExposureMode.AUTO) {
            // Freeze the AE reference: once AE is off, capture results echo our
            // own manual values and are no longer a meter.
            meteredRef = controller.metered.value
            controller.metered.value?.let {
                userIso = it.iso
                userShutterNs = it.shutterNs
            }
        }
        _ui.value = _ui.value.copy(mode = mode)
        recompute()
    }

    fun setUserIso(iso: Int) {
        userIso = iso
        recompute()
    }

    fun setUserShutter(ns: Long) {
        userShutterNs = ns
        recompute()
    }

    fun setEc(stops: Float) {
        ecStops = stops
        recompute()
    }

    fun setRaw(enabled: Boolean) {
        val effective = enabled && _caps.value?.rawSupported == true
        controller.setRawEnabled(effective)
        _rawEnabled.value = effective
    }

    /**
     * Capture → MediaStore immediately; then, if a stock is selected, hand the
     * saved JPEG to the full-quality render job ("None" needs no processing).
     */
    fun takePhoto(onResult: (Boolean, String) -> Unit) {
        val stockId = _selectedStock.value
        controller.takePhoto { success, message, jpegUri ->
            if (success && jpegUri != null && stockId != null) {
                RenderWorker.enqueue(appContext, jpegUri, stockId)
            }
            onResult(success, message)
        }
    }

    private fun recompute() {
        val mode = _ui.value.mode
        val caps = _caps.value ?: return
        if (mode == ExposureMode.AUTO) {
            controller.setManualExposure(null)
            val m = controller.metered.value
            _ui.value = ExposureUi(mode, m?.iso ?: 0, m?.shutterNs ?: 0, ecStops, 0f)
            return
        }
        val metered = meteredRef ?: ExposureSettings(400, 16_666_666)
        val solved = ExposureSolver(caps.exposure)
            .solve(mode, metered, userIso, userShutterNs, ecStops)
        controller.setManualExposure(solved.settings)
        _ui.value = ExposureUi(
            mode, solved.settings.iso, solved.settings.shutterNs, ecStops, solved.offsetStops,
        )
    }

    override fun onCleared() {
        pipeline.stop()
        super.onCleared()
    }
}
