package dev.motionfx.plugin

import kotlin.math.pow
import kotlin.math.roundToInt

/** Straight-alpha, encoded sRGB ARGB8. Callers cannot mutate the engine's input buffer. */
class Frame(val width: Int, val height: Int, pixels: IntArray) {
    internal val data: IntArray
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= PluginApi.MAX_FRAME_PIXELS)
        require(pixels.size == width * height)
        data = pixels.copyOf()
    }
    fun pixels(): IntArray = data.copyOf()
}

data class ResolvedOperation(val type: OperationType, val amount: Float, val red: Float, val green: Float, val blue: Float)
fun interface GpuBackend { fun apply(frame: Frame, operations: List<ResolvedOperation>): Frame }
enum class RenderPurpose { PREVIEW, EXPORT }
data class RenderResult(val frame: Frame, val issues: List<PluginIssue>)

/** Both preview and export call this exact pipeline at explicit composition times. No wall-clock state. */
class FrameEngine(private val plugins: PluginManager, private val gpu: GpuBackend? = null) {
    @Suppress("UNUSED_PARAMETER")
    fun render(
        input: Frame,
        references: List<PluginReference>,
        timeSeconds: Float = 0f,
        transitionProgress: Float = 0f,
        second: Frame? = null,
        purpose: RenderPurpose = RenderPurpose.PREVIEW,
    ): RenderResult {
        require(references.size <= 16 && finite(timeSeconds) && finite(transitionProgress))
        require(second == null || (second.width == input.width && second.height == input.height))
        var frame = input
        val issues = mutableListOf<PluginIssue>()
        for (ref in references.filter { it.enabled }) {
            val contribution = try { plugins.resolve(ref) } catch (e: Exception) {
                issues += PluginIssue(ref.pluginId, e.message ?: "Unavailable plugin")
                continue
            }
            try {
                frame = when (contribution.kind) {
                    ContributionKind.EFFECT, ContributionKind.GPU_SHADER -> {
                        val operations = resolveOperations(contribution, ref.parameters)
                        if (contribution.kind == ContributionKind.GPU_SHADER && gpu != null) {
                            try {
                                gpu.apply(frame, operations).also { require(it.width == frame.width && it.height == frame.height) }
                            } catch (e: Exception) {
                                issues += PluginIssue(ref.pluginId, "GPU unavailable; CPU fallback: ${e.message}")
                                CpuOperations.apply(frame, operations)
                            }
                        } else CpuOperations.apply(frame, operations)
                    }
                    ContributionKind.TRANSITION -> {
                        requireNotNull(second) { "Crossfade needs a second frame" }
                        blend(frame, second, transitionProgress.coerceIn(0f, 1f))
                    }
                    ContributionKind.PRESET -> opacity(frame, sample(contribution.keyframes, timeSeconds))
                }
            } catch (e: Exception) {
                // The preceding frame and all persisted project references remain untouched.
                issues += PluginIssue(ref.pluginId, "Contribution bypassed: ${e.message}")
            }
        }
        return RenderResult(frame, issues)
    }
}

fun resolveOperations(c: Contribution, values: Map<String, Float>): List<ResolvedOperation> = c.operations.map { op ->
    val p = op.amount.parameter?.let { id -> c.parameters.single { it.id == id } }
    val amount = if (p == null) op.amount.value else (values[p.id] ?: p.default).coerceIn(p.min, p.max)
    require(finite(amount))
    ResolvedOperation(op.type, amount, op.color[0], op.color[1], op.color[2])
}

