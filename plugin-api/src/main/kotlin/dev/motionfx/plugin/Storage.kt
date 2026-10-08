package dev.motionfx.plugin

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Same-directory replace: readers see the old complete file or the new complete file. */
internal object AtomicStorage {
    fun write(file: File, bytes: ByteArray, keepBackup: Boolean = false) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (file.exists() && !keepBackup) {
                val backupTemp = File(file.path + ".bak.tmp")
                FileOutputStream(backupTemp).use { out -> file.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                Files.move(backupTemp.toPath(), File(file.path + ".bak").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun <T> read(file: File, decode: (ByteArray) -> T): T? {
        var failure: Exception? = null
        for (candidate in listOf(file, File(file.path + ".bak"))) {
            if (!candidate.exists()) continue
            try {
                val bytes = candidate.inputStream().use { boundedRead(it, 1_048_576) }
                val value = decode(bytes)
                if (candidate != file) write(file, bytes, keepBackup = true)
                return value
            }
            catch (e: Exception) { failure = e }
        }
        if (failure != null) throw PluginException("Cannot recover ${file.name}; files were preserved", failure)
        return null
    }
}

@Serializable
data class ProjectDocument(
    val schemaVersion: Int = 1,
    val name: String = "MotionFX composition",
    val durationSeconds: Float = 3f,
    val fps: Int = 24,
    val mediaFile: String? = null,
    val plugins: List<PluginReference> = emptyList(),
)

class ProjectStore(private val file: File) {
    @Synchronized fun save(project: ProjectDocument) {
        validate(project)
        AtomicStorage.write(file, PluginApi.json.encodeToString(project).toByteArray())
    }
    @Synchronized fun load(): ProjectDocument? = AtomicStorage.read(file) {
        PluginApi.json.decodeFromString<ProjectDocument>(checkedJson(it)).also(::validate)
    }
    private fun validate(p: ProjectDocument) {
        require(p.schemaVersion == 1 && p.name.length <= 160)
        require(finite(p.durationSeconds) && p.durationSeconds in 0.1f..60f && p.fps in 1..60)
        require(p.mediaFile == null || Regex("image-[a-f0-9-]+\\.png").matches(p.mediaFile))
        require(p.plugins.size <= 16)
        p.plugins.forEach {
            require(validId(it.pluginId) && validId(it.contributionId)); Version.parse(it.version)
            require(it.parameters.size <= 16 && it.parameters.all { (key, value) -> validId(key) && finite(value) })
        }
    }
}
