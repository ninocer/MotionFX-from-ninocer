package dev.motionfx.render

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.motionfx.core.Project
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.coroutineContext

data class ExportSettings(val width: Int, val height: Int, val fps: Int, val bitrate: Int=8_000_000,
    val mime: String=MediaFormat.MIMETYPE_VIDEO_AVC)
class VideoExporter(private val context: Context) {
    suspend fun export(project: Project, settings: ExportSettings, progress: (Float)->Unit): Uri = withContext(Dispatchers.Default) {
        val file=File.createTempFile("export-",".mp4",context.cacheDir)
        try { encode(project,settings,file,progress); coroutineContext.ensureActive(); publish(file,"video/mp4","mp4") }
        finally { file.delete() }
    }
    suspend fun encode(p: Project,s: ExportSettings,file: File,progress:(Float)->Unit) {
        p.validated()
        require(s.width in 16..3840 && s.height in 16..3840 && s.width%2==0 && s.height%2==0)
        require(s.fps in listOf(24,30,60) && s.bitrate in 1_000_000..80_000_000)
        val needed=s.bitrate.toLong()*p.duration/8000+16*1024*1024
        check(context.cacheDir.usableSpace > needed*2) { "Not enough storage / Недостаточно места" }
        val format=MediaFormat.createVideoFormat(s.mime,s.width,s.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,s.bitrate); setInteger(MediaFormat.KEY_FRAME_RATE,s.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val codecs=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.contains(s.mime) }
        val suitable=codecs.filter { runCatching {
            val caps=it.getCapabilitiesForType(s.mime)
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && caps.isFormatSupported(format)
        }.getOrDefault(false) }.sortedByDescending { it.isHardwareAccelerated }
        val info=suitable.firstOrNull() ?: error("Encoder does not support selected size/FPS/codec. Try 720p / 30 FPS.")
        val codec=MediaCodec.createByCodecName(info.name)
        var muxer: MediaMuxer?=null; var egl: EncoderSurface?=null; var input: android.view.Surface?=null
        var codecStarted=false; var muxStarted=false; var track=-1
        val bufferInfo=MediaCodec.BufferInfo()
        var eos=false
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            input=codec.createInputSurface(); codec.start(); codecStarted=true
            val encoderSurface=EncoderSurface(input,s.width,s.height); egl=encoderSurface
            val output=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer=output
            suspend fun drain(waitForEnd: Boolean) {
                var deadline=System.nanoTime()+30_000_000_000L
                while(true) {
                    coroutineContext.ensureActive()
                    when(val index=codec.dequeueOutputBuffer(bufferInfo,if(waitForEnd) 10000L else 0L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> { if(!waitForEnd) return; check(System.nanoTime()<deadline) { "Encoder timed out" } }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxStarted); track=output.addTrack(codec.outputFormat); output.start(); muxStarted=true
                        }
                        else -> if(index>=0) {
                            try {
                                val buffer=checkNotNull(codec.getOutputBuffer(index))
                                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size>0) {
                                    check(muxStarted); buffer.position(bufferInfo.offset); buffer.limit(bufferInfo.offset+bufferInfo.size)
                                    output.writeSampleData(track,buffer,bufferInfo)
                                }
                                eos=bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { codec.releaseOutputBuffer(index,false) }
                            deadline=System.nanoTime()+30_000_000_000L
                            if(eos) return
                        }
                    }
                }
            }
            FrameRenderer(context).use { renderer ->
                val count=(p.duration*s.fps+999)/1000
                for(i in 0 until count) {
                    coroutineContext.ensureActive(); drain(false)
                    val bitmap=renderer.render(p,i*1000/s.fps,s.width,s.height)
                    try { encoderSurface.draw(bitmap,i*1_000_000_000L/s.fps) } finally { bitmap.recycle() }
                    drain(false); progress((i+1).toFloat()/count)
                }
            }
            codec.signalEndOfInputStream(); drain(true); check(eos && muxStarted)
            output.stop(); muxStarted=false
        } finally {
            runCatching { egl?.close() }; runCatching { input?.release() }
            if(codecStarted) runCatching { codec.stop() }; codec.release()
            if(muxStarted) runCatching { muxer?.stop() }; muxer?.release()
        }
    }
    fun publish(file: File,mime: String,extension: String): Uri {
        val resolver=context.contentResolver
        val collection=if(mime.startsWith("image")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"MotionFX-${System.currentTimeMillis()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,if(mime.startsWith("image")) "${Environment.DIRECTORY_PICTURES}/MotionFX" else "${Environment.DIRECTORY_MOVIES}/MotionFX")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=checkNotNull(resolver.insert(collection,values)) { "Cannot create output" }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use{it.copyTo(out)} }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING,0); resolver.update(uri,values,null,null)
            return uri
        } catch(t: Throwable) { resolver.delete(uri,null,null); throw t }
    }
}
