package com.example.demohelper

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * One button per action registered in the "DemoHelper.Actions" group, plus a log of what the
 * plugin experienced. 1.0.0 registers a single benign action; 1.1.0 registers four more.
 */
class DemoHelperToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = JPanel(BorderLayout())
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12, 6, 12)
            add(JBLabel("Demo Helper ${DemoSupport.version}").apply { font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 3f) })
            add(JBLabel("A third-party plugin under PluginFence observation. Every button below performs a real operation.").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 12, 8, 12)
        }
        // Resolved by id rather than by expanding the action group: ActionGroup.getChildren(null) is
        // forbidden by the platform (it logs SEVERE and raises the red "IDE internal error" badge).
        // Unregistered ids simply resolve to null, which is what makes 1.0.0 show one button and
        // 1.1.0 show five without either build knowing about the other.
        val actions = DemoSupport.ACTION_IDS.mapNotNull { ActionManager.getInstance().getAction(it) }
        for (action in actions) {
            val text = action.templatePresentation.text ?: action.javaClass.simpleName
            val button = JButton(text).apply {
                toolTipText = action.templatePresentation.description
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
                addActionListener { invoke(action, project) }
            }
            buttons.add(button)
            buttons.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
        }

        val logArea = JBTextArea().apply {
            isEditable = false
            font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size - 1))
            border = JBUI.Borders.empty(6)
        }
        val listener: () -> Unit = {
            logArea.text = DemoSupport.logLines().joinToString("\n")
            logArea.caretPosition = logArea.document.length
        }
        DemoSupport.addLogListener(listener)
        Disposer.register(toolWindow.disposable) { DemoSupport.removeLogListener(listener); DemoSupport.stopLocalService() }
        listener()

        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(buttons, BorderLayout.CENTER)
        }
        root.add(top, BorderLayout.NORTH)
        root.add(JBScrollPane(logArea), BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun invoke(action: AnAction, project: Project) {
        val event = AnActionEvent.createEvent(
            action, DataContext { key -> if (key == com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name) project else null },
            Presentation(), ActionPlaces.TOOLWINDOW_CONTENT, com.intellij.openapi.actionSystem.ActionUiKind.NONE, null,
        )
        action.actionPerformed(event)
    }
}
