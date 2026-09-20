package com.pluginfence.engine

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.pluginfence.classify.PathScope
import com.pluginfence.model.PluginInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the roots that turn a raw path into "project file" / "IDE-managed" / "outside project",
 * and resolves plugin trust from the platform's plugin registry. Everything is cached so the
 * decision path never touches the platform under a lock.
 */
class ScopeTracker {

    @Volatile
    private var scope: PathScope = PathScope(emptyList(), ideRoots())

    private val trust = ConcurrentHashMap<String, Boolean>()

    fun pathScope(): PathScope = scope

    fun refreshProjects() {
        val roots = runCatching {
            ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }
        }.getOrDefault(emptyList())
        scope = PathScope(roots, ideRoots())
    }

    fun projectOpened(project: Project) = refreshProjects()

    fun projectClosed(project: Project) = refreshProjects()

    /**
     * Trusted = part of the platform: PluginFence itself, plugins bundled with the IDE, or plugins
     * whose vendor is JetBrains. Trusted plugins are monitored, never enforced against by default.
     */
    fun isTrusted(pluginId: String, bundledHint: Boolean, vendorHint: String?): Boolean {
        if (pluginId == OWN_PLUGIN_ID) return true
        return trust.computeIfAbsent(pluginId) {
            if (bundledHint || isJetBrains(vendorHint)) return@computeIfAbsent true
            val descriptor = runCatching { PluginManagerCore.getPlugin(PluginId.getId(pluginId)) }.getOrNull()
            descriptor != null && (descriptor.isBundled || isJetBrains(descriptor.vendor))
        }
    }

    fun installedPlugins(): List<PluginInfo> = runCatching {
        PluginManagerCore.plugins.map { d ->
            val id = d.pluginId.idString
            PluginInfo(
                pluginId = id,
                name = d.name ?: id,
                version = d.version ?: "",
                vendor = d.vendor ?: "",
                bundled = d.isBundled,
                trusted = isTrusted(id, d.isBundled, d.vendor),
                enabled = d.isEnabled,
            )
        }
    }.getOrDefault(emptyList())

    private fun isJetBrains(vendor: String?): Boolean {
        val v = vendor?.trim()?.lowercase() ?: return false
        return v == "jetbrains" || v.startsWith("jetbrains ") || v == "jetbrains s.r.o." || v == "jetbrains s.r.o"
    }

    private fun ideRoots(): List<String> = runCatching {
        listOfNotNull(
            PathManager.getConfigPath(), PathManager.getSystemPath(), PathManager.getPluginsPath(),
            PathManager.getLogPath(), PathManager.getTempPath(), PathManager.getHomePath(),
            System.getProperty("java.io.tmpdir"),
        )
    }.getOrDefault(emptyList())

    companion object {
        const val OWN_PLUGIN_ID = "com.pluginfence"
    }
}
