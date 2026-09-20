package com.pluginfence.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.pluginfence.engine.FenceEngine
import com.pluginfence.engine.FenceListener
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.Incident
import java.awt.BorderLayout
import javax.swing.JPanel

class FenceToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val engine = FenceEngine.getInstance()
        engine.start()
        val panel = FenceToolWindowPanel(project, engine, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        panels[project] = panel
        Disposer.register(toolWindow.disposable) { panels.remove(project) }
        val stats = engine.stats()
        com.intellij.openapi.diagnostic.Logger.getInstance(FenceToolWindowFactory::class.java).info(
            "PluginFence tool window opened: agent=${stats.agentInstalled} provider=${stats.providerRegistered} " +
                "events=${stats.eventsRecorded} incidents=${engine.incidents().size} drifts=${stats.behaviorChanges}",
        )
    }

    companion object {
        const val ID = "PluginFence"
        const val TAB_OVERVIEW = "Overview"
        const val TAB_ACTIVITY = "Activity"
        const val TAB_PERMISSIONS = "Permissions"
        const val TAB_DRIFT = "Drift"

        private val panels = java.util.concurrent.ConcurrentHashMap<Project, FenceToolWindowPanel>()

        fun show(project: Project, tab: String) {
            ApplicationManager.getApplication().invokeLater {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return@invokeLater
                toolWindow.activate({ panels[project]?.selectTab(tab) }, true)
            }
        }
    }
}

/** Hosts the four tabs and coalesces engine notifications into batched EDT refreshes. */
class FenceToolWindowPanel(project: Project, private val engine: FenceEngine, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val tabs = JBTabbedPane()
    private val overview = OverviewPanel(engine)
    private val activity = ActivityPanel(engine)
    private val permissions = PermissionsPanel(engine)
    private val drift = DriftPanel(engine)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        Disposer.register(parent, this)
        tabs.addTab(FenceToolWindowFactory.TAB_OVERVIEW, overview)
        tabs.addTab(FenceToolWindowFactory.TAB_ACTIVITY, activity)
        tabs.addTab(FenceToolWindowFactory.TAB_PERMISSIONS, permissions)
        tabs.addTab(FenceToolWindowFactory.TAB_DRIFT, drift)
        add(tabs, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(FenceListener.TOPIC, object : FenceListener {
            override fun eventsRecorded(events: List<FenceEvent>) = scheduleRefresh()
            override fun incidentUpdated(incident: Incident) = scheduleRefresh()
            override fun driftUpdated(drift: BehaviorDrift) = scheduleRefresh()
            override fun stateChanged() = scheduleRefresh()
        })
        refreshAll()
    }

    fun selectTab(name: String) {
        for (i in 0 until tabs.tabCount) {
            if (tabs.getTitleAt(i) == name) {
                tabs.selectedIndex = i
                return
            }
        }
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshAll() }, 200)
    }

    private fun refreshAll() {
        overview.refresh()
        activity.refresh()
        permissions.refresh()
        drift.refresh()
    }

    override fun dispose() {}
}
