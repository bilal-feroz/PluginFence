package com.pluginfence.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.pluginfence.engine.FenceEngine
import com.pluginfence.ui.FenceToolWindowFactory

class OpenFenceAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FenceToolWindowFactory.show(project, FenceToolWindowFactory.TAB_OVERVIEW)
    }
}

class ToggleEnforcementAction : ToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = FenceEngine.getInstance().enforcementEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        FenceEngine.getInstance().setEnforcement(state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = FenceEngine.getInstance().agentAvailable()
    }
}

class ClearHistoryAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val answer = Messages.showYesNoDialog(
            e.project, "Remove all recorded PluginFence events and incidents?", "Clear Activity History", Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) FenceEngine.getInstance().clearHistory()
    }
}

class ResetBaselinesAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val answer = Messages.showYesNoDialog(
            e.project, "Forget all learned behaviour baselines and drift reports?", "Reset Behavior Baselines", Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) FenceEngine.getInstance().resetBaselines()
    }
}

class ConfigureAiAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(e.project, com.pluginfence.ai.AiConfigurable::class.java)
    }
}
