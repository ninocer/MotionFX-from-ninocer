package dev.motionfx.app

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.motionfx.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PluginRow(val installed: InstalledPlugin, val contributions: List<Contribution>, val problem: String? = null)
data class EditorState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val project: ProjectDocument = ProjectDocument(),
    val plugins: List<PluginRow> = emptyList(),
    val preview: Bitmap? = null,
    val time: Float = 1f,
    val playing: Boolean = false,
    val exportProgress: Float? = null,
    val message: String? = null,
    val issues: List<PluginIssue> = emptyList(),
    val exportedUri: Uri? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val guard = Mutex()
    private val files = application.filesDir
    private val store = ProjectStore(File(files, "composition.mfx"))
    private lateinit var manager: PluginManager
    private var source = demoFrame()
    private val second = demoFrame(reverse = true)
    private var renderJob: Job? = null
    private var playJob: Job? = null
    private var exportJob: Job? = null

    init {
        work {
            manager = PluginManager(File(files, "plugins"))
            val marker = File(files, "starter-installed")
            if (!marker.exists()) {
                if (manager.list().none { it.manifest.id == "dev.motionfx.starter" }) {
                    application.assets.open("starter.mfxplugin").use { manager.install(it) }
                    manager.enable("dev.motionfx.starter")
                }
                marker.writeText("1")
            }
            val project = store.load() ?: ProjectDocument(plugins = listOf(PluginReference("dev.motionfx.starter", "1.0.0", "warm"))).also(store::save)
            project.mediaFile?.let { source = decodeImage(Uri.fromFile(File(files, it))) }
            mutable.update { it.copy(ready = true, project = project) }
            refreshPlugins()
            render()
        }
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            guard.withLock {
                mutable.update { it.copy(busy = true) }
                try { withContext(Dispatchers.Default) { block() } }
                finally { mutable.update { it.copy(busy = false) } }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(message = e.message ?: e.javaClass.simpleName) } }
    }

    private fun refreshPlugins() {
        val rows = manager.list().map { plugin ->
            var problem: String? = null
            val contributions = if (plugin.enabled) runCatching { manager.contributions(plugin.manifest.id) }
                .getOrElse { problem = it.message ?: "Plugin unavailable"; emptyList() } else emptyList()
            PluginRow(plugin, contributions, problem)
        }
        mutable.update { it.copy(plugins = rows) }
    }

    fun install(uri: Uri) = work {
        checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { manager.install(it) }
        refreshPlugins()
        mutable.update { it.copy(message = getApplication<Application>().getString(R.string.installed_disabled)) }
    }
    fun setEnabled(id: String, enabled: Boolean) = work {
        if (enabled) manager.enable(id) else manager.disable(id)
        refreshPlugins(); render()
    }
    fun uninstall(id: String) = work { manager.uninstall(id); refreshPlugins(); render() }

    fun add(plugin: InstalledPlugin, contribution: Contribution) = edit { project ->
        require(project.plugins.size < 16) { "Maximum 16 contributions per composition" }
        project.copy(plugins = project.plugins + PluginReference(plugin.manifest.id, plugin.manifest.version, contribution.id))
    }
    fun remove(index: Int) = edit { it.copy(plugins = it.plugins.filterIndexed { i, _ -> i != index }) }
    fun toggle(index: Int) = edit { it.copy(plugins = it.plugins.mapIndexed { i, r -> if (i == index) r.copy(enabled = !r.enabled) else r }) }
    fun parameter(index: Int, name: String, value: Float) = edit { it.copy(plugins = it.plugins.mapIndexed { i, r -> if (i == index) r.copy(parameters = r.parameters + (name to value)) else r }) }

    private fun edit(transform: (ProjectDocument) -> ProjectDocument) {
        pause()
        work {
            val project = transform(mutable.value.project)
            store.save(project) // Commit first, only then replace visible state.
            mutable.update { it.copy(project = project) }
            render()
        }
    }

    fun importImage(uri: Uri) {
        pause()
        work {
            val frame = decodeImage(uri)
            val file = File(files, "image-${UUID.randomUUID()}.png")
            FileOutputStream(file).use { out ->
                val bitmap = frame.bitmap()
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.fd.sync() }
                finally { bitmap.recycle() }
            }
            val project = mutable.value.project.copy(mediaFile = file.name)
            store.save(project)
            source = frame
            mutable.update { it.copy(project = project) }
            render()
        }
    }

    fun seek(seconds: Float) {
        mutable.update { it.copy(time = seconds.coerceIn(0f, it.project.durationSeconds)) }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(25)
            guard.withLock { withContext(Dispatchers.Default) { render() } }
        }
    }
    fun play() {
        if (mutable.value.playing) { pause(); return }
        playJob = viewModelScope.launch {
            mutable.update { it.copy(playing = true) }
            try {
                val startTime = mutable.value.time.takeIf { it < mutable.value.project.durationSeconds } ?: 0f
                val start = System.nanoTime()
                while (isActive) {
                    val time = startTime + (System.nanoTime() - start) / 1_000_000_000f
                    if (time >= mutable.value.project.durationSeconds) break
                    mutable.update { it.copy(time = time) }
                    guard.withLock { withContext(Dispatchers.Default) { render() } }
                    delay(33) // Adaptive playback: never queue overdue frames.
                }
            } finally { mutable.update { it.copy(playing = false) } }
        }
    }
    fun pause() { playJob?.cancel(); mutable.update { it.copy(playing = false) } }

    private fun render(): RenderResult {
        val current = mutable.value
        val result = LazyGpu(source.width, source.height).use { gpu ->
            FrameEngine(manager, gpu).render(source, current.project.plugins, current.time, current.time / current.project.durationSeconds, second)
        }
        mutable.update { it.copy(preview = result.frame.bitmap(), issues = result.issues.distinct()) }
        return result
    }

    fun exportVideo() {
        pause()
        exportJob = work {
            mutable.update { it.copy(exportProgress = 0f, exportedUri = null) }
            try {
                val uri = VideoExporter.export(getApplication(), manager, mutable.value.project, source, second,
                    { progress -> mutable.update { it.copy(exportProgress = progress) } },
                    { issues -> mutable.update { it.copy(issues = issues.distinct()) } })
                mutable.update { it.copy(exportedUri = uri, message = getApplication<Application>().getString(R.string.video_saved)) }
            } finally { mutable.update { it.copy(exportProgress = null) } }
        }
    }
    fun cancelExport() { exportJob?.cancel() }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }

    fun exportPng() = work {
        val result = render()
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MotionFX-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MotionFX")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val bitmap = result.frame.bitmap()
            try { checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            mutable.update { it.copy(message = getApplication<Application>().getString(R.string.png_saved)) }
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    private fun decodeImage(uri: Uri): Frame {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(WIDTH.toFloat() / info.size.width, HEIGHT.toFloat() / info.size.height, 1f)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        val fitted = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(fitted); canvas.drawColor(Color.BLACK)
            val scale = minOf(WIDTH.toFloat() / bitmap.width, HEIGHT.toFloat() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(WIDTH * HEIGHT); fitted.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return Frame(WIDTH, HEIGHT, pixels)
        } finally { bitmap.recycle(); fitted.recycle() }
    }
    companion object {
        const val WIDTH = 640; const val HEIGHT = 360
        fun demoFrame(reverse: Boolean = false): Frame = Frame(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH; val y = i / WIDTH
            val r = if (reverse) 255 - x * 255 / WIDTH else x * 255 / WIDTH
            val g = y * 255 / HEIGHT
            val b = if ((x / 80 + y / 60) % 2 == 0) 210 else 70
            Color.rgb(r, g, b)
        })
    }
}

fun Frame.bitmap(): Bitmap = Bitmap.createBitmap(pixels(), width, height, Bitmap.Config.ARGB_8888)
