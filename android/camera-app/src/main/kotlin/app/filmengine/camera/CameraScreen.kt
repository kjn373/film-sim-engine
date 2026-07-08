package app.filmengine.camera

import android.widget.Toast
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import app.filmengine.camera.core.ExposureMode
import app.filmengine.film.BuiltinStocks
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun CameraScreen(vm: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by vm.ui.collectAsState()
    val caps by vm.caps.collectAsState()
    var selectedStock by remember { mutableStateOf(BuiltinStocks.all.first().id) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { view -> vm.bind(lifecycleOwner, view.surfaceProvider) }
            },
        )

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

            LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                items(BuiltinStocks.all, key = { it.id }) { stock ->
                    FilterChip(
                        selected = stock.id == selectedStock,
                        onClick = { selectedStock = stock.id },
                        label = { Text(stock.name) },
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
