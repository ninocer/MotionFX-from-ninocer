package dev.motionfx.plugins

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream

class PluginStore(context: Context) {
    private val root=File(context.filesDir,"plugins").apply { mkdirs() }
    private val preferences=context.getSharedPreferences("plugins",Context.MODE_PRIVATE)
    fun list(): List<PluginManifest> = root.listFiles().orEmpty().filter{it.extension=="json"}.map { PluginApi.parse(it.readText()) }
    fun enabled(id: String) = preferences.getBoolean(id,true)
    fun enable(id: String, enabled: Boolean) { preferences.edit().putBoolean(id,enabled).apply() }
    fun install(input: InputStream): PluginManifest {
        val manifest=PluginApi.read(input)
        val file=AtomicFile(File(root,"${manifest.id}.json")); val stream=file.startWrite()
        try { stream.write(PluginApi.encode(manifest).toByteArray()); file.finishWrite(stream) }
        catch(t: Throwable) { file.failWrite(stream); throw t }
        return manifest
    }
    fun remove(m: PluginManifest) { AtomicFile(File(root,"${m.id}.json")).delete(); preferences.edit().remove(m.id).apply() }
}
