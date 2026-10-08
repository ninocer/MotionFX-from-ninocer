package dev.motionfx.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class PluginApiTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manager() = PluginManager(temporary.newFolder())
    private val range = VersionRange("1.0.0", "2.0.0")
    private val appRange = VersionRange("0.1.0", "1.0.0")
    private fun effect(type: OperationType = OperationType.INVERT) = Contribution("effect", "Effect", ContributionKind.EFFECT,
        operations = listOf(Operation(type, Scalar(1f))))
    private fun bytes(
        id: String = "test.plugin", version: String = "1.0.0", items: List<Contribution> = listOf(effect()),
        dependencies: List<Dependency> = emptyList(), api: VersionRange = range, app: VersionRange = appRange,
    ): ByteArray {
        val content = PluginApi.json.encodeToString(PluginContent(items)).toByteArray()
        val manifest = PluginManifest(id = id, name = "Test plugin", version = version, description = "Test", author = "Tests",
            api = api, application = app, dependencies = dependencies, contentSha256 = sha256(content))
        return zip("manifest.json" to PluginApi.json.encodeToString(manifest).toByteArray(), "content.json" to content)
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        return out.toByteArray()
    }
    private fun install(m: PluginManager, bytes: ByteArray = bytes(), enabled: Boolean = true): InstalledPlugin {
        val item = m.install(ByteArrayInputStream(bytes))
        if (enabled) m.enable(item.manifest.id)
        return item
    }
    private fun ref(id: String = "test.plugin", contribution: String = "effect") = PluginReference(id, "1.0.0", contribution)

    @Test fun `stable versions compare numerically and reject ambiguous inputs`() {
        assertTrue(Version.parse("1.10.0") > Version.parse("1.2.99"))
        listOf("1", "1.0", "01.0.0", "1.0.0-beta", "1.0.0+build", "9999999.0.0", "-1.0.0").forEach { assertFails { Version.parse(it) } }
        assertTrue(Version.parse("1.0.0") in range); assertFalse(Version.parse("2.0.0") in range)
    }
    @Test fun `valid package exposes a versioned manifest`() {
        val p = PackageReader.decode(bytes())
        assertEquals("test.plugin", p.manifest.id)
        assertEquals(1, p.content.contributions.size)
    }
    @Test fun `path traversal absolute paths and executable files are rejected`() {
        listOf("../manifest.json", "/manifest.json", "classes.dex", "lib.so", "entry.js", "shader.glsl", "nested/content.json").forEach { name ->
            assertFailsWith<PluginException> { PackageReader.decode(zip(name to "payload".toByteArray())) }
        }
    }
    @Test fun `compressed expansion is bounded`() {
        val bomb = zip("manifest.json" to ByteArray(40_000) { 32 }, "content.json" to byteArrayOf())
        assertTrue(bomb.size < 1000)
        assertFailsWith<PluginException> { PackageReader.decode(bomb) }
    }
    @Test fun `compressed package input has a size limit`() {
        assertFails { PackageReader.read(ByteArrayInputStream(ByteArray(PluginApi.MAX_PACKAGE_BYTES + 1))) }
    }
    @Test fun `deeply nested json is rejected before decoding`() {
        assertFails { checkedJson(("[".repeat(33) + "]".repeat(33)).toByteArray()) }
    }
    @Test fun `checksum corruption is rejected`() {
        val good = PackageReader.decode(bytes())
        val manifest = good.manifest.copy(contentSha256 = "0".repeat(64))
        assertFailsWith<PluginException> { PackageReader.decode(zip(
            "manifest.json" to PluginApi.json.encodeToString(manifest).toByteArray(),
            "content.json" to PluginApi.json.encodeToString(good.content).toByteArray())) }
    }
    @Test fun `duplicate ZIP entries are rejected`() {
        val archive = zip("manifest.json" to "{}".toByteArray(), "manifest.jsoN" to "{}".toByteArray())
        val signature = "manifest.jsoN".toByteArray()
        for (i in 0..archive.size - signature.size) {
            if (archive.copyOfRange(i, i + signature.size).contentEquals(signature)) archive[i + signature.lastIndex] = 'n'.code.toByte()
        }
        assertFailsWith<PluginException> { PackageReader.decode(archive) }
    }
    @Test fun `raw shader properties are rejected even with a valid checksum`() {
        val content = """{"contributions":[{"id":"shader","name":"Shader","kind":"GPU_SHADER","source":"void main() { while(true) {} }"}]}""".toByteArray()
        val manifest = PackageReader.decode(bytes()).manifest.copy(contentSha256 = sha256(content))
        assertFailsWith<PluginException> { PackageReader.decode(zip(
            "manifest.json" to PluginApi.json.encodeToString(manifest).toByteArray(), "content.json" to content)) }
    }
    @Test fun `invalid ids unknown parameters and unbounded operations are rejected`() {
        assertFails { PackageReader.decode(bytes(id = "../escape")) }
        assertFails { PackageReader.decode(bytes(items = listOf(effect().copy(operations = List(9) { Operation(OperationType.INVERT, Scalar(1f)) })))) }
        assertFails { PackageReader.decode(bytes(items = listOf(effect().copy(operations = listOf(Operation(OperationType.TINT, Scalar(parameter = "missing"))))))) }
        val invalid = effect().copy(operations = listOf(Operation(OperationType.INVERT, Scalar(5f))))
        assertFails { PackageReader.decode(bytes(items = listOf(invalid))) }
    }
    @Test fun `incompatible api and application cannot install`() {
        val m = manager()
        assertFails { m.install(ByteArrayInputStream(bytes(api = VersionRange("2.0.0", "3.0.0")))) }
        assertFails { m.install(ByteArrayInputStream(bytes(app = VersionRange("9.0.0", "10.0.0")))) }
        assertTrue(m.list().isEmpty())
    }
    @Test fun `lifecycle is persistent and inactive graphs consume no cache`() {
        val dir = temporary.newFolder(); val m = PluginManager(dir)
        install(m, enabled = false)
        assertEquals(0, m.loadedPluginCount())
        assertFails { m.resolve(ref()) }
        m.enable("test.plugin")
        assertEquals(0, m.loadedPluginCount())
        m.resolve(ref()); assertEquals(1, m.loadedPluginCount())
        m.disable("test.plugin"); assertEquals(0, m.loadedPluginCount())
        assertFalse(PluginManager(dir).list().single().enabled)
        m.uninstall("test.plugin"); assertTrue(m.list().isEmpty())
        assertTrue(PluginManager(dir).list().isEmpty())
    }
    @Test fun `dependencies enforce versions enable order and reverse lifecycle guards`() {
        val m = manager()
        install(m, bytes(id = "child", dependencies = listOf(Dependency("base", range))), false)
        assertFails { m.enable("child") }
        install(m, bytes(id = "base"), false)
        assertFails { m.enable("child") }
        m.enable("base"); m.enable("child")
        assertFails { m.disable("base") }; assertFails { m.uninstall("base") }
        m.disable("child"); m.uninstall("child"); m.disable("base"); m.uninstall("base")
        assertTrue(m.list().isEmpty())
    }
    @Test fun `dependency wrong version and cycles cannot activate`() {
        val m = manager()
        install(m, bytes(id = "base", version = "2.0.0"))
        install(m, bytes(id = "child", dependencies = listOf(Dependency("base", range))), false)
        assertFails { m.enable("child") }
        val other = manager()
        install(other, bytes(id = "first", dependencies = listOf(Dependency("second", range))), false)
        assertFails { install(other, bytes(id = "second", dependencies = listOf(Dependency("first", range))), false) }
        assertEquals(1, other.list().size)
    }
    @Test fun `duplicate install cannot overwrite working package`() {
        val m = manager(); install(m)
        assertFails { install(m, bytes(version = "1.1.0")) }
        assertEquals("1.0.0", m.list().single().manifest.version)
    }
    @Test fun `missing plugin preserves project and bypasses only its contribution`() {
        val m = manager(); install(m)
        val input = Frame(1, 1, intArrayOf(0xff204060.toInt()))
        val refs = listOf(ref("missing"), ref())
        val result = FrameEngine(m).render(input, refs)
        assertEquals(0xffdfbf9f.toInt(), result.frame.pixels()[0])
        assertEquals("missing", result.issues.single().pluginId)
        assertEquals(0xff204060.toInt(), input.pixels()[0]); assertEquals(2, refs.size)
    }
    @Test fun `preview and export are pixel identical at the same explicit time`() {
        val m = manager(); install(m)
        val input = Frame(2, 1, intArrayOf(0xff125533.toInt(), 0x80665522.toInt()))
        val engine = FrameEngine(m)
        assertContentEquals(engine.render(input, listOf(ref()), 1.25f, purpose = RenderPurpose.PREVIEW).frame.pixels(),
            engine.render(input, listOf(ref()), 1.25f, purpose = RenderPurpose.EXPORT).frame.pixels())
    }
    @Test fun `parameter clamping is deterministic and unknown parameters bypass safely`() {
        val c = Contribution("effect", "Tint", ContributionKind.EFFECT,
            listOf(Parameter("strength", "Strength", 0f, 1f, .5f)), listOf(Operation(OperationType.TINT, Scalar(parameter = "strength"), listOf(1f, 0f, 0f))))
        val m = manager(); install(m, bytes(items = listOf(c)))
        val engine = FrameEngine(m); val frame = Frame(1, 1, intArrayOf(-1))
        assertEquals(0xffff0000.toInt(), engine.render(frame, listOf(ref().copy(parameters = mapOf("strength" to 5f)))).frame.pixels()[0])
        val bad = engine.render(frame, listOf(ref().copy(parameters = mapOf("unknown" to 1f))))
        assertEquals(-1, bad.frame.pixels()[0]); assertEquals(1, bad.issues.size)
    }
    @Test fun `preset and alpha-aware transition produce expected samples`() {
        val preset = Contribution("fade", "Fade", ContributionKind.PRESET, keyframes = listOf(Keyframe(0f, 0f), Keyframe(2f, 1f)))
        val transition = Contribution("cross", "Cross", ContributionKind.TRANSITION)
        val m = manager(); install(m, bytes(items = listOf(preset, transition)))
        val engine = FrameEngine(m); val red = Frame(1, 1, intArrayOf(0xffff0000.toInt())); val blue = Frame(1, 1, intArrayOf(0xff0000ff.toInt()))
        assertEquals(128, engine.render(red, listOf(ref(contribution = "fade")), 1f).frame.pixels()[0] ushr 24)
        assertEquals(0xff800080.toInt(), engine.render(red, listOf(ref(contribution = "cross")), transitionProgress = .5f, second = blue).frame.pixels()[0])
        assertEquals(0xff0000ff.toInt(), engine.render(Frame(1, 1, intArrayOf(0x00ff0000)), listOf(ref(contribution = "cross")), transitionProgress = 1f, second = blue).frame.pixels()[0])
    }
    @Test fun `interpolation honors holds endpoints and easing`() {
        assertEquals(.5f, sample(listOf(Keyframe(0f, 0f, Interpolation.EASE_IN_OUT), Keyframe(2f, 1f)), 1f))
        assertEquals(0f, sample(listOf(Keyframe(0f, 0f, Interpolation.HOLD), Keyframe(2f, 1f)), 1f))
        assertEquals(1f, sample(listOf(Keyframe(0f, 0f), Keyframe(2f, 1f)), 9f))
    }
    @Test fun `GPU backend failures fall back to real CPU rendering`() {
        val m = manager(); install(m, bytes(items = listOf(effect().copy(kind = ContributionKind.GPU_SHADER))))
        val engine = FrameEngine(m, GpuBackend { _, _ -> error("Context lost") })
        val result = engine.render(Frame(1, 1, intArrayOf(-1)), listOf(ref()))
        assertEquals(0xff000000.toInt(), result.frame.pixels()[0])
        assertTrue(result.issues.single().message.contains("CPU fallback"))
    }
    @Test fun `installed corruption is quarantined without damaging project`() {
        val dir = temporary.newFolder(); val m = PluginManager(dir); val item = install(m)
        val projectFile = File(temporary.newFolder(), "project.mfx"); val store = ProjectStore(projectFile)
        store.save(ProjectDocument(plugins = listOf(ref())))
        val before = projectFile.readBytes()
        File(dir, "packages/${item.fileName}").writeText("corrupt")
        val result = FrameEngine(m).render(Frame(1, 1, intArrayOf(-1)), listOf(ref()))
        assertEquals(-1, result.frame.pixels()[0]); assertNotNull(m.issue("test.plugin"))
        assertEquals(0, m.loadedPluginCount()); assertContentEquals(before, projectFile.readBytes())
        assertEquals(listOf(ref()), store.load()!!.plugins)
    }
    @Test fun `app version change rejects enabled plugins on resolution`() {
        val dir = temporary.newFolder(); install(PluginManager(dir))
        val m = PluginManager(dir, Version.parse("2.0.0"))
        assertFails { m.resolve(ref()) }; assertEquals(0, m.loadedPluginCount())
    }
    @Test fun `exact project version prevents silent visual changes`() {
        val m = manager(); install(m)
        assertFails { m.resolve(ref().copy(version = "1.0.1")) }
    }
    @Test fun `project roundtrip preserves missing plugin references and parameters`() {
        val file = File(temporary.newFolder(), "project.mfx"); val store = ProjectStore(file)
        val p = ProjectDocument(plugins = listOf(ref("missing").copy(parameters = mapOf("strength" to .75f))))
        store.save(p); assertEquals(p, store.load())
    }
    @Test fun `corrupt project recovers previous atomic snapshot`() {
        val file = File(temporary.newFolder(), "project.mfx"); val store = ProjectStore(file)
        val p = ProjectDocument(name = "Saved work", plugins = listOf(ref("missing")))
        store.save(p); store.save(p.copy(name = "Latest")); file.writeText("{partial")
        assertEquals(p, store.load())
        assertTrue(File(file.path + ".bak").exists())
    }
    @Test fun `unrecoverable project is preserved instead of replaced`() {
        val file = File(temporary.newFolder(), "project.mfx"); file.writeText("broken")
        assertFails { ProjectStore(file).load() }; assertEquals("broken", file.readText())
    }
    @Test fun `registry backup retains the package needed after interrupted removal`() {
        val dir = temporary.newFolder(); val m = PluginManager(dir)
        install(m); m.uninstall("test.plugin")
        File(dir, "registry.json").writeText("interrupted")
        val recovered = PluginManager(dir)
        assertEquals("effect", recovered.resolve(ref()).id)
    }
    @Test fun `recovered snapshot stays valid through the next interrupted write`() {
        val file = File(temporary.newFolder(), "project.mfx"); val store = ProjectStore(file)
        val original = ProjectDocument(name = "Original")
        store.save(original); store.save(original.copy(name = "Second")); file.writeText("bad")
        assertEquals(original, store.load())
        store.save(original.copy(name = "Third")); file.writeText("bad again")
        assertEquals(original, store.load())
    }
    @Test fun `frames enforce allocation budgets and copy input buffers`() {
        assertFails { Frame(Int.MAX_VALUE, 2, intArrayOf()) }
        val values = intArrayOf(-1); val frame = Frame(1, 1, values); values[0] = 0
        assertEquals(-1, frame.pixels()[0]); frame.pixels()[0] = 0; assertEquals(-1, frame.pixels()[0])
    }
    @Test fun `shader generator only emits bounded host code`() {
        val shader = ShaderCompiler.fragment(listOf(ResolvedOperation(OperationType.VIGNETTE, .7f, 1f, 1f, 1f)))
        assertTrue(shader.startsWith("#version 300 es")); assertFalse(shader.contains("while"))
        assertFails { ShaderCompiler.fragment(listOf(ResolvedOperation(OperationType.EXPOSURE, Float.NaN, 1f, 1f, 1f))) }
    }
    @Test fun `committed example installs and every contribution actually renders`() {
        val path = File("../examples/starter.mfxplugin")
        assertTrue(path.isFile, "Run tests from Gradle's plugin-api working directory")
        val m = manager(); install(m, path.readBytes())
        val input = Frame(2, 2, IntArray(4) { -1 }); val second = Frame(2, 2, IntArray(4) { 0xff000000.toInt() })
        m.contributions("dev.motionfx.starter").forEach { contribution ->
            val result = FrameEngine(m).render(input, listOf(ref("dev.motionfx.starter", contribution.id)), .5f, .5f, second)
            assertTrue(result.issues.isEmpty()); assertFalse(input.pixels().contentEquals(result.frame.pixels()), contribution.id)
        }
    }
}
