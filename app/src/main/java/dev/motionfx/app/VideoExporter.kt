package dev.motionfx.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import dev.motionfx.plugin.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File

class LazyGpu(private val width: Int, private val height: Int) : GpuBackend, Closeable {
    private var renderer: GlRenderer? = null
    private var failure: Exception? = null
    override fun apply(frame: Frame, operations: List<ResolvedOperation>): Frame {
        failure?.let { throw it }
        try {
            val gl = renderer ?: GlRenderer(width, height).also { renderer = it }
            return gl.apply(frame, operations)
        } catch (e: Exception) { failure = e; throw e }
    }
    override fun close() { renderer?.close(); renderer = null }
}

object VideoExporter {
    /** Encodes a real H.264 MP4. All frames use FrameEngine, exactly like preview. */
    suspend fun export(
        context: Context,
        manager: PluginManager,
        project: ProjectDocument,
        input: Frame,
        second: Frame,
        onProgress: (Float) -> Unit,
        onIssues: (List<PluginIssue>) -> Unit,
    ): Uri {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, input.width, input.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, project.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("No H.264 encoder supports this composition")
        val temporary = File.createTempFile("motionfx-", ".mp4", context.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var outputSurface: android.view.Surface? = null
        var encoderGl: GlRenderer? = null
        var gpu: LazyGpu? = null
        var muxerStarted = false
        var codecStarted = false
        try {
            val encoder = MediaCodec.createByCodecName(name).also { codec = it }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface().also { outputSurface = it }
            encoderGl = GlRenderer(input.width, input.height, surface)
            encoder.start(); codecStarted = true
            val writer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val effectsGpu = LazyGpu(input.width, input.height).also { gpu = it }
            val engine = FrameEngine(manager, effectsGpu)
            val bufferInfo = MediaCodec.BufferInfo()
            var track = -1
            var eos = false
            val coroutine = currentCoroutineContext()
            fun drain(final: Boolean) {
                val deadline = System.nanoTime() + 30_000_000_000L
                do {
                    coroutine.ensureActive()
                    val index = encoder.dequeueOutputBuffer(bufferInfo, if (final) 10_000 else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!final) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed twice" }
                            track = writer.addTrack(encoder.outputFormat); writer.start(); muxerStarted = true
                        }
                        index >= 0 -> {
                            try {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size > 0) {
                                    check(muxerStarted)
                                    val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                    buffer.position(bufferInfo.offset); buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    writer.writeSampleData(track, buffer, bufferInfo)
                                }
                                eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { encoder.releaseOutputBuffer(index, false) }
                        }
                    }
                    check(System.nanoTime() < deadline) { "Encoder output timed out" }
                } while (!eos)
            }
            val count = (project.durationSeconds * project.fps).toInt().coerceAtLeast(1)
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                drain(false)
                val time = i.toFloat() / project.fps
                val result = engine.render(input, project.plugins, time, time / project.durationSeconds, second, RenderPurpose.EXPORT)
                if (result.issues.isNotEmpty()) onIssues(result.issues)
                encoderGl.encode(result.frame, i.toLong() * 1_000_000_000L / project.fps)
                onProgress((i + 1f) / count)
            }
            encoder.signalEndOfInputStream()
            drain(true)
            check(muxerStarted && temporary.length() > 0) { "Encoder produced no video" }
            writer.stop(); muxerStarted = false
            currentCoroutineContext().ensureActive()
            return publish(context, temporary)
        } finally {
            // A cancellation or plugin/backend failure cannot leave a partial public video or alter the project.
            runCatching { gpu?.close() }; runCatching { encoderGl?.close() }
            runCatching { if (codecStarted) codec?.stop() }; runCatching { codec?.release() }
            runCatching { outputSurface?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }; runCatching { muxer?.release() }
            temporary.delete()
        }
    }

    private fun publish(context: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MotionFX-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotionFX")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}
