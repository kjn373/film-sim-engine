package app.filmengine.camera

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import app.filmengine.camera.core.AspectRatioMode
import app.filmengine.camera.core.ExposureMode
import app.filmengine.camera.core.QualityLevel
import app.filmengine.film.BuiltinStocks
import app.filmengine.render.gles.ScopeData
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val Accent = Color(0xFFFFC107)
private val PillBg = Color(0x2EFFFFFF)
private val DimText = Color(0x99FFFFFF)

/** Which readout segment's slider is expanded. */
private enum class ActiveControl { SHUTTER, ISO, EV }

private data class StockChip(val id: String?, val name: String)

private val stockChips: List<StockChip> = buildList {
    add(StockChip(null, "None"))
    BuiltinStocks.all.forEach { add(StockChip(it.id, it.name)) }
}

@Composable
fun CameraScreen(onOpenEditor: () -> Unit = {}, vm: CameraViewModel = hiltViewModel()) {
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
    val calMode by vm.calMode.collectAsState()
    val aspect by vm.aspect.collectAsState()
    val cameraLabel by vm.cameraLabel.collectAsState()
    val canSwitchCamera by vm.canSwitchCamera.collectAsState()
    val capturing by vm.capturing.collectAsState()
    val flashTick by vm.flashTick.collectAsState()

    var activeControl by remember { mutableStateOf<ActiveControl?>(null) }
    var stocksVisible by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        vm.bind(lifecycleOwner)
        onDispose { vm.stopPipeline() }
    }

    // Brief white flash as shutter feedback — snaps to full alpha, fades over 220ms.
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, tween(220))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ── top bar ─────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "%.0fms".format(frameTime),
                color = if (frameTime > 16.6f) Color(0xFFFF6B6B) else DimText,
                fontSize = 12.sp,
            )
            if (quality != QualityLevel.FULL) {
                Text("  ${quality.name}", color = Color(0xFFFFD43B), fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TopToggle(
                    when (scopeMode) {
                        ScopeMode.OFF -> "SCOPE"
                        ScopeMode.HISTOGRAM -> "HIST"
                        ScopeMode.WAVEFORM -> "WAVE"
                    },
                    on = scopeMode != ScopeMode.OFF,
                ) { vm.cycleScopeMode() }
                TopToggle("ZEBRA", zebra) { vm.setZebra(!zebra) }
                TopToggle("PEAK", peaking) { vm.setPeaking(!peaking) }
                TopToggle(aspect.label, on = true) { vm.cycleAspect() }
                if (caps?.rawSupported == true) {
                    TopToggle("RAW", rawEnabled) { vm.setRaw(!rawEnabled) }
                    TopToggle(calMode.label, calMode != CalMode.OFF) { vm.cycleCalMode() }
                }
            }
        }

        // ── viewfinder ──────────────────────────────────────────────────
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().aspectRatio(aspect.displayRatio)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    val r = holder.surfaceFrame
                                    vm.startPipeline(holder.surface, r.width(), r.height())
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
                GridOverlay()
                scopeData?.let { data ->
                    if (scopeMode != ScopeMode.OFF) {
                        ScopeOverlay(data, scopeMode, Modifier.align(Alignment.TopEnd).padding(12.dp))
                    }
                }
                if (canSwitchCamera) {
                    Column(
                        Modifier.align(Alignment.TopStart).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RoundButton("🔄", onClick = vm::switchCamera)
                        Text(cameraLabel, color = DimText, fontSize = 9.sp)
                    }
                }
            }
            if (flashAlpha.value > 0f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
            }
        }

        // ── readout pill ────────────────────────────────────────────────
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(50))
                .background(PillBg)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReadoutSegment(
                label = "S",
                value = formatShutter(ui.shutterNs),
                auto = ui.mode == ExposureMode.AUTO || ui.mode == ExposureMode.ISO_PRIORITY,
                onClick = { activeControl = ActiveControl.SHUTTER.takeIf { activeControl != it } },
            )
            ReadoutSegment(
                label = "ISO",
                value = if (ui.iso > 0) "${ui.iso}" else "—",
                auto = ui.mode == ExposureMode.AUTO || ui.mode == ExposureMode.SHUTTER_PRIORITY,
                onClick = { activeControl = ActiveControl.ISO.takeIf { activeControl != it } },
            )
            ReadoutSegment(
                label = "EV",
                value = "%.1f".format(ui.ecStops),
                auto = false,
                onClick = { activeControl = ActiveControl.EV.takeIf { activeControl != it } },
            )
            if (kotlin.math.abs(ui.offsetStops) > 0.2f) {
                Text(
                    "%+.1f".format(ui.offsetStops),
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }

        // ── expanded control slider ─────────────────────────────────────
        caps?.exposure?.let { e ->
            Box(Modifier.padding(horizontal = 24.dp)) {
                when (activeControl) {
                    ActiveControl.SHUTTER ->
                        if (ui.mode == ExposureMode.MANUAL || ui.mode == ExposureMode.SHUTTER_PRIORITY) {
                            LogSlider(
                                value = ui.shutterNs.toFloat(),
                                min = e.minShutterNs.toFloat(),
                                max = minOf(e.maxShutterNs, 250_000_000L).toFloat(),
                            ) { vm.setUserShutter(it.roundToLong()) }
                        }
                    ActiveControl.ISO ->
                        if (ui.mode == ExposureMode.MANUAL || ui.mode == ExposureMode.ISO_PRIORITY) {
                            LogSlider(
                                value = ui.iso.toFloat(),
                                min = e.isoMin.toFloat(),
                                max = e.isoMax.toFloat(),
                            ) { vm.setUserIso(it.roundToInt()) }
                        }
                    ActiveControl.EV ->
                        if (ui.mode != ExposureMode.MANUAL) {
                            Slider(
                                value = ui.ecStops,
                                onValueChange = { vm.setEc((it * 3f).roundToInt() / 3f) },
                                valueRange = -3f..3f,
                            )
                        }
                    null -> Unit
                }
            }
        }

        // ── stock strip ─────────────────────────────────────────────────
        if (stocksVisible) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                items(stockChips, key = { it.id ?: "_none" }) { chip ->
                    val sel = chip.id == selectedStock
                    Text(
                        chip.name,
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) Accent else PillBg)
                            .clickable { vm.setStock(chip.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // ── mode pill ───────────────────────────────────────────────────
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(PillBg)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((mode, label) in listOf(
                ExposureMode.AUTO to "A",
                ExposureMode.SHUTTER_PRIORITY to "S",
                ExposureMode.ISO_PRIORITY to "ISO",
                ExposureMode.MANUAL to "M",
            )) {
                val sel = ui.mode == mode
                Text(
                    label,
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (sel) Accent else Color.Transparent)
                        .clickable { vm.setMode(mode) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // ── bottom row: editor · shutter · stocks toggle ───────────────
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 32.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton("🧪", onClick = onOpenEditor)
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF141414))
                        .clickable(enabled = !capturing) {
                            val onDone = { _: Boolean, message: String ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                            if (calMode != CalMode.OFF) vm.captureCalibration(onDone)
                            else vm.takePhoto(onDone)
                        },
                )
                if (capturing) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(40.dp))
                }
            }
            RoundButton("🎞️", on = stocksVisible, onClick = { stocksVisible = !stocksVisible })
        }
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────

