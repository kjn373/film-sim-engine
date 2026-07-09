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
    private var rawSupported = false
    private var rawEnabled = false

    // Composed control state
    private var manualExposure: ExposureSettings? = null
    private var focusDiopters: Float? = null
    private var aeLocked = false
    private var awbLocked = false

    suspend fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider): CameraCaps {
        val provider = cameraProvider()
        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
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
        )
        val preview = previewBuilder.build().also { it.surfaceProvider = surfaceProvider }
        val capture = buildCapture()

        provider.unbindAll()
        val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        camera = cam
        imageCapture = capture
        this.provider = provider
        this.owner = owner
        rawSupported = runCatching {
            ImageCapture.getImageCaptureCapabilities(cam.cameraInfo)
                .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }.getOrDefault(false)
        return readCaps(cam)
    }

    /**
     * Toggle DNG capture. The output format is fixed at use-case creation, so
     * this swaps the ImageCapture use case in place (preview stays bound).
     */
    fun setRawEnabled(enabled: Boolean) {
        val want = enabled && rawSupported
        if (want == rawEnabled) return
        rawEnabled = want
        val provider = provider ?: return
        val owner = owner ?: return
        imageCapture?.let { provider.unbind(it) }
        val capture = buildCapture()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
        imageCapture = capture
        applyControls()
    }

    private fun buildCapture(): ImageCapture {
        val builder = ImageCapture.Builder()
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
