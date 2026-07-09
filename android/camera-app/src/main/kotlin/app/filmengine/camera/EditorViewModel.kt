package app.filmengine.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.filmengine.camera.core.CaptureRender
import app.filmengine.camera.core.EditLayer
import app.filmengine.camera.core.EditStack
import app.filmengine.recipe.RecipeCodec
import app.filmengine.render.cpu.BuiltinCpuKernels
import app.filmengine.render.cpu.CpuBackend
import app.filmengine.render.cpu.FilmCpuKernels
import app.filmengine.render.cpu.PlanFusion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * B8 editor: non-destructive layer stack over a picked photo.
 *
 * The working state is a [List] of [EditLayer]; every mutation re-renders a
 * debounced CPU preview (fused plan, ≤[PREVIEW_EDGE]px). "Save version"
 * appends the stack — encoded as a RecipeCodec graph document — to the
 * session's append-only history; restoring any version just decodes it back
 * into layers (edit → reopen → re-edit lossless).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dao: EditHistoryDao,
) : ViewModel() {

    private val _imageUri = MutableStateFlow<Uri?>(null)
    val imageUri: StateFlow<Uri?> = _imageUri

    private val _layers = MutableStateFlow<List<EditLayer>>(emptyList())
    val layers: StateFlow<List<EditLayer>> = _layers

    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview

    private val _rendering = MutableStateFlow(false)
    val rendering: StateFlow<Boolean> = _rendering

    private val _sessionId = MutableStateFlow<Long?>(null)

    val versions: StateFlow<List<EditVersionEntity>> = _sessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.versions(id) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Preview source pixels (sRGB ARGB), decoded once per opened image.
    private var srcPixels: IntArray? = null
    private var srcW = 0
    private var srcH = 0

    private val kernels = BuiltinCpuKernels.all + FilmCpuKernels.all
    private val backend = CpuBackend(kernels)

    init {
        viewModelScope.launch {
            _layers.debounce(250).collectLatest { renderPreview(it) }
        }
    }

    /** Open a picked photo: load/create its session and restore the latest version. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            _imageUri.value = uri
            _preview.value = null

            val bitmap = withContext(Dispatchers.IO) {
                MediaImages.load(appContext, uri, PREVIEW_EDGE)
            } ?: return@launch
            srcW = bitmap.width
            srcH = bitmap.height
            srcPixels = IntArray(srcW * srcH).also {
                bitmap.getPixels(it, 0, srcW, 0, 0, srcW, srcH)
            }
            bitmap.recycle()

            val key = uri.toString()
            val sessionId = dao.sessionFor(key)?.id
                ?: dao.insertSession(
                    EditSessionEntity(imageUri = key, createdAt = System.currentTimeMillis())
                )
            _sessionId.value = sessionId

            val latest = dao.versions(sessionId).first().lastOrNull()
            _layers.value = latest
                ?.let { EditStack.fromGraph(RecipeCodec.decode(it.graphJson)) }
                ?: emptyList()
            renderPreview(_layers.value) // debounce won't fire if layers are value-equal
        }
    }

    // ── layer stack ops ─────────────────────────────────────────────────────

    fun addLayer(type: String) {
        _layers.value += EditStack.defaultLayer(type)
    }

    fun removeLayer(index: Int) {
        _layers.value = _layers.value.filterIndexed { i, _ -> i != index }
    }

    fun toggleLayer(index: Int) = update(index) { it.copy(enabled = !it.enabled) }

    fun moveLayer(index: Int, delta: Int) {
        val list = _layers.value.toMutableList()
        val to = index + delta
        if (index !in list.indices || to !in list.indices) return
        list[index] = list[to].also { list[to] = list[index] }
        _layers.value = list
    }

    fun setParam(index: Int, key: String, value: Float) =
        update(index) { it.copy(params = it.params + (key to value)) }

    fun setOption(index: Int, key: String, value: String) =
        update(index) { it.copy(options = it.options + (key to value)) }

    private fun update(index: Int, transform: (EditLayer) -> EditLayer) {
        _layers.value = _layers.value.mapIndexed { i, l -> if (i == index) transform(l) else l }
    }

    // ── history + export ────────────────────────────────────────────────────

    fun saveVersion() {
        viewModelScope.launch {
            val sessionId = _sessionId.value ?: return@launch
            dao.insertVersion(
                EditVersionEntity(
                    sessionId = sessionId,
                    seq = (dao.maxSeq(sessionId) ?: 0) + 1,
                    graphJson = RecipeCodec.encode(EditStack.toGraph(_layers.value)),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun restore(version: EditVersionEntity) {
        _layers.value = EditStack.fromGraph(RecipeCodec.decode(version.graphJson))
    }

    /** Enqueue the full-resolution tiled export ([ExportWorker.FORMAT_JPEG]/[FORMAT_HEIF]). */
    fun export(format: String) {
        val uri = _imageUri.value ?: return
        ExportWorker.enqueue(
            appContext, uri, RecipeCodec.encode(EditStack.toGraph(_layers.value)), format,
        )
    }

    // ── preview render ──────────────────────────────────────────────────────

    private suspend fun renderPreview(layers: List<EditLayer>) {
        val px = srcPixels ?: return
        _rendering.value = true
        val image = withContext(Dispatchers.Default) {
            val plan = PlanFusion.fuse(EditStack.compile(layers), EditStack.registry, kernels)
            val out = backend.render(plan, CaptureRender.decode(px, srcW, srcH))
            Bitmap.createBitmap(CaptureRender.encode(out), srcW, srcH, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
        _preview.value = image
        _rendering.value = false
    }

    companion object {
        // ponytail: CPU preview at ≤1280px, debounced — a GPU editor preview
        // rides in with the export EGL context when slider latency demands it.
        private const val PREVIEW_EDGE = 1280
    }
}
