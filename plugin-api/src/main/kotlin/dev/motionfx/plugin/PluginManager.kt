package dev.motionfx.plugin

import java.io.File
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class InstalledPlugin(val manifest: PluginManifest, val packageSha256: String, val enabled: Boolean = false) {
    val fileName: String get() = "$packageSha256.mfxplugin"
}
@Serializable private data class Registry(val schemaVersion: Int = 1, val plugins: List<InstalledPlugin> = emptyList())

/** Thread-safe lifecycle and lazy loading. The registry retains metadata, never inactive graphs or GPU objects. */
class PluginManager(private val directory: File, private val appVersion: Version = PluginApi.applicationVersion) {
    private val registryFile = File(directory, "registry.json")
    private val packages = File(directory, "packages").apply { mkdirs() }
    private var registry = AtomicStorage.read(registryFile) { bytes ->
        PluginApi.json.decodeFromString<Registry>(checkedJson(bytes)).also { r ->
            require(r.schemaVersion == 1 && r.plugins.size <= 128)
            require(r.plugins.map { it.manifest.id }.distinct().size == r.plugins.size)
            r.plugins.forEach {
                PackageReader.validateManifest(it.manifest)
                require(Regex("[0-9a-f]{64}").matches(it.packageSha256))
            }
            checkCycles(r.plugins)
        }
    } ?: Registry()
    private val loaded = mutableMapOf<String, PluginContent>()
    private val quarantined = mutableMapOf<String, String>()

    @Synchronized fun list(): List<InstalledPlugin> = registry.plugins.toList()
    @Synchronized fun loadedPluginCount(): Int = loaded.size
    @Synchronized fun issue(id: String): String? = quarantined[id]

    @Synchronized fun install(input: InputStream): InstalledPlugin {
        val (pkg, bytes) = PackageReader.read(input)
        PackageReader.checkCompatibility(pkg.manifest, appVersion)
        require(registry.plugins.none { it.manifest.id == pkg.manifest.id }) { "Uninstall the previous version first (project references remain intact)" }
        require(registry.plugins.size < 128) { "Installed plugin limit reached" }
        // Dependencies may be installed later, but cyclic dependency graphs are rejected now.
        val item = InstalledPlugin(pkg.manifest, pkg.sha256)
        val next = registry.copy(plugins = registry.plugins + item)
        checkCycles(next.plugins)
        val destination = File(packages, item.fileName)
        if (!destination.exists() || destination.inputStream().use { sha256(boundedRead(it, PluginApi.MAX_PACKAGE_BYTES)) } != pkg.sha256) {
            AtomicStorage.write(destination, bytes)
        }
        commit(next) // If this fails, only an inert orphan package remains; the old registry is valid.
        return item
    }

    @Synchronized fun enable(id: String) {
        val item = find(id)
        PackageReader.checkCompatibility(item.manifest, appVersion)
        item.manifest.dependencies.forEach { dep ->
            val installed = find(dep.id)
            require(installed.enabled && Version.parse(installed.manifest.version) in dep.versions) { "Enable compatible dependency ${dep.id} first" }
            require(issue(dep.id) == null) { "Dependency ${dep.id} is quarantined" }
            content(installed) // Includes transitive compatibility and integrity checks.
        }
        readVerified(item) // Validate the package before committing; do not retain its content yet.
        commit(registry.copy(plugins = registry.plugins.map { if (it.manifest.id == id) it.copy(enabled = true) else it }))
        quarantined.remove(id)
    }

    @Synchronized fun disable(id: String) {
        find(id)
        require(registry.plugins.none { it.enabled && it.manifest.dependencies.any { dep -> dep.id == id } }) { "Disable dependent plugins first" }
        commit(registry.copy(plugins = registry.plugins.map { if (it.manifest.id == id) it.copy(enabled = false) else it }))
        loaded.remove(id); quarantined.remove(id)
    }

    @Synchronized fun uninstall(id: String) {
        val item = find(id)
        require(registry.plugins.none { it.manifest.dependencies.any { dep -> dep.id == id } }) { "Uninstall dependent plugins first" }
        commit(registry.copy(plugins = registry.plugins.filterNot { it.manifest.id == id }))
        loaded.remove(id); quarantined.remove(id)
        // Keep the previous package while registry.bak can reference it. At most one registry generation is retained.
        val backup = AtomicStorage.read(File(registryFile.path + ".bak")) { PluginApi.json.decodeFromString<Registry>(checkedJson(it)) }
        val retained = (registry.plugins + (backup?.plugins ?: emptyList())).map { it.fileName }.toSet()
        packages.listFiles()?.filter { it.name.endsWith(".mfxplugin") && it.name !in retained }?.forEach { it.delete() }
        check(item.manifest.id == id)
    }

    @Synchronized fun contributions(id: String): List<Contribution> {
        val item = find(id)
        require(item.enabled) { "Plugin is disabled" }
        return content(item).contributions.toList()
    }

    @Synchronized fun resolve(reference: PluginReference): Contribution {
        val item = find(reference.pluginId)
        require(item.enabled) { "Plugin is disabled" }
        require(item.manifest.version == reference.version) { "Project requires plugin version ${reference.version}" }
        require(reference.parameters.size <= 16 && reference.parameters.values.all(::finite)) { "Invalid parameter values" }
        val contribution = content(item).contributions.singleOrNull { it.id == reference.contributionId }
            ?: throw PluginException("Missing contribution ${reference.contributionId}")
        require(reference.parameters.keys.all { key -> contribution.parameters.any { it.id == key } }) { "Unknown parameter" }
        return contribution
    }

    @Synchronized fun quarantine(id: String, reason: String) {
        quarantined[id] = reason.take(512)
        loaded.remove(id)
    }

    private fun content(item: InstalledPlugin): PluginContent {
        val id = item.manifest.id
        quarantined[id]?.let { throw PluginException("Plugin quarantined: $it") }
        PackageReader.checkCompatibility(item.manifest, appVersion)
        item.manifest.dependencies.forEach { dep ->
            val target = find(dep.id)
            require(target.enabled && Version.parse(target.manifest.version) in dep.versions && dep.id !in quarantined) { "Unavailable dependency ${dep.id}" }
            content(target)
        }
        return loaded.getOrPut(id) {
            try { readVerified(item).content }
            catch (e: Exception) { quarantine(id, e.message ?: "Package read failed"); throw e }
        }
    }

    private fun readVerified(item: InstalledPlugin): PluginPackage {
        val bytes = File(packages, item.fileName).inputStream().use { boundedRead(it, PluginApi.MAX_PACKAGE_BYTES) }
        require(sha256(bytes) == item.packageSha256) { "Installed package integrity check failed" }
        val pkg = PackageReader.decode(bytes)
        require(pkg.manifest == item.manifest) { "Registry identity mismatch" }
        return pkg
    }
    private fun find(id: String) = registry.plugins.singleOrNull { it.manifest.id == id } ?: throw PluginException("Missing plugin $id")
    private fun commit(next: Registry) {
        AtomicStorage.write(registryFile, PluginApi.json.encodeToString(next).toByteArray())
        registry = next
    }
    private fun checkCycles(items: List<InstalledPlugin>) {
        val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in done) return
            require(visiting.add(id)) { "Cyclic plugin dependency" }
            items.find { it.manifest.id == id }?.manifest?.dependencies?.forEach { visit(it.id) }
            visiting.remove(id); done.add(id)
        }
        items.forEach { visit(it.manifest.id) }
    }
}
