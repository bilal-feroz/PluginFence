package com.pluginfence.engine

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity

/** Registers the control plane as early as the platform allows (before projects open). */
class FenceAppLifecycleListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        FenceEngine.getInstance().start()
    }
}

/** Keeps the "inside project" roots current. */
class FenceProjectListener : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        FenceEngine.getInstance().scope.projectClosed(project)
    }
}

/** Safety net: guarantees start-up even if the lifecycle listener did not fire (e.g. headless). */
class FenceProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val engine = FenceEngine.getInstance()
        engine.start()
        engine.scope.projectOpened(project)
    }
}
