package com.example.demohelper

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scripted self-test mode used by `scripts/smoke-test.*` and the demo docs:
 *
 *  -Dpluginfence.demo.autorun=normal,secret,exfil,process,attack   actions to run, in order
 *  -Dpluginfence.demo.autorun.delay=8000                            ms to wait before the first action
 *  -Dpluginfence.demo.exitAfter=40000                               ms after which the IDE exits (state is saved)
 *  -Dpluginfence.demo.openToolWindows=true                          activate the PluginFence and Demo Helper tool windows first
 *
 * Without the autorun property this activity does nothing. Actions that this plugin version does
 * not register (e.g. "secret" in 1.0.0) are skipped, which keeps the script identical for both builds.
 */
class DemoAutorun : ProjectActivity {

    override suspend fun execute(project: Project) {
        val script = System.getProperty("pluginfence.demo.autorun")?.trim().orEmpty()
        if (script.isEmpty() || !started.compareAndSet(false, true)) return
        val delay = System.getProperty("pluginfence.demo.autorun.delay")?.toLongOrNull() ?: 8_000L
        val exitAfter = System.getProperty("pluginfence.demo.exitAfter")?.toLongOrNull()
        DemoSupport.log("autorun: $script (delay ${delay}ms${exitAfter?.let { ", exit after ${it}ms" } ?: ""})")

        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(delay)
            if (java.lang.Boolean.getBoolean("pluginfence.demo.openToolWindows")) {
                ApplicationManager.getApplication().invokeAndWait {
                    val manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    manager.getToolWindow("Demo Helper")?.activate(null, false)
                    manager.getToolWindow("PluginFence")?.activate(null, false)
                }
                Thread.sleep(1_500)
            }
            for (step in script.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
                val actionId = ACTIONS[step]
                if (actionId == null) {
                    DemoSupport.log("autorun: unknown step '$step'")
                    continue
                }
                val action = ActionManager.getInstance().getAction(actionId)
                if (action == null) {
                    DemoSupport.log("autorun: '$step' is not available in Demo Helper ${DemoSupport.version}; skipped")
                    continue
                }
                DemoSupport.log("autorun: running '$step'")
                ApplicationManager.getApplication().invokeAndWait {
                    val event = AnActionEvent.createEvent(
                        action, DataContext { key -> if (key == CommonDataKeys.PROJECT.name) project else null },
                        Presentation(), ActionPlaces.UNKNOWN, ActionUiKind.NONE, null,
                    )
                    action.actionPerformed(event)
                }
                Thread.sleep(2_500)
            }
            DemoSupport.log("autorun: finished")
            if (exitAfter != null) {
                Thread.sleep(exitAfter.coerceAtLeast(0))
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().exit(true, true, false)
                }
            }
        }
    }

    companion object {
        private val started = AtomicBoolean(false)
        private val ACTIONS = mapOf(
            "normal" to "DemoHelper.Normal",
            "secret" to "DemoHelper.SecretRead",
            "exfil" to "DemoHelper.Exfil",
            "process" to "DemoHelper.Process",
            "attack" to "DemoHelper.Attack",
        )
    }
}
