package com.pluginfence.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.Alarm
import com.pluginfence.engine.FenceEngine
import com.pluginfence.engine.FenceListener
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.Incident
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/** Anything the tool window can refresh when the engine reports new data. */
interface FencePanel {
    fun refresh()
    fun component(): JComponent
}

/** Lets a panel hand the user off to another tab - stat tiles and banners are all shortcuts. */
interface FenceNavigator {
    fun open(tab: String)

    /** Opens Activity pre-filtered to operations PluginFence actually stopped. */
    fun openPrevented()

    /** Opens Activity showing everything one plugin did. */
    fun openActivityFor(pluginName: String)
}

class FenceToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val engine = FenceEngine.getInstance()
        engine.start()
        val controller = FenceToolWindowController(engine, toolWindow)
        controllers[project] = controller
        Disposer.register(toolWindow.disposable, controller)
        Disposer.register(toolWindow.disposable) { controllers.remove(project) }

        val stats = engine.stats()
        Logger.getInstance(FenceToolWindowFactory::class.java).info(
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

        private val controllers = ConcurrentHashMap<Project, FenceToolWindowController>()

        fun show(project: Project, tab: String) {
            ApplicationManager.getApplication().invokeLater {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return@invokeLater
                toolWindow.activate({ controllers[project]?.open(tab) }, true)
            }
        }
    }
}

/**
 * Owns the four tabs and the refresh loop.
 *
 * Each tab is a real tool-window [Content], so the IDE renders the tab strip natively and each tab
 * gets its own action toolbar. Engine notifications arrive in bursts during an attack, so they are
 * coalesced onto the EDT and applied only to the tab the user is actually looking at; the others
 * are marked stale and refreshed when selected. That keeps a live demo smooth and, more
 * importantly, stops a background rebuild from closing a combo box under the user's cursor.
 */
class FenceToolWindowController(engine: FenceEngine, toolWindow: ToolWindow) : Disposable, FenceNavigator {

    private val overview = OverviewPanel(engine, this)
    private val activity = ActivityPanel(engine)
    private val permissions = PermissionsPanel(engine)
    private val drift = DriftPanel(engine)

    private val panels = linkedMapOf(
        FenceToolWindowFactory.TAB_OVERVIEW to overview as FencePanel,
        FenceToolWindowFactory.TAB_ACTIVITY to activity as FencePanel,
        FenceToolWindowFactory.TAB_PERMISSIONS to permissions as FencePanel,
        FenceToolWindowFactory.TAB_DRIFT to drift as FencePanel,
    )
    private val contents = LinkedHashMap<String, Content>()
    private val stale = HashSet<String>()
    private val contentManager = toolWindow.contentManager
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val engineRef = engine

    init {
        val factory = ContentFactory.getInstance()
        panels.forEach { (name, panel) ->
            val content = factory.createContent(panel.component(), name, false)
            content.isCloseable = false
            content.isPinned = true
            contents[name] = content
            contentManager.addContent(content)
        }

        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation != ContentManagerEvent.ContentOperation.add) return
                val name = contents.entries.firstOrNull { it.value === event.content }?.key ?: return
                if (stale.remove(name)) panels[name]?.refresh()
            }
        })

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            FenceListener.TOPIC,
            object : FenceListener {
                override fun eventsRecorded(events: List<FenceEvent>) = scheduleRefresh()
                override fun incidentUpdated(incident: Incident) = scheduleRefresh()
                override fun driftUpdated(drift: BehaviorDrift) = scheduleRefresh()
                override fun stateChanged() = scheduleRefresh()
            },
        )

        panels.values.forEach { it.refresh() }
        updateTabTitles()
    }

    override fun open(tab: String) {
        val content = contents[tab] ?: return
        if (stale.remove(tab)) panels[tab]?.refresh()
        contentManager.setSelectedContent(content, true)
    }

    override fun openPrevented() {
        activity.showPreventedOnly()
        open(FenceToolWindowFactory.TAB_ACTIVITY)
    }

    override fun openActivityFor(pluginName: String) {
        activity.showPlugin(pluginName)
        open(FenceToolWindowFactory.TAB_ACTIVITY)
    }

    private fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshSelected() }, REFRESH_DELAY_MS)
    }

    private fun refreshSelected() {
        val selected = contentManager.selectedContent
        val selectedName = contents.entries.firstOrNull { it.value === selected }?.key
        panels.forEach { (name, panel) ->
            if (name == selectedName) panel.refresh() else stale.add(name)
        }
        updateTabTitles()
    }

    /**
     * Tab titles carry the numbers that decide where to look next, so the user can triage without
     * opening every tab: "Activity 128", "Drift 1".
     */
    private fun updateTabTitles() {
        val stats = engineRef.stats()
        title(FenceToolWindowFactory.TAB_OVERVIEW, engineRef.incidents().size)
        title(FenceToolWindowFactory.TAB_ACTIVITY, stats.eventsRecorded.toInt())
        title(FenceToolWindowFactory.TAB_PERMISSIONS, stats.pluginsMonitored)
        title(FenceToolWindowFactory.TAB_DRIFT, stats.behaviorChanges)
    }

    private fun title(tab: String, count: Int) {
        val content = contents[tab] ?: return
        val text = if (count > 0) "$tab  $count" else tab
        if (content.displayName != text) content.displayName = text
    }

    override fun dispose() {}

    private companion object {
        const val REFRESH_DELAY_MS = 200
    }
}
