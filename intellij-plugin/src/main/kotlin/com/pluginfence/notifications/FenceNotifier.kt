package com.pluginfence.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.Incident
import com.pluginfence.ui.FenceToolWindowFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Native IntelliJ notifications for things a developer must see: permission requests (ASK),
 * high/critical blocked operations, correlated incidents and high-risk behaviour drift.
 *
 * Anti-spam: identical (plugin, operation, target) notifications are aggregated for 30 s,
 * per-target muting ("Keep Blocking") silences repeats for the session.
 */
class FenceNotifier(private val engine: FenceEngine) {

    private val recent = ConcurrentHashMap<String, Long>()
    private val muted = ConcurrentHashMap.newKeySet<String>()
    private val notifiedIncidents = ConcurrentHashMap<String, Int>()
    private val notifiedDrifts = ConcurrentHashMap.newKeySet<String>()

    fun notifyEvent(event: FenceEvent) {
        if (!event.prevented) return
        val key = key(event)
        if (key in muted || !throttle(key)) return
        if (event.verdict == FenceVerdict.ASK) {
            askPermission(event)
        } else if (event.riskLevel >= FenceRisk.HIGH) {
            blocked(event)
        }
    }

    fun notifyIncident(incident: Incident, trigger: FenceEvent) {
        val previous = notifiedIncidents.put(incident.id, incident.chain.size)
        if (previous != null && previous >= incident.chain.size) return
        if (incident.riskLevel < FenceRisk.HIGH) return
        val text = buildString {
            append(incident.summary)
            append("<br><b>").append(incident.outcome).append("</b>")
        }
        val notification = group(INCIDENTS).createNotification("PluginFence: ${incident.title}", text, NotificationType.ERROR)
        notification.subtitle = "${incident.pluginName} ${incident.pluginVersion} - risk ${incident.riskScore}/100 ${incident.riskLevel.label.uppercase()}"
        notification.addAction(openToolWindow("View Incident", FenceToolWindowFactory.TAB_OVERVIEW))
        if (trigger.verdict == FenceVerdict.ASK) addPermissionActions(notification, trigger)
        show(notification)
    }

    fun notifyDrift(drift: BehaviorDrift) {
        val key = "${drift.pluginId}|${drift.oldVersion}|${drift.newVersion}"
        if (!notifiedDrifts.add(key)) return
        val text = buildString {
            append("${drift.pluginName} ${drift.oldVersion} &rarr; ${drift.newVersion} gained ${drift.newCapabilityCount} new capabilit${if (drift.newCapabilityCount == 1) "y" else "ies"}:")
            append("<br>")
            append(describe(drift).joinToString("<br>") { "&bull; $it" })
        }
        val notification = group(INCIDENTS).createNotification("PluginFence: behaviour drift detected", text, NotificationType.WARNING)
        notification.subtitle = "Risk ${drift.riskScore}/100 ${drift.riskLevel.label.uppercase()}"
        notification.addAction(openToolWindow("Open Drift", FenceToolWindowFactory.TAB_DRIFT))
        show(notification)
    }

    fun mute(event: FenceEvent) {
        muted.add(key(event))
    }

    // --- builders -----------------------------------------------------------------------------

    private fun askPermission(event: FenceEvent) {
        val text = buildString {
            append("<b>${event.displayPlugin}</b> wants to perform <b>${event.actionLabel.lowercase()}</b> of<br>")
            append("<code>${escape(displayTarget(event))}</code>")
            if (event.metadata["args"]?.isNotBlank() == true) append("<br><code>${escape(event.metadata["args"]!!)}</code>")
            append("<br>The operation was prevented. Retry the plugin action after allowing it.")
        }
        val notification = group(GENERAL).createNotification("PluginFence prevented an operation", text, NotificationType.WARNING)
        notification.subtitle = "${event.reason} - risk ${event.riskScore}/100"
        addPermissionActions(notification, event)
        notification.addAction(openToolWindow("Details", FenceToolWindowFactory.TAB_ACTIVITY))
        show(notification)
    }

    private fun blocked(event: FenceEvent) {
        val text = buildString {
            append("The plugin attempted <b>${event.actionLabel.lowercase()}</b> of<br><code>${escape(displayTarget(event))}</code>")
            append("<br>${escape(event.reason)}")
        }
        val notification = group(INCIDENTS).createNotification("PluginFence blocked ${event.displayPlugin}", text, NotificationType.ERROR)
        notification.subtitle = "Risk ${event.riskScore}/100 ${event.riskLevel.label.uppercase()}"
        notification.addAction(openToolWindow("View Incident", FenceToolWindowFactory.TAB_OVERVIEW))
        show(notification)
    }

    private fun addPermissionActions(notification: Notification, event: FenceEvent) {
        notification.addAction(object : NotificationAction("Allow Once") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                engine.allowOnce(event); n.expire()
            }
        })
        notification.addAction(object : NotificationAction("Always Allow") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                engine.alwaysAllow(event); n.expire()
            }
        })
        notification.addAction(object : NotificationAction("Keep Blocking") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                engine.keepBlocking(event); n.expire()
            }
        })
    }

    private fun openToolWindow(text: String, tab: String) = object : NotificationAction(text) {
        override fun actionPerformed(e: AnActionEvent, n: Notification) {
            val project = e.project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
            FenceToolWindowFactory.show(project, tab)
        }
    }

    private fun show(notification: Notification) {
        ApplicationManager.getApplication().invokeLater {
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            notification.notify(project)
        }
    }

    private fun throttle(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = recent.put(key, now)
        if (recent.size > 256) recent.entries.removeIf { now - it.value > AGGREGATION_WINDOW_MS }
        return last == null || now - last > AGGREGATION_WINDOW_MS
    }

    private fun key(event: FenceEvent) = "${event.pluginId}|${event.operation}|${event.approvalTarget.lowercase()}"

    private fun displayTarget(event: FenceEvent) = engine.sensitivePaths.displayPath(event.target)

    private fun describe(drift: BehaviorDrift): List<String> {
        val out = ArrayList<String>()
        drift.addedSensitiveResources.forEach { out += "Sensitive file access ($it)" }
        drift.addedCapabilities.filter { it != com.pluginfence.model.Capability.SENSITIVE_FILES || drift.addedSensitiveResources.isEmpty() }
            .filter { it != com.pluginfence.model.Capability.NETWORK || drift.addedHosts.isEmpty() }
            .filter { it != com.pluginfence.model.Capability.PROCESS_EXECUTION || drift.addedProcesses.isEmpty() }
            .forEach { out += it.displayName }
        drift.addedHosts.forEach { out += "Network destination $it" }
        drift.addedProcesses.forEach { out += "Process execution: $it" }
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun group(id: String) = NotificationGroupManager.getInstance().getNotificationGroup(id)

    companion object {
        const val GENERAL = "PluginFence"
        const val INCIDENTS = "PluginFence Incidents"
        private const val AGGREGATION_WINDOW_MS = 30_000L
    }
}