@Composable
private fun TopToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Accent else DimText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun ReadoutSegment(label: String, value: String, auto: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ", color = DimText, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (auto) {
            Box(
                Modifier.padding(start = 4.dp).size(15.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RoundButton(glyph: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (on) Color(0x4DFFFFFF) else PillBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 20.sp)
    }
}

/** Rule-of-thirds grid + center reticle over the viewfinder. */
@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.25f)
        for (f in floatArrayOf(1f / 3f, 2f / 3f)) {
            drawLine(line, Offset(size.width * f, 0f), Offset(size.width * f, size.height), 1.dp.toPx())
            drawLine(line, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx())
        }
        drawCircle(Color.White.copy(alpha = 0.6f), radius = 40.dp.toPx(), style = Stroke(1.5f.dp.toPx()))
        val l = 11.dp.toPx()
        drawLine(Color.White, center - Offset(l, 0f), center + Offset(l, 0f), 2.dp.toPx())
        drawLine(Color.White, center - Offset(0f, l), center + Offset(0f, l), 2.dp.toPx())
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
                val max = maxOf(data.red.max(), data.green.max(), data.blue.max(), 1)
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
        val y = h - 1 - i / w
        px[y * w + i % w] = (intensity shl 24) or 0x69F0AE
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Slider that maps position 0..1 exponentially onto [min, max]. */
@Composable
private fun LogSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    val fraction = if (value <= min) 0f
    else (ln(value / min) / ln(max / min)).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = { f -> onChange(min * (max / min).pow(f)) },
    )
}

private fun formatShutter(ns: Long): String = when {
    ns <= 0 -> "—"
    ns >= 1_000_000_000 -> "%.1fs".format(ns / 1e9)
    else -> "1/${(1e9 / ns).roundToInt()}"
}
