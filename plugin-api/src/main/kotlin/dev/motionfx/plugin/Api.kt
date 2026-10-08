package dev.motionfx.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** MotionFX's data-only extension contract. No plugin class loaders or executable entry points. */
object PluginApi {
    val version = Version.parse("1.0.0")
    val applicationVersion = Version.parse("0.1.0")
    val json = Json { ignoreUnknownKeys = false; isLenient = false; encodeDefaults = true }
    const val MAX_PACKAGE_BYTES = 262_144
    const val MAX_CONTENT_BYTES = 131_072
    const val MAX_MANIFEST_BYTES = 32_768
    const val MAX_FRAME_PIXELS = 4_194_304
}

data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(text: String): Version {
            require(Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})").matches(text)) { "Expected a stable major.minor.patch version: $text" }
            val n = text.split('.').map(String::toInt)
            return Version(n[0], n[1], n[2])
        }
    }
}

@Serializable
data class VersionRange(val min: String, val maxExclusive: String) {
    fun validate() { require(Version.parse(min) < Version.parse(maxExclusive)) { "Empty version range" } }
    operator fun contains(version: Version): Boolean = version >= Version.parse(min) && version < Version.parse(maxExclusive)
}

@Serializable
data class Dependency(val id: String, val versions: VersionRange)

@Serializable
data class PluginManifest(
    val formatVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val api: VersionRange,
    val application: VersionRange,
    val dependencies: List<Dependency> = emptyList(),
    val contentSha256: String,
)

@Serializable enum class ContributionKind { EFFECT, TRANSITION, PRESET, GPU_SHADER }
@Serializable enum class OperationType { TINT, EXPOSURE, CONTRAST, INVERT, VIGNETTE }
@Serializable enum class Interpolation { LINEAR, HOLD, EASE_IN_OUT }
@Serializable data class Parameter(val id: String, val name: String, val min: Float, val max: Float, val default: Float)
@Serializable data class Scalar(val value: Float = 0f, val parameter: String? = null)
@Serializable data class Operation(val type: OperationType, val amount: Scalar, val color: List<Float> = listOf(1f, 1f, 1f))
@Serializable data class Keyframe(val timeSeconds: Float, val value: Float, val interpolation: Interpolation = Interpolation.LINEAR)
@Serializable
data class Contribution(
    val id: String,
    val name: String,
    val kind: ContributionKind,
    val parameters: List<Parameter> = emptyList(),
    val operations: List<Operation> = emptyList(),
    val keyframes: List<Keyframe> = emptyList(),
)
@Serializable data class PluginContent(val contributions: List<Contribution>)
data class PluginPackage(val manifest: PluginManifest, val content: PluginContent, val sha256: String)

/** Project references survive missing, disabled, quarantined, or incompatible plugins. */
@Serializable
data class PluginReference(
    val pluginId: String,
    val version: String,
    val contributionId: String,
    val parameters: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true,
)

data class PluginIssue(val pluginId: String, val message: String)
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun validId(id: String) = Regex("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*").matches(id) && id.length <= 96
internal fun finite(value: Float) = value.isFinite()
