package app.filmengine.camera

import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.filmengine.camera.core.CameraCaps
import app.filmengine.camera.core.CameraController
import app.filmengine.camera.core.ExposureMode
import app.filmengine.camera.core.ExposureSettings
import app.filmengine.camera.core.ExposureSolver
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val controller: CameraController,
) : ViewModel() {

    private val _caps = MutableStateFlow<CameraCaps?>(null)
    val caps: StateFlow<CameraCaps?> = _caps

    private val _ui = MutableStateFlow(ExposureUi())
    val ui: StateFlow<ExposureUi> = _ui

    /** AE reference frozen at the moment the user leaves AUTO. */
    private var meteredRef: ExposureSettings? = null
    private var userIso = 400
    private var userShutterNs = 16_666_666L
    private var ecStops = 0f

    fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        viewModelScope.launch {
            _caps.value = controller.bind(owner, surfaceProvider)
        }
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

    fun takePhoto(onResult: (Boolean, String) -> Unit) = controller.takePhoto(onResult)

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
}