object CpuOperations {
    fun apply(frame: Frame, operations: List<ResolvedOperation>): Frame {
        require(operations.size <= 8)
        val output = IntArray(frame.data.size)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val pixel = frame.data[y * frame.width + x]
            var r = ((pixel ushr 16) and 255) / 255f
            var g = ((pixel ushr 8) and 255) / 255f
            var b = (pixel and 255) / 255f
            for (op in operations) {
                val a = op.amount
                when (op.type) {
                    OperationType.TINT -> { r *= 1f + (op.red - 1f) * a; g *= 1f + (op.green - 1f) * a; b *= 1f + (op.blue - 1f) * a }
                    OperationType.EXPOSURE -> { val gain = 2f.pow(a); r *= gain; g *= gain; b *= gain }
                    OperationType.CONTRAST -> { r = (r - .5f) * a + .5f; g = (g - .5f) * a + .5f; b = (b - .5f) * a + .5f }
                    OperationType.INVERT -> { r += (1f - 2f * r) * a; g += (1f - 2f * g) * a; b += (1f - 2f * b) * a }
                    OperationType.VIGNETTE -> {
                        val dx = (x + .5f) / frame.width - .5f
                        val dy = (y + .5f) / frame.height - .5f
                        val factor = 1f - a * ((dx * dx + dy * dy) * 2f).coerceIn(0f, 1f)
                        r *= factor; g *= factor; b *= factor
                    }
                }
                r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
            }
            output[y * frame.width + x] = (pixel and -0x1000000) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
        return Frame(frame.width, frame.height, output)
    }
}

/** Generates GLSL exclusively from host-owned templates and bounded finite numbers. Never embeds package source. */
object ShaderCompiler {
    fun fragment(operations: List<ResolvedOperation>): String {
        require(operations.size in 1..8)
        fun number(v: Float): String { require(finite(v) && v in -16f..16f); return v.toString() }
        val body = operations.joinToString("\n") { op ->
            val a = number(op.amount)
            val line = when (op.type) {
                OperationType.TINT -> "c.rgb *= mix(vec3(1.0), vec3(${number(op.red)}, ${number(op.green)}, ${number(op.blue)}), $a);"
                OperationType.EXPOSURE -> "c.rgb *= exp2($a);"
                OperationType.CONTRAST -> "c.rgb = (c.rgb - vec3(0.5)) * $a + vec3(0.5);"
                OperationType.INVERT -> "c.rgb = mix(c.rgb, vec3(1.0) - c.rgb, $a);"
                OperationType.VIGNETTE -> "c.rgb *= 1.0 - $a * clamp(dot(uv - vec2(0.5), uv - vec2(0.5)) * 2.0, 0.0, 1.0);"
            }
            "$line\nc.rgb = clamp(c.rgb, 0.0, 1.0);"
        }
        return """#version 300 es
precision highp float;
uniform sampler2D sourceImage;
in vec2 uv;
out vec4 resultColor;
void main() {
    vec4 c = texture(sourceImage, uv);
    $body
    resultColor = c;
}
"""
    }
    val vertex = """#version 300 es
out vec2 uv;
void main() {
    vec2 positions[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
    vec2 p = positions[gl_VertexID];
    uv = (p + 1.0) * 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""
}

fun sample(keys: List<Keyframe>, time: Float): Float {
    require(keys.isNotEmpty() && finite(time))
    if (time <= keys.first().timeSeconds) return keys.first().value
    if (time >= keys.last().timeSeconds) return keys.last().value
    val next = keys.indexOfFirst { it.timeSeconds > time }
    val a = keys[next - 1]; val b = keys[next]
    val t = (time - a.timeSeconds) / (b.timeSeconds - a.timeSeconds)
    val eased = when (a.interpolation) { Interpolation.HOLD -> 0f; Interpolation.LINEAR -> t; Interpolation.EASE_IN_OUT -> t * t * (3f - 2f * t) }
    return a.value + (b.value - a.value) * eased
}

private fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255).roundToInt()
private fun opacity(frame: Frame, value: Float): Frame = Frame(frame.width, frame.height, IntArray(frame.data.size) {
    val pixel = frame.data[it]
    (pixel and 0x00ffffff) or ((((pixel ushr 24) * value).roundToInt()) shl 24)
})
private fun blend(a: Frame, b: Frame, amount: Float): Frame = Frame(a.width, a.height, IntArray(a.data.size) { i ->
    val pa = a.data[i]; val pb = b.data[i]
    val aa = (pa ushr 24) / 255f; val ab = (pb ushr 24) / 255f
    val alpha = aa * (1 - amount) + ab * amount
    var pixel = channel(alpha) shl 24
    for (shift in listOf(16, 8, 0)) {
        val premultiplied = ((pa ushr shift) and 255) / 255f * aa * (1 - amount) + ((pb ushr shift) and 255) / 255f * ab * amount
        pixel = pixel or (channel(if (alpha > 0f) premultiplied / alpha else 0f) shl shift)
    }
    pixel
})
