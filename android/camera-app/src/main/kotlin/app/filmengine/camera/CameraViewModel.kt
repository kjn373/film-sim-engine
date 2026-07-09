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
import app.filmengine.render.gles.ScopeData
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ScopeMode { OFF, HISTOGRAM, WAVEFORM }

/**
 * Calibration capture mode (B7). Each non-OFF mode tags the next shutter press
 * as one calibration frame kind; chart modes carry the illuminant CCT the
 * calibrator's dual-illuminant solve needs.
 */
enum class CalMode(val label: String, val kind: String?, val cct: Float) {
    OFF("CAL", null, 0f),
    DARK("DARK", "dark", 6504f),
    CHART_DAY("CHART☀", "chart", 6504f),
    CHART_TUNGSTEN("CHART💡", "chart", 2856f),
    FLAT("FLAT", "flat", 6504f),
}

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

    // ── viewfinder aids (B6) ────────────────────────────────────────────────
    private val _scopeMode = MutableStateFlow(ScopeMode.OFF)
    val scopeMode: StateFlow<ScopeMode> = _scopeMode

    private val _zebra = MutableStateFlow(false)
    val zebra: StateFlow<Boolean> = _zebra

    private val _peaking = MutableStateFlow(false)
    val peaking: StateFlow<Boolean> = _peaking

    /** Latest histogram/waveform readback from the GL thread. */
    val scopeData: StateFlow<ScopeData?> = pipeline.scopeData

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

    fun cycleScopeMode() {
        val next = ScopeMode.entries[(_scopeMode.value.ordinal + 1) % ScopeMode.entries.size]
        _scopeMode.value = next
        pipeline.setScopes(next != ScopeMode.OFF)
    }

    fun setZebra(enabled: Boolean) {
        _zebra.value = enabled
        pipeline.setZebra(enabled)
    }

    fun setPeaking(enabled: Boolean) {
        _peaking.value = enabled
        pipeline.setPeaking(enabled)
    }

    fun setRaw(enabled: Boolean) {
        val effective = enabled && _caps.value?.rawSupported == true
        controller.setRawEnabled(effective)
        _rawEnabled.value = effective
    }

    // ── calibration capture (B7) ────────────────────────────────────────────
    private val _calMode = MutableStateFlow(CalMode.OFF)
    val calMode: StateFlow<CalMode> = _calMode

    fun cycleCalMode() {
        _calMode.value = CalMode.entries[(_calMode.value.ordinal + 1) % CalMode.entries.size]
    }

    /**
     * One in-memory RAW frame → Bayer stats → appended to
     * `calibration_report.json` in the app's external files dir. Pull the report
     * with adb and run `tooling/profile-calibrator` on it to get the profile.
     */
    fun captureCalibration(onResult: (Boolean, String) -> Unit) {
        val mode = _calMode.value
        val kind = mode.kind ?: return
        val iso = _ui.value.iso.takeIf { it > 0 } ?: 400
        val shutterNs = _ui.value.shutterNs.takeIf { it > 0 } ?: 16_666_666L
        controller.captureCalibrationRaw { image, message ->
            if (image == null) return@captureCalibrationRaw onResult(false, message)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val result = runCatching {
                    val stats = try {
                        controller.calibrationStats(image, kind, iso, shutterNs, mode.cct)
                    } finally {
                        image.close()
                    }
                    appendToReport(stats)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    result.fold(
                        { n -> onResult(true, "${mode.label} frame #$n · ISO $iso") },
                        { e -> onResult(false, "Calibration failed: ${e.message}") },
                    )
                }
            }
        }
    }

    private fun appendToReport(frame: app.filmengine.profile.FrameStats): Int {
        val dir = appContext.getExternalFilesDir(null)
        val file = java.io.File(dir, "calibration_report.json")
        val report = if (file.exists()) {
            app.filmengine.profile.CalibrationReportCodec.decode(file.readText())
        } else {
            val harvested = controller.harvestProfile()
            // drop the §16 harvested fallback next to the report for comparison
            harvested?.let {
                java.io.File(dir, "deviceprofile_harvested.json")
                    .writeText(app.filmengine.profile.DeviceProfileCodec.encode(it))
            }
            app.filmengine.profile.CalibrationReport(
                model = android.os.Build.MODEL,
                sensorId = harvested?.sensorId ?: "0",
                whiteLevel = harvested?.sensor?.whiteLevel ?: 1023f,
                frames = emptyList(),
            )
        }
        val updated = report.copy(frames = report.frames + frame)
        file.writeText(app.filmengine.profile.CalibrationReportCodec.encode(updated))
        return updated.frames.size
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
