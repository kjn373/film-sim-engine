package app.filmengine.camera

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.filmengine.camera.core.EditLayer
import app.filmengine.camera.core.EditStack
import app.filmengine.film.BuiltinStocks

/**
 * B8 editor: pick a photo → tune a layer stack over a live-rendered preview →
 * save versions (append-only) → export full-res JPEG/HEIF in the background.
 */
@Composable
fun EditorScreen(onBack: () -> Unit, vm: EditorViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val imageUri by vm.imageUri.collectAsState()
    val layers by vm.layers.collectAsState()
    val preview by vm.preview.collectAsState()
    val rendering by vm.rendering.collectAsState()
    val versions by vm.versions.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(vm::open) }

    Column(Modifier.fillMaxSize()) {
        // ── top bar ─────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("◀ Camera") }
            TextButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (imageUri == null) "Pick photo" else "Change") }
            if (imageUri != null) {
                TextButton(onClick = {
                    vm.saveVersion()
                    Toast.makeText(context, "Version saved", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
                TextButton(onClick = {
                    vm.export(ExportWorker.FORMAT_JPEG)
                    Toast.makeText(context, "Export queued (JPEG)", Toast.LENGTH_SHORT).show()
                }) { Text("JPEG") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    TextButton(onClick = {
                        vm.export(ExportWorker.FORMAT_HEIF)
                        Toast.makeText(context, "Export queued (HEIF)", Toast.LENGTH_SHORT).show()
                    }) { Text("HEIF") }
                }
            }
        }

        if (imageUri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Pick a photo to edit") }
            }
            return@Column
        }

        // ── preview ─────────────────────────────────────────────────────
        Box(
            Modifier.fillMaxWidth().height(280.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, contentDescription = "Edited preview", contentScale = ContentScale.Fit)
            }
            if (rendering) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }

        // ── version history (append-only) ───────────────────────────────
        if (versions.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                items(versions, key = { it.id }) { v ->
                    FilterChip(
                        selected = false,
                        onClick = { vm.restore(v) },
                        label = { Text("v${v.seq}") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }

        // ── add-layer palette ───────────────────────────────────────────
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            items(EditStack.palette) { type ->
                FilterChip(
                    selected = false,
                    onClick = { vm.addLayer(type) },
                    label = { Text("+ ${type.replace('_', ' ')}") },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        // ── layer stack ─────────────────────────────────────────────────
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            itemsIndexed(layers) { i, layer ->
                LayerRow(
                    index = i,
                    layer = layer,
                    lastIndex = layers.lastIndex,
                    vm = vm,
                )
            }
        }
    }
}

@Composable
private fun LayerRow(index: Int, layer: EditLayer, lastIndex: Int, vm: EditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                layer.type.replace('_', ' '),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            Switch(checked = layer.enabled, onCheckedChange = { vm.toggleLayer(index) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.moveLayer(index, -1) }, enabled = index > 0) { Text("▲") }
                TextButton(onClick = { vm.moveLayer(index, 1) }, enabled = index < lastIndex) { Text("▼") }
                TextButton(onClick = { vm.removeLayer(index) }) { Text("✕") }
            }
        }
        if (!layer.enabled) return@Column

        if (layer.type == "film_sim") {
            LazyRow {
                items(BuiltinStocks.all) { stock ->
                    FilterChip(
                        selected = layer.options["stock"] == stock.id,
                        onClick = { vm.setOption(index, "stock", stock.id) },
                        label = { Text(stock.name) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
        for (spec in EditStack.descriptor(layer.type).params) {
            val value = layer.params[spec.key] ?: spec.default
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${spec.key} %.2f".format(value),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = value,
                    onValueChange = { vm.setParam(index, spec.key, it) },
                    valueRange = spec.min..spec.max,
                )
            }
        }
    }
}
