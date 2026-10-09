package dev.motionfx.plugins

import dev.motionfx.core.Effect
import dev.motionfx.core.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.ZipInputStream

@Serializable data class PluginManifest(
    val api: Int = 1, val id: String, val name: String, val version: String,
    val description: String = "", val minAppVersion: Int = 1,
    val dependencies: List<String> = emptyList(), val effects: List<PluginEffect>)
@Serializable data class PluginEffect(val kind: String, val amount: Float)
object PluginApi {
    const val API_VERSION = 1
    val supportedEffects = setOf("brightness", "contrast", "saturation", "tint")
    private val json = Json { encodeDefaults = true }
    fun parse(text: String): PluginManifest {
        require(text.length <= 65536) { "Manifest too large" }
        return json.decodeFromString(PluginManifest.serializer(),text).also { m ->
            require(m.api == API_VERSION && m.minAppVersion <= 1) { "Incompatible plugin API" }
            require(m.id.matches(Regex("[a-z][a-z0-9.-]{2,63}"))) { "Invalid plugin ID" }
            require(m.name.isNotBlank() && m.name.length<=80 && m.description.length<=2048)
            require(m.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            require(m.dependencies.isEmpty()) { "Dependencies are not supported by API 1" }
            require(m.effects.size in 1..8)
            m.effects.forEach { require(it.kind in supportedEffects && it.amount.isFinite() && it.amount in -1f..1f) { "Unsupported effect" } }
        }
    }
    fun read(input: InputStream): PluginManifest = ZipInputStream(input).use { zip ->
        var manifest: PluginManifest?=null; var entry=zip.nextEntry; var count=0
        while(entry!=null) {
            require(++count==1 && entry.name=="manifest.json" && !entry.isDirectory) { "API 1 permits only manifest.json; code and shaders are rejected" }
            val bytes=zip.readNBytes(65537); require(bytes.size<=65536)
            manifest=parse(bytes.toString(Charsets.UTF_8)); zip.closeEntry(); entry=zip.nextEntry
        }
        requireNotNull(manifest) { "Missing manifest.json" }
    }
    fun encode(m: PluginManifest) = json.encodeToString(PluginManifest.serializer(),m)
    fun instantiate(m: PluginManifest) = m.effects.map { Effect(it.kind, Track(it.amount), pluginId=m.id) }
}
