package dev.motionfx.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString

object PackageReader {
    fun read(stream: InputStream): Pair<PluginPackage, ByteArray> {
        val bytes = boundedRead(stream, PluginApi.MAX_PACKAGE_BYTES)
        return decode(bytes) to bytes
    }

    fun decode(bytes: ByteArray): PluginPackage = try {
        require(bytes.size <= PluginApi.MAX_PACKAGE_BYTES) { "Package exceeds 256 KiB" }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.json", "content.json") && !entry.isDirectory) {
                    "Only manifest.json and content.json are allowed; executable files and raw shaders are forbidden"
                }
                require(entry.name !in entries) { "Duplicate ZIP entry" }
                val limit = if (entry.name == "manifest.json") PluginApi.MAX_MANIFEST_BYTES else PluginApi.MAX_CONTENT_BYTES
                entries[entry.name] = boundedRead(zip, limit)
                zip.closeEntry() // also verifies ZIP CRC
            }
        }
        require(entries.keys == setOf("manifest.json", "content.json")) { "Incomplete package" }
        val manifest = PluginApi.json.decodeFromString<PluginManifest>(checkedJson(entries.getValue("manifest.json")))
        val contentBytes = entries.getValue("content.json")
        require(sha256(contentBytes) == manifest.contentSha256) { "Content checksum mismatch" }
        val content = PluginApi.json.decodeFromString<PluginContent>(checkedJson(contentBytes))
        validate(manifest, content)
        PluginPackage(manifest, content, sha256(bytes))
    } catch (e: Exception) {
        throw PluginException("Invalid .mfxplugin: ${e.message}", e)
    }

    fun checkCompatibility(manifest: PluginManifest, app: Version = PluginApi.applicationVersion) {
        require(PluginApi.version in manifest.api) { "Plugin requires API ${manifest.api}" }
        require(app in manifest.application) { "Plugin requires application ${manifest.application}" }
    }

    private fun validate(m: PluginManifest, c: PluginContent) {
        validateManifest(m)
        validateContent(c)
    }

    internal fun validateManifest(m: PluginManifest) {
        require(m.formatVersion == 1) { "Unsupported package format" }
        require(validId(m.id)) { "Invalid plugin id" }
        require(m.name.isNotBlank() && m.name.length <= 80 && m.description.length <= 2048 && m.author.length in 1..120)
        Version.parse(m.version)
        m.api.validate(); m.application.validate()
        require(Regex("[0-9a-f]{64}").matches(m.contentSha256))
        require(m.dependencies.size <= 16 && m.dependencies.map { it.id }.distinct().size == m.dependencies.size)
        m.dependencies.forEach { require(validId(it.id) && it.id != m.id); it.versions.validate() }
    }

    private fun validateContent(c: PluginContent) {
        require(c.contributions.size in 1..16 && c.contributions.map { it.id }.distinct().size == c.contributions.size)
        c.contributions.forEach { item ->
            require(validId(item.id) && item.name.isNotBlank() && item.name.length <= 80)
            require(item.parameters.size <= 16 && item.parameters.map { it.id }.distinct().size == item.parameters.size)
            item.parameters.forEach { p ->
                require(validId(p.id) && p.name.length in 1..80)
                require(listOf(p.min, p.max, p.default).all(::finite) && p.min >= -4 && p.max <= 4 && p.min < p.max && p.default in p.min..p.max)
            }
            when (item.kind) {
                ContributionKind.EFFECT, ContributionKind.GPU_SHADER -> require(item.operations.size in 1..8 && item.keyframes.isEmpty())
                ContributionKind.TRANSITION -> require(item.operations.isEmpty() && item.keyframes.isEmpty() && item.parameters.isEmpty())
                ContributionKind.PRESET -> {
                    require(item.operations.isEmpty() && item.parameters.isEmpty() && item.keyframes.size in 2..128)
                    require(item.keyframes.first().timeSeconds == 0f)
                    item.keyframes.forEach { require(finite(it.timeSeconds) && it.timeSeconds in 0f..60f && finite(it.value) && it.value in 0f..1f) }
                    require(item.keyframes.zipWithNext().all { (a, b) -> a.timeSeconds < b.timeSeconds })
                }
            }
            item.operations.forEach { op ->
                require(finite(op.amount.value) && op.color.size == 3 && op.color.all { finite(it) && it in 0f..1f })
                val allowed = when (op.type) {
                    OperationType.EXPOSURE -> -4f..4f
                    OperationType.CONTRAST -> 0f..2f
                    else -> 0f..1f
                }
                val parameter = op.amount.parameter
                if (parameter != null) {
                    val p = item.parameters.singleOrNull { it.id == parameter } ?: error("Unknown operation parameter")
                    require(p.min in allowed && p.max in allowed) { "Parameter outside operation bounds" }
                    require(op.amount.value == 0f) { "Use a literal or a parameter, not both" }
                } else require(op.amount.value in allowed) { "Operation value outside bounds" }
            }
        }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun boundedRead(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (n < 0) break
        if (n == 0) continue
        out.write(buffer, 0, n)
        require(out.size() <= limit) { "Size limit exceeded" }
    }
    return out.toByteArray()
}

/** Bound parser nesting before asking the JSON decoder to allocate recursive structures. */
internal fun checkedJson(bytes: ByteArray): String {
    val text = bytes.toString(Charsets.UTF_8)
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 32) { "JSON nesting limit exceeded" } }
            '}', ']' -> depth--
        }
    }
    return text
}
