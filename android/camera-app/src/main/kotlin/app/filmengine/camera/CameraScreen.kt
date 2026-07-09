package app.filmengine.camera

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import app.filmengine.camera.core.ExposureMode
import app.filmengine.camera.core.QualityLevel
import app.filmengine.film.BuiltinStocks
import app.filmengine.render.gles.ScopeData
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Stock chip descriptor: `null` id means "None" (passthrough, no film sim).
 */
private data class StockChip(val id: String?, val name: String)

private val stockChips: List<StockChip> = buildList {
    add(StockChip(null, "None"))
    BuiltinStocks.all.forEach { add(StockChip(it.id, it.name)) }
}

@Composable
fun CameraScreen(vm: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by vm.ui.collectAsState()
    val caps by vm.caps.collectAsState()
    val selectedStock by vm.selectedStock.collectAsState()
    val rawEnabled by vm.rawEnabled.collectAsState()
    val frameTime by vm.frameTimeMs.collectAsState()
    val quality by vm.qualityLevel.collectAsState()
    val scopeMode by vm.scopeMode.collectAsState()
    val scopeData by vm.scopeData.collectAsState()
    val zebra by vm.zebra.collectAsState()
    val peaking by vm.peaking.collectAsState()

    // Bind camera once; pipeline lifecycle managed via SurfaceHolder callbacks.
    DisposableEffect(lifecycleOwner) {
        vm.bind(lifecycleOwner)
        onDispose { vm.stopPipeline() }
    }

    Box(Modifier.fillMaxSize()) {
        // ── Processed preview surface ───────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val s = holder.surface
                            val r = holder.surfaceFrame
                            vm.startPipeline(s, r.width(), r.height())
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                            vm.onSurfaceChanged(w, h)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vm.stopPipeline()
                        }
                    })
                }
            },
        )

        // ── Debug HUD (top-left) ────────────────────────────────────────
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "%.1f ms".format(frameTime),
                color = if (frameTime > 16.6f) Color(0xFFFF6B6B) else Color(0xFF69DB7C),
                fontSize = 12.sp,
            )
            if (quality != QualityLevel.FULL) {
                Text(
                    quality.name,
                    color = Color(0xFFFFD43B),
                    fontSize = 10.sp,
                )
            }
        }

        // ── Scope overlay (top-right) ───────────────────────────────────
        scopeData?.let { data ->
            if (scopeMode != ScopeMode.OFF) {
                ScopeOverlay(
                    data = data,
                    mode = scopeMode,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }

        // ── Controls overlay (bottom) ───────────────────────────────────
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Readout: ISO · shutter · EC/clip warning
            val readout = buildString {
                append("ISO ${ui.iso}  ·  ${formatShutter(ui.shutterNs)}")
                if (ui.ecStops != 0f) append("  ·  EC %+.1f".format(ui.ecStops))
                if (kotlin.math.abs(ui.offsetStops) > 0.2f) {
                    append("  ·  %+.1f stops off".format(ui.offsetStops))
                }
            }
            Text(readout, color = Color.White, style = MaterialTheme.typography.labelLarge)

            // Mode selector
            Row {
                for ((mode, label) in listOf(
                    ExposureMode.AUTO to "A",
                    ExposureMode.SHUTTER_PRIORITY to "S",
                    ExposureMode.ISO_PRIORITY to "ISO",
                    ExposureMode.MANUAL to "M",
                )) {
                    FilterChip(
                        selected = ui.mode == mode,
                        onClick = { vm.setMode(mode) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                if (caps?.rawSupported == true) {
                    FilterChip(
                        selected = rawEnabled,
                        onClick = { vm.setRaw(!rawEnabled) },
                        label = { Text("RAW") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                FilterChip(
                    selected = scopeMode != ScopeMode.OFF,
                    onClick = { vm.cycleScopeMode() },
                    label = {
                        Text(
                            when (scopeMode) {
                                ScopeMode.OFF -> "Scope"
                                ScopeMode.HISTOGRAM -> "Hist"
                                ScopeMode.WAVEFORM -> "Wave"
                            }
                        )
                    },
                    modifier = Modifier.padding(end = 6.dp),
                )
                FilterChip(
                    selected = zebra,
                    onClick = { vm.setZebra(!zebra) },
                    label = { Text("Zebra") },
                    modifier = Modifier.padding(end = 6.dp),
                )
                FilterChip(
                    selected = peaking,
                    onClick = { vm.setPeaking(!peaking) },
                    label = { Text("Peak") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }

            // Manual sliders (log-scaled)
            caps?.exposure?.let { e ->
                if (ui.mode == ExposureMode.MANUAL || ui.mode == ExposureMode.ISO_PRIORITY) {
                    LogSlider(
                        label = "ISO",
                        value = ui.iso.toFloat(),
                        min = e.isoMin.toFloat(),
                        max = e.isoMax.toFloat(),
                    ) { vm.setUserIso(it.roundToInt()) }
                }
                if (ui.mode == ExposureMode.MANUAL || ui.mode == ExposureMode.SHUTTER_PRIORITY) {
                    LogSlider(
                        label = "Shutter",
                        value = ui.shutterNs.toFloat(),
                        min = e.minShutterNs.toFloat(),
                        max = minOf(e.maxShutterNs, 250_000_000L).toFloat(), // cap UI at 1/4 s
                    ) { vm.setUserShutter(it.roundToLong()) }
                }
                if (ui.mode != ExposureMode.MANUAL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("EC", color = Color.White, modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = ui.ecStops,
                            onValueChange = { vm.setEc((it * 3f).roundToInt() / 3f) },
                            valueRange = -3f..3f,
                        )
                    }
                }
            }

            // ── Stock selector (with "None" passthrough option) ─────────
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                items(stockChips, key = { it.id ?: "_none" }) { chip ->
                    FilterChip(
                        selected = chip.id == selectedStock,
                        onClick = { vm.setStock(chip.id) },
                        label = { Text(chip.name) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
                    .size(60.dp)
                    .clickable {
                        vm.takePhoto { _, message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
            ) {}
        }
    }
}

/**
 * Histogram / waveform panel drawn from the latest [ScopeData] readback.
 * Histogram: R/G/B channel bars (additive alpha) + white luma outline.
 * Waveform: heat bitmap, rows flipped so white is at the top.
 */
@Composable
private fun ScopeOverlay(data: ScopeData, mode: ScopeMode, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(180.dp, 100.dp)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(4.dp),
    ) {
        when (mode) {
            ScopeMode.HISTOGRAM -> {
                val max = maxOf(
                    data.red.max(), data.green.max(), data.blue.max(), 1,
                )
                drawHistogram(data.red, Color(0xFFFF5252), max)
                drawHistogram(data.green, Color(0xFF69F0AE), max)
                drawHistogram(data.blue, Color(0xFF448AFF), max)
                drawHistogram(data.luma, Color.White, maxOf(data.luma.max(), 1), alpha = 0.9f)
            }
            ScopeMode.WAVEFORM -> drawImage(
                image = waveformBitmap(data),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                srcSize = IntSize(ScopeData.WAVE_W, ScopeData.WAVE_H),
                filterQuality = FilterQuality.Low,
            )
            ScopeMode.OFF -> Unit
        }
    }
}

private fun DrawScope.drawHistogram(bins: IntArray, color: Color, max: Int, alpha: Float = 0.55f) {
    val bw = size.width / bins.size
    for (i in bins.indices) {
        if (bins[i] == 0) continue
        val h = bins[i].toFloat() / max * size.height
        drawRect(
            color = color,
            topLeft = Offset(i * bw, size.height - h),
            size = Size(bw, h),
            alpha = alpha,
        )
    }
}

// ponytail: one small Bitmap alloc per scope readback (≤15 Hz); reuse a
// mutable Bitmap if the Allocation Tracker ever flags it.
private fun waveformBitmap(data: ScopeData): ImageBitmap {
    val w = ScopeData.WAVE_W
    val h = ScopeData.WAVE_H
    val max = maxOf(data.waveform.max(), 1)
    val logMax = ln(1f + max)
    val px = IntArray(w * h)
    for (i in data.waveform.indices) {
        val v = data.waveform[i]
        if (v == 0) continue
        val intensity = (ln(1f + v) / logMax * 255f).roundToInt().coerceIn(0, 255)
        // row 0 = black level → draw at the bottom
        val y = h - 1 - i / w
        px[y * w + i % w] = (intensity shl 24) or 0x69F0AE
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Slider that maps position 0..1 exponentially onto [min, max]. */
@Composable
private fun LogSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    val fraction = if (value <= min) 0f
    else (ln(value / min) / ln(max / min)).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.padding(end = 8.dp))
        Slider(
            value = fraction,
            onValueChange = { f -> onChange(min * (max / min).pow(f)) },
        )
    }
}

private fun formatShutter(ns: Long): String = when {
    ns <= 0 -> "—"
    ns >= 1_000_000_000 -> "%.1fs".format(ns / 1e9)
    else -> "1/${(1e9 / ns).roundToInt()}"
}
