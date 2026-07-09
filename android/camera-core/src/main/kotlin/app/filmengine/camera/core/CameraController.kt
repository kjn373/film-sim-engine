package app.filmengine.camera.core

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume

data class CameraCaps(
    val exposure: ExposureCaps,
    /** 0 when the lens can't focus manually; otherwise the closest focus in diopters. */
    val minFocusDiopters: Float,
    /** Device supports simultaneous DNG + JPEG capture. */
    val rawSupported: Boolean = false,
)

/**
 * Owns the CameraX session and every manual control (Camera2 interop).
 * Control state is composed and re-applied as one CaptureRequestOptions set —
 * interop options replace wholesale, so partial updates would drop siblings.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    private val _metered = MutableStateFlow<ExposureSettings?>(null)
    /** Live AE-metered pair from capture results — the solver's reference. */
    val metered: StateFlow<ExposureSettings?> = _metered

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var rawSupported = false
    private var rawOnlySupported = false
    private var rawEnabled = false

    // ── camera selection (front/back + extra lenses) ─────────────────────────
    private val cameras = mutableListOf<CameraSelector>()
    private val cameraLabels = mutableListOf<String>()
    private var cameraIndex = 0
    private var lensSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var aspect: AspectRatioMode = AspectRatioMode.RATIO_4_3

    /** Human labels for the discovered cameras, in switch order. */
    val availableCameraLabels: List<String> get() = cameraLabels.toList()
    val currentCameraLabel: String get() = cameraLabels.getOrElse(cameraIndex) { "CAM" }

    // Composed control state
    private var manualExposure: ExposureSettings? = null
    private var focusDiopters: Float? = null
    private var aeLocked = false
    private var awbLocked = false

    private val meteredCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            // Only trust the pair as "metered" while AE is actually running.
            if (manualExposure != null) return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            _metered.value = ExposureSettings(iso, shutter)
        }
    }

    suspend fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider): CameraCaps {
        val provider = cameraProvider()
        this.provider = provider
        this.owner = owner
        this.surfaceProvider = surfaceProvider
        discoverCameras(provider)
        return rebind()
    }

    /** Enumerate every camera CameraX exposes (front, back, and any extra lenses). */
    private fun discoverCameras(provider: ProcessCameraProvider) {
        cameras.clear()
        cameraLabels.clear()
        var back = 0
        var front = 0
        for (info in provider.availableCameraInfos) {
            cameras += info.cameraSelector
            cameraLabels += when (info.lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> if (front++ == 0) "FRONT" else "FRONT ${front}"
                CameraSelector.LENS_FACING_BACK -> if (back++ == 0) "BACK" else "BACK ${back}"
                else -> "CAM ${cameras.size}"
            }
        }
        // Start on the default back camera if present.
        cameraIndex = cameras.indexOfFirst {
            runCatching { provider.hasCamera(it) }.getOrDefault(false) &&
                cameraLabels[cameras.indexOf(it)] == "BACK"
        }.takeIf { it >= 0 } ?: 0
        lensSelector = cameras.getOrElse(cameraIndex) { CameraSelector.DEFAULT_BACK_CAMERA }
    }

    /**
     * (Re)bind preview + capture for the current lens, aspect ratio, and RAW
     * setting. Every control path (initial bind, camera switch, aspect change,
     * RAW toggle) funnels through here so the session is always consistent.
     * Must run on the main thread.
     */
    private fun rebind(): CameraCaps {
        val provider = provider ?: error("Camera not initialised")
        val owner = owner ?: error("Camera not initialised")
        val previewBuilder = Preview.Builder().setTargetAspectRatio(aspect.cameraXRatio)
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(meteredCallback)
        val preview = previewBuilder.build().also { it.surfaceProvider = surfaceProvider }
        val capture = buildCapture()

        provider.unbindAll()
        val cam = provider.bindToLifecycle(owner, lensSelector, preview, capture)
        camera = cam
        imageCapture = capture

        val formats = runCatching {
            ImageCapture.getImageCaptureCapabilities(cam.cameraInfo).supportedOutputFormats
        }.getOrDefault(emptySet())
        rawSupported = formats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        rawOnlySupported = formats.contains(ImageCapture.OUTPUT_FORMAT_RAW)
        if (!rawSupported) rawEnabled = false
        applyControls()
        return readCaps(cam)
    }

    /** Cycle to the next available camera; returns the new capabilities. */
    fun switchCamera(): CameraCaps {
        val cam = camera ?: error("Camera not bound")
        if (cameras.size <= 1) return readCaps(cam)
        cameraIndex = (cameraIndex + 1) % cameras.size
        lensSelector = cameras[cameraIndex]
        return rebind()
    }

    /** Change the capture aspect ratio; returns the (unchanged) capabilities. */
    fun setAspectRatio(mode: AspectRatioMode): CameraCaps {
        val cam = camera ?: error("Camera not bound")
        if (mode == aspect) return readCaps(cam)
        aspect = mode
        return rebind()
    }

    /** Toggle DNG capture (rebinds — the output format is fixed at use-case creation). */
    fun setRawEnabled(enabled: Boolean) {
        val want = enabled && rawSupported
        if (want == rawEnabled) return
        rawEnabled = want
        if (provider != null && owner != null) rebind()
    }

    private fun buildCapture(): ImageCapture {
        val builder = ImageCapture.Builder()
            // Shutter latency matters more than the last bit of quality for a
            // handheld film-sim camera; the graph re-renders afterward anyway.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(aspect.cameraXRatio)
        if (rawEnabled) builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        return builder.build()
    }

    /** null returns exposure to AE. */
    fun setManualExposure(settings: ExposureSettings?) {
        manualExposure = settings
        applyControls()
    }

    /** null returns focus to continuous AF. */
    fun setManualFocus(diopters: Float?) {
        focusDiopters = diopters
        applyControls()
    }

    fun setAeLock(locked: Boolean) {
        aeLocked = locked
        applyControls()
    }

    fun setAwbLock(locked: Boolean) {
        awbLocked = locked
        applyControls()
    }

    /**
     * Capture to MediaStore immediately — JPEG always, plus a DNG sibling when
     * RAW is enabled. [onResult] fires once, on the JPEG outcome; its non-null
     * [Uri] is the hook for the full-quality render job (B5).
     */
    fun takePhoto(onResult: (success: Boolean, message: String, jpegUri: Uri?) -> Unit) {
        val capture = imageCapture ?: return onResult(false, "Camera not bound", null)
        val name = "FE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val withDng = rawEnabled
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                // RAW+JPEG invokes this once per file; only JPEG drives the pipeline.
                if (result.imageFormat == ImageFormat.RAW_SENSOR) return
                val suffix = if (withDng) " (+DNG)" else ""
                onResult(true, "Saved $name$suffix", result.savedUri)
            }

            override fun onError(e: ImageCaptureException) =
                onResult(false, "Capture failed: ${e.message}", null)
        }
        val executor = ContextCompat.getMainExecutor(context)
        if (withDng) {
            capture.takePicture(
                mediaStoreOutput(name, "image/x-adobe-dng"),
                mediaStoreOutput(name, "image/jpeg"),
                executor,
                callback,
            )
        } else {
            capture.takePicture(mediaStoreOutput(name, "image/jpeg"), executor, callback)
        }
    }

    // ── calibration (B7) ────────────────────────────────────────────────────

    private fun <T> characteristic(key: CameraCharacteristics.Key<T>): T? =
        camera?.let { Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(key) }

    /**
     * One in-memory RAW_SENSOR frame for calibration. The RAW-only ImageCapture
     * use case is swapped in just for this shot and the normal one restored after —
     * heavy, but calibration shots are rare. Caller owns (and must close) the image.
     */
    fun captureCalibrationRaw(onResult: (androidx.camera.core.ImageProxy?, String) -> Unit) {
        val provider = provider ?: return onResult(null, "Camera not bound")
        val owner = owner ?: return onResult(null, "Camera not bound")
        if (!rawOnlySupported) return onResult(null, "RAW capture not supported")
        imageCapture?.let { provider.unbind(it) }
        val rawCapture = ImageCapture.Builder()
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
            .build()
        provider.bindToLifecycle(owner, lensSelector, rawCapture)
        applyControls()
        fun restore() {
            provider.unbind(rawCapture)
            rebind()
        }
        rawCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    restore()
                    onResult(image, "ok")
                }

                override fun onError(e: ImageCaptureException) {
                    restore()
                    onResult(null, "Calibration capture failed: ${e.message}")
                }
            },
        )
    }

    /** Bayer statistics for a calibration frame — [CalibrationStats] over the RAW16 plane. */
    fun calibrationStats(
        image: androidx.camera.core.ImageProxy,
        kind: String,
        iso: Int,
        exposureNs: Long,
        cct: Float,
    ): app.filmengine.profile.FrameStats {
        val plane = image.planes[0]
        val buf = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val cfa = characteristic(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        return CalibrationStats.compute(kind, iso, exposureNs, cct, image.width, image.height, cfa) { x, y ->
            buf.getShort(y * rowStride + x * pixelStride).toInt() and 0xFFFF
        }
    }

    /** §16 fallback profile straight from Camera2 static metadata. Null before bind. */
    fun harvestProfile(): app.filmengine.profile.DeviceProfile? {
        val cam = camera ?: return null
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val white = characteristic(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
        val black = characteristic(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.let { p -> (0..3).map { p.getOffsetForIndex(it % 2, it / 2) }.average().toFloat() } ?: 0f
        fun mat(t: android.hardware.camera2.params.ColorSpaceTransform?): FloatArray? =
            t?.let { FloatArray(9) { i -> it.getElement(i % 3, i / 3).toFloat() } }
        return DeviceProfiles.harvest(
            model = android.os.Build.MODEL,
            sensorId = info.cameraId,
            whiteLevel = white.toFloat(),
            blackLevel = black,
            illuminant1Code = characteristic(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21,
            illuminant2Code = characteristic(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 17,
            xyzToCam1 = mat(characteristic(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
            xyzToCam2 = mat(characteristic(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
        )
    }

    /**
     * §16 runtime lookup: a calibrated profile dropped in the app's external
     * files dir (profile-calibrator output, e.g. via adb push) wins; otherwise
     * fall back to the harvested one.
     */
    fun loadProfile(): app.filmengine.profile.DeviceProfile? {
        val f = java.io.File(context.getExternalFilesDir(null), "deviceprofile.json")
        if (f.exists()) {
            runCatching { return app.filmengine.profile.DeviceProfileCodec.decode(f.readText()) }
        }
        return harvestProfile()
    }

    private fun mediaStoreOutput(name: String, mimeType: String): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FilmEngine")
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
    }

    private fun applyControls() {
        val control = camera?.cameraControl ?: return
        val builder = CaptureRequestOptions.Builder()
        manualExposure?.let {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it.iso)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it.shutterNs)
        }
        focusDiopters?.let {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        if (aeLocked && manualExposure == null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (awbLocked) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        Camera2CameraControl.from(control).setCaptureRequestOptions(builder.build())
    }

    private fun readCaps(cam: Camera): CameraCaps {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        return CameraCaps(
            exposure = ExposureCaps(
                isoMin = isoRange?.lower ?: 100,
                isoMax = isoRange?.upper ?: 800,
                minShutterNs = shutterRange?.lower ?: 1_000_000L,
                maxShutterNs = shutterRange?.upper ?: 100_000_000L,
            ),
            minFocusDiopters = minFocus ?: 0f,
            rawSupported = rawSupported,
        )
    }

    private suspend fun cameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }
}
