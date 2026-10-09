package dev.motionfx.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable enum class LayerKind { VIDEO, IMAGE, TEXT, SHAPE }
@Serializable enum class Easing { LINEAR, HOLD, EASE_IN, EASE_OUT, EASE_IN_OUT, BEZIER }
@Serializable data class Keyframe(val time: Long, val value: Float, val easing: Easing = Easing.LINEAR,
    val x1: Float = .25f, val y1: Float = .1f, val x2: Float = .25f, val y2: Float = 1f)
@Serializable data class Track(val value: Float = 0f, val keys: List<Keyframe> = emptyList()) {
    fun at(time: Long): Float {
        if (keys.isEmpty()) return value
        val sorted = keys.sortedBy { it.time }
        if (time <= sorted.first().time) return sorted.first().value
        if (time >= sorted.last().time) return sorted.last().value
        val b = sorted.first { it.time > time }; val a = sorted.last { it.time <= time }
        val t = (time - a.time).toFloat() / (b.time - a.time)
        val f = when (a.easing) {
            Easing.LINEAR -> t
            Easing.HOLD -> 0f
            Easing.EASE_IN -> t*t
            Easing.EASE_OUT -> 1-(1-t)*(1-t)
            Easing.EASE_IN_OUT -> t*t*(3-2*t)
            Easing.BEZIER -> bezier(t, a)
        }
        return a.value + (b.value-a.value)*f
    }
    fun key(time: Long, newValue: Float, easing: Easing = Easing.LINEAR) = copy(
        keys = (keys.filterNot { it.time == time } + Keyframe(time, newValue, easing)).sortedBy { it.time })
}
private fun bezier(t: Float, k: Keyframe): Float {
    fun curve(u: Float, a: Float, b: Float) = 3*(1-u)*(1-u)*u*a + 3*(1-u)*u*u*b + u*u*u
    var low=0f; var high=1f
    repeat(24) { val mid=(low+high)/2; if (curve(mid,k.x1,k.x2)<t) low=mid else high=mid }
    return curve((low+high)/2,k.y1,k.y2)
}
@Serializable data class Transform(
    val x: Track = Track(.5f), val y: Track = Track(.5f), val scale: Track = Track(1f),
    val rotation: Track = Track(0f), val opacity: Track = Track(1f))
@Serializable data class Effect(val kind: String, val amount: Track = Track(0f), val enabled: Boolean = true,
    val pluginId: String? = null)
@Serializable data class Layer(
    val id: String = UUID.randomUUID().toString(), val name: String = "Layer", val kind: LayerKind = LayerKind.SHAPE,
    val source: String = "", val text: String = "MotionFX", val color: Int = 0xff9875ff.toInt(),
    val start: Long = 0, val end: Long = 5000, val sourceIn: Long = 0, val speed: Float = 1f,
    val visible: Boolean = true, val locked: Boolean = false,
    val transform: Transform = Transform(), val effects: List<Effect> = emptyList()) {
    fun active(time: Long) = visible && time >= start && time < end
    fun mediaTime(time: Long) = sourceIn + ((time-start)*speed).toLong()
}
@Serializable data class Project(
    val version: Int = 1, val id: String = UUID.randomUUID().toString(), val name: String = "Untitled",
    val width: Int = 1280, val height: Int = 720, val fps: Int = 30, val duration: Long = 5000,
    val background: Int = 0xff111118.toInt(), val layers: List<Layer> = emptyList()) {
    fun validated(): Project {
        require(version == 1) { "Unsupported .mfx version: $version" }
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.length <= 200)
        require(width in 16..3840 && height in 16..3840 && width%2==0 && height%2==0)
        require(fps in listOf(24,30,60) && duration in 1..3_600_000)
        require(layers.size <= 32 && layers.map { it.id }.distinct().size == layers.size)
        layers.forEach { l ->
            require(l.name.length<=200 && l.text.length<=4096 && l.source.length<=8192 && l.effects.size<=32)
            require(l.start >= 0 && l.end > l.start && l.end <= duration && l.sourceIn >= 0)
            require(l.speed.isFinite() && l.speed in .1f..8f)
            (listOf(l.transform.x,l.transform.y,l.transform.scale,l.transform.rotation,l.transform.opacity)+l.effects.map{it.amount}).forEach { tr ->
                require(tr.value.isFinite() && tr.keys.size <= 10000)
                require(tr.keys.map{it.time}.distinct().size == tr.keys.size)
                tr.keys.forEach { k ->
                    require(k.time in 0..duration && k.value.isFinite())
                    require(k.x1 in 0f..1f && k.x2 in 0f..1f && k.y1.isFinite() && k.y2.isFinite())
                }
            }
        }
        return this
    }
    fun replace(layer: Layer) = copy(layers=layers.map { if(it.id==layer.id) layer else it })
    fun split(id: String, time: Long): Project {
        val l=layers.first{it.id==id}; require(!l.locked && time>l.start && time<l.end)
        return copy(layers=layers.flatMap { if(it.id!=id) listOf(it) else listOf(
            it.copy(end=time), it.copy(id=UUID.randomUUID().toString(), start=time, sourceIn=it.mediaTime(time))) })
    }
}
object ProjectFormat {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(p: Project) = json.encodeToString(Project.serializer(), p.validated())
    fun decode(text: String) = json.decodeFromString(Project.serializer(), text).validated()
}
class History {
    private val undo = ArrayDeque<Project>(); private val redo = ArrayDeque<Project>()
    fun push(p: Project) { undo.addLast(p); if(undo.size>50) undo.removeFirst(); redo.clear() }
    fun undo(current: Project): Project { if(undo.isEmpty()) return current; redo.addLast(current); return undo.removeLast() }
    fun redo(current: Project): Project { if(redo.isEmpty()) return current; undo.addLast(current); return redo.removeLast() }
    fun clear() { undo.clear(); redo.clear() }
}
