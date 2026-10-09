package dev.motionfx

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.motionfx.core.*
import dev.motionfx.render.*
import dev.motionfx.storage.ProjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun effectsChangeActualPixelsAndLayersComposite() {
        val p=Project(width=320,height=240,layers=listOf(Layer(color=Color.rgb(100,100,100))))
        FrameRenderer(context).use{r->
            val a=r.render(p,0);val b=r.render(p.copy(layers=p.layers.map{it.copy(effects=listOf(Effect("brightness",Track(.2f))))}),0)
            assertTrue(Color.red(b.getPixel(160,120))>Color.red(a.getPixel(160,120))+40)
            val hidden=r.render(p.copy(layers=p.layers.map{it.copy(visible=false)}),0)
            assertEquals(p.background,hidden.getPixel(160,120));a.recycle();b.recycle();hidden.recycle()
        }
    }
    @Test fun atomicProjectRoundTrip() {
        val store=ProjectStore(context);val p=Project(name="Recovery test")
        try{store.save(p);store.save(p.copy(name="Updated"));assertEquals("Updated",store.load(p.id).name)}finally{store.delete(p.id)}
    }
    @Test fun mp4ContainsDecodableTimedFramesAndMatchesPreview()= runBlocking {
        val p=Project(width=320,height=240,duration=1000,fps=24,background=Color.BLACK,
            layers=listOf(Layer(end=1000,color=Color.RED,transform=Transform(x=Track().key(0,.25f).key(999,.75f)))))
        val file=File(context.cacheDir,"instrumented-export.mp4")
        try {
            VideoExporter(context).encode(p,ExportSettings(320,240,24,1_000_000),file){}
            assertTrue(file.length()>100)
            val ex=MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath);assertEquals(1,ex.trackCount)
                assertEquals("video/avc",ex.getTrackFormat(0).getString(MediaFormat.KEY_MIME));ex.selectTrack(0)
                var count=0;var last=-1L;val buffer=ByteBuffer.allocate(1024*1024)
                while(ex.readSampleData(buffer,0)>=0){assertTrue(ex.sampleTime>last);last=ex.sampleTime;count++;ex.advance()}
                assertEquals(24,count)
            }finally{ex.release()}
            MediaMetadataRetriever().use{r->r.setDataSource(file.absolutePath)
                val frame=checkNotNull(r.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST))
                assertTrue(Color.red(frame.getPixel(80,120))>180)
                assertTrue(Color.red(frame.getPixel(280,120))<40)
                frame.recycle()
            }
        }finally{file.delete()}
    }
}
