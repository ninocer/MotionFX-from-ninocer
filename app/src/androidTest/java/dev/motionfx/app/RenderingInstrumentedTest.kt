package dev.motionfx.app

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.motionfx.plugin.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RenderingInstrumentedTest {
    @Test fun generatedGpuShadersMatchCpuIncludingAlphaAndOrientation() {
        val pixels = IntArray(8 * 6) { i -> ((80 + i * 3) shl 24) or ((i * 5) shl 16) or ((200 - i * 3) shl 8) or (i * 4) }
        val frame = Frame(8, 6, pixels)
        GlRenderer(8, 6).use { gl ->
            for (type in OperationType.entries) {
                val operations = listOf(ResolvedOperation(type, .6f, 1f, .7f, .3f))
                val cpu = CpuOperations.apply(frame, operations).pixels()
                val gpu = gl.apply(frame, operations).pixels()
                assertPixelsClose(cpu, gpu, 1)
            }
        }
    }

    @Test fun packagePersistsRendersExportsAndDecodesActualMp4() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}").apply { mkdirs() }
        var uri: android.net.Uri? = null
        try {
            val manager = PluginManager(File(root, "plugins"))
            context.assets.open("starter.mfxplugin").use { manager.install(it) }
            manager.enable("dev.motionfx.starter")
            val references = listOf(
                PluginReference("dev.motionfx.starter", "1.0.0", "warm"),
                PluginReference("dev.motionfx.starter", "1.0.0", "vignette"),
                PluginReference("dev.motionfx.starter", "1.0.0", "fade-in"),
            )
            val project = ProjectDocument(durationSeconds = .5f, fps = 24, plugins = references)
            val store = ProjectStore(File(root, "project.mfx")); store.save(project)
            val source = EditorViewModel.demoFrame(); val second = EditorViewModel.demoFrame(true)
            var progress = 0f
            uri = VideoExporter.export(context, manager, project, source, second, { progress = it }, { assertTrue(it.toString(), it.isEmpty()) })
            assertEquals(1f, progress)
            assertEquals(project, store.load())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri!!, null)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(source.width, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(source.height, format.getInteger(MediaFormat.KEY_HEIGHT))
                extractor.selectTrack(0)
                var frames = 0; var lastTimestamp = -1L
                while (extractor.sampleTime >= 0) {
                    assertTrue(extractor.sampleTime > lastTimestamp)
                    lastTimestamp = extractor.sampleTime; frames++
                    if (!extractor.advance()) break
                }
                assertEquals(12, frames)
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri!!)
                val bitmap = checkNotNull(retriever.getFrameAtTime(250_000, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    assertEquals(source.width, bitmap.width); assertEquals(source.height, bitmap.height)
                    // The first frame of the fade is black, the quarter-second frame must contain actual image data.
                    assertTrue(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff != 0)
                } finally { bitmap.recycle() }
            } finally { retriever.release() }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            root.deleteRecursively()
        }
    }

    private fun assertPixelsClose(expected: IntArray, actual: IntArray, tolerance: Int) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { i ->
            for (shift in listOf(0, 8, 16, 24)) {
                assertTrue("Pixel $i channel $shift: ${expected[i]} != ${actual[i]}",
                    abs(((expected[i] ushr shift) and 255) - ((actual[i] ushr shift) and 255)) <= tolerance)
            }
        }
    }
}
