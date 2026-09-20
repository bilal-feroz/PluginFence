package com.pluginfence.ui

import com.google.gson.GsonBuilder
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.ProjectManager
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.Incident
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Writes an incident (with its redacted attack chain) as a JSON file the user picks. */
object IncidentExport {

    fun export(incident: Incident, engine: FenceEngine) {
        val descriptor = FileSaverDescriptor("Export PluginFence Incident", "Save the incident and its event chain as JSON", "json")
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "pluginfence-incident-${incident.id}.json") ?: return
        val payload = mapOf(
            "id" to incident.id,
            "kind" to incident.kind.name,
            "title" to incident.title,
            "timestamp" to UiSupport.dateTime(incident.timestamp),
            "plugin" to mapOf("id" to incident.pluginId, "name" to incident.pluginName, "version" to incident.pluginVersion),
            "summary" to incident.summary,
            "outcome" to incident.outcome,
            "riskScore" to incident.riskScore,
            "riskLevel" to incident.riskLevel.name,
            "chain" to incident.chain.map { e ->
                mapOf(
                    "time" to UiSupport.dateTime(e.timestamp),
                    "operation" to e.operation.name,
                    "capability" to e.capability?.name,
                    "target" to engine.sensitivePaths.displayPath(e.target),
                    "api" to e.api,
                    "sourceClass" to e.sourceClass,
                    "verdict" to e.verdict.name,
                    "reason" to e.reason,
                    "ruleId" to e.ruleId,
                    "riskScore" to e.riskScore,
                    "riskFactors" to e.riskFactors.map { f -> mapOf("id" to f.id, "label" to f.label, "points" to f.points) },
                    "metadata" to e.metadata,
                )
            },
            "generatedBy" to "PluginFence",
        )
        val json = GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(payload)
        runCatching {
            Files.write(wrapper.file.toPath(), json.toByteArray(StandardCharsets.UTF_8))
        }.onSuccess {
            NotificationGroupManager.getInstance().getNotificationGroup("PluginFence")
                .createNotification("Incident exported", wrapper.file.absolutePath, NotificationType.INFORMATION).notify(project)
        }.onFailure { t ->
            NotificationGroupManager.getInstance().getNotificationGroup("PluginFence")
                .createNotification("Incident export failed", t.message ?: t.javaClass.simpleName, NotificationType.ERROR).notify(project)
        }
    }
}
