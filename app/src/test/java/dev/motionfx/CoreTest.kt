package dev.motionfx

import dev.motionfx.core.*
import dev.motionfx.plugins.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    @Test fun interpolationAndBoundaries() {
        val track=Track().key(0,0f).key(1000,10f)
        assertEquals(5f,track.at(500),.00001f)
        assertEquals(0f,track.at(-100),0f)
        assertEquals(10f,track.at(2000),0f)
        assertEquals(7f,track.key(500,3f).key(500,7f).at(500),0f)
        assertEquals(3,track.key(500,3f).key(500,7f).keys.size)
    }
    @Test fun allEasingsAreDeterministic() {
        Easing.entries.forEach{ease->val track=Track().key(0,2f,ease).key(1000,10f)
            val values=(0L..1000L step 5).map{track.at(it)}
            assertEquals(values,(0L..1000L step 5).map{track.at(it)})
            assertTrue(values.all{it in 2f..10f})
        }
        assertEquals(2f,Track().key(0,2f,Easing.HOLD).key(1000,10f).at(999),0f)
        assertEquals(4f,Track().key(0,2f,Easing.EASE_IN).key(1000,10f).at(500),.001f)
    }
    @Test fun bezierSolvesTimeAxis() {
        val track=Track(keys=listOf(Keyframe(0,0f,Easing.BEZIER,0f,0f,1f,1f),Keyframe(1000,1f)))
        assertEquals(.25f,track.at(250),.0001f)
    }
    @Test fun splitPreservesSourceAndAnimation() {
        val l=Layer(kind=LayerKind.VIDEO,start=100,end=3000,sourceIn=300,speed=2f,
            transform=Transform(x=Track().key(0,0f).key(5000,1f)))
        val p=Project(layers=listOf(l)).split(l.id,1000)
        assertEquals(2100L,p.layers[1].sourceIn)
        assertFalse(p.layers[0].active(1000));assertTrue(p.layers[1].active(1000))
        assertEquals(l.transform.x.at(1500),p.layers[1].transform.x.at(1500),0f)
        assertEquals(l.mediaTime(1500),p.layers[1].mediaTime(1500))
    }
    @Test fun roundTripUnicodeAndPluginSnapshot() {
        val p=Project(name="Тест ✨",layers=listOf(Layer(text="Привет",effects=listOf(Effect("tint",Track(.2f),pluginId="test.fx")),
            transform=Transform(rotation=Track().key(0,0f).key(4000,180f)))))
        assertEquals(p,ProjectFormat.decode(ProjectFormat.encode(p)))
    }
    @Test fun historyUndoRedoAndBranch() {
        val h=History();val a=Project();val b=a.copy(name="B");h.push(a)
        assertEquals(a,h.undo(b));assertEquals(b,h.redo(a));h.push(b);h.undo(a);h.push(a)
        assertEquals(a,h.redo(a))
    }
    @Test fun projectRejectsUnsafeOrUnsupportedInput() {
        listOf(Project(id="../escape"),Project(version=2),Project(width=15),Project(fps=120),
            Project(layers=listOf(Layer(end=6000))),Project(layers=listOf(Layer(transform=Transform(x=Track(Float.NaN)))))).forEach {
            assertThrows(IllegalArgumentException::class.java){it.validated()}
        }
    }
    @Test fun pluginManifestAndPackage() {
        val m=PluginManifest(id="example.violet",name="Violet",version="1.0.0",effects=listOf(PluginEffect("tint",.5f)))
        assertEquals(m,PluginApi.parse(PluginApi.encode(m)))
        val bytes=pack("manifest.json",PluginApi.encode(m))
        assertEquals(m,PluginApi.read(ByteArrayInputStream(bytes)))
        assertEquals("example.violet",PluginApi.instantiate(m).single().pluginId)
        assertThrows(IllegalArgumentException::class.java){PluginApi.parse(PluginApi.encode(m.copy(api=99)))}
        assertThrows(IllegalArgumentException::class.java){PluginApi.parse(PluginApi.encode(m.copy(effects=listOf(PluginEffect("code",1f)))))}
        assertThrows(IllegalArgumentException::class.java){PluginApi.read(ByteArrayInputStream(pack("../manifest.json",PluginApi.encode(m))))}
        assertThrows(IllegalArgumentException::class.java){PluginApi.read(ByteArrayInputStream(pack("classes.dex","evil")))}
        assertThrows(IllegalArgumentException::class.java){PluginApi.read(ByteArrayInputStream(pack("manifest.json","x".repeat(65537))))}
    }
    private fun pack(name:String,text:String):ByteArray { val out=ByteArrayOutputStream();ZipOutputStream(out).use{it.putNextEntry(ZipEntry(name));it.write(text.toByteArray());it.closeEntry()};return out.toByteArray() }
}
