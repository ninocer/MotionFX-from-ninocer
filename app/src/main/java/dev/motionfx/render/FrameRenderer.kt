package dev.motionfx.render

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.motionfx.core.*
import java.io.Closeable

/** Shared reference compositor for preview, PNG and MP4. Bounded media caches; no hidden redraw loop. */
class FrameRenderer(private val context: Context): Closeable {
    private val videos=LinkedHashMap<String,MediaMetadataRetriever>(4, .75f, true)
    private val images=LinkedHashMap<String,Bitmap>(4,.75f,true)
    private var imageSize=0
    @Synchronized fun render(p: Project, time: Long, width: Int=p.width, height: Int=p.height): Bitmap {
        val frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val c=Canvas(frame); c.drawColor(p.background)
            for(l in p.layers) {
                if(!l.active(time)) continue
                val bitmap=when(l.kind) {
                    LayerKind.IMAGE -> image(l.source,maxOf(width,height))
                    LayerKind.VIDEO -> video(l.source).getScaledFrameAtTime(l.mediaTime(time)*1000,
                        MediaMetadataRetriever.OPTION_CLOSEST, width,height)
                        ?: error("Cannot decode video: ${l.name}")
                    else -> null
                }
                try {
                    val t=l.transform
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha=(t.opacity.at(time).coerceIn(0f,1f)*255).toInt()
                        colorFilter=filter(l.effects,time)
                    }
                    c.save(); c.translate(t.x.at(time)*width,t.y.at(time)*height)
                    c.rotate(t.rotation.at(time)); val scale=t.scale.at(time).coerceIn(.01f,10f); c.scale(scale,scale)
                    if(bitmap!=null) {
                        val fit=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
                        val w=bitmap.width*fit; val h=bitmap.height*fit
                        c.drawBitmap(bitmap,null,RectF(-w/2,-h/2,w/2,h/2),paint)
                    } else {
                        paint.color=l.color; paint.alpha=(t.opacity.at(time).coerceIn(0f,1f)*255).toInt()
                        if(l.kind==LayerKind.TEXT) {
                            paint.textSize=width*.075f; paint.typeface=Typeface.create("sans-serif",Typeface.BOLD)
                            paint.textAlign=Paint.Align.CENTER
                            c.drawText(l.text,0f,-(paint.ascent()+paint.descent())/2,paint)
                        } else c.drawRoundRect(RectF(-width*.22f,-height*.22f,width*.22f,height*.22f),width*.02f,width*.02f,paint)
                    }
                    c.restore()
                } finally { if(l.kind==LayerKind.VIDEO) bitmap?.recycle() }
            }
            return frame
        } catch(t: Throwable) { frame.recycle(); throw t }
    }
    private fun video(uri: String): MediaMetadataRetriever = videos.getOrPut(uri) {
        if(videos.size>=2) { val first=videos.keys.first(); videos.remove(first)?.release() }
        MediaMetadataRetriever().apply { try { setDataSource(context,Uri.parse(uri)) } catch(t: Throwable) { release(); throw t } }
    }
    private fun image(uri: String, size: Int): Bitmap {
        if(imageSize!=size) { images.values.forEach{it.recycle()}; images.clear(); imageSize=size }
        return images.getOrPut(uri) {
            if(images.size>=3) images.remove(images.keys.first())?.recycle()
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,Uri.parse(uri))) { decoder,info,_ ->
                val ratio=minOf(1f,size.toFloat()/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }
    @Synchronized override fun close() { videos.values.forEach{it.release()}; videos.clear(); images.values.forEach{it.recycle()}; images.clear() }
    companion object {
        fun filter(effects: List<Effect>,time: Long): ColorMatrixColorFilter? {
            val total=ColorMatrix(); var applied=false
            for(e in effects.filter{it.enabled}) {
                val a=e.amount.at(time).coerceIn(-1f,1f)
                val m=when(e.kind) {
                    "saturation" -> ColorMatrix().apply { setSaturation(1+a) }
                    "brightness" -> ColorMatrix(floatArrayOf(1f,0f,0f,0f,a*255, 0f,1f,0f,0f,a*255, 0f,0f,1f,0f,a*255, 0f,0f,0f,1f,0f))
                    "contrast" -> { val s=1+a; val b=128*(1-s); ColorMatrix(floatArrayOf(s,0f,0f,0f,b,0f,s,0f,0f,b,0f,0f,s,0f,b,0f,0f,0f,1f,0f)) }
                    "tint" -> ColorMatrix(floatArrayOf(1f,0f,0f,0f,a*25,0f,1f,0f,0f,-a*15,0f,0f,1f,0f,a*40,0f,0f,0f,1f,0f))
                    else -> error("Unsupported effect: ${e.kind}")
                }
                total.postConcat(m); applied=true
            }
            return if(applied) ColorMatrixColorFilter(total) else null
        }
    }
}
