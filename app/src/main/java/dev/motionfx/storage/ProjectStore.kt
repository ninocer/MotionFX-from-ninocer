package dev.motionfx.storage

import android.content.Context
import android.util.AtomicFile
import dev.motionfx.core.*
import java.io.File

class ProjectStore(context: Context) {
    private val root = File(context.filesDir,"projects").apply { mkdirs() }
    @Synchronized fun save(project: Project) {
        val bytes=ProjectFormat.encode(project).toByteArray(Charsets.UTF_8)
        check(root.usableSpace > bytes.size * 3L + 1024*1024) { "Not enough storage / Недостаточно места" }
        val file = AtomicFile(File(root,"${project.id}.mfx"))
        val stream=file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch(t: Throwable) { file.failWrite(stream); throw t }
    }
    @Synchronized fun list(): List<Pair<String,String>> = root.listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.filter { it.endsWith(".mfx") }.distinct()
        .map { name -> name.removeSuffix(".mfx") to runCatching { load(name.removeSuffix(".mfx")).name }.getOrDefault("⚠ $name") }
    @Synchronized fun load(id: String): Project {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return ProjectFormat.decode(AtomicFile(File(root,"$id.mfx")).openRead().bufferedReader().use { it.readText() })
    }
    @Synchronized fun delete(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        AtomicFile(File(root,"$id.mfx")).delete()
    }
}
