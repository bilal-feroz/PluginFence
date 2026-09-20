package com.example.demohelper

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * What a well-behaved plugin does: read a file from the open project and talk to its own
 * local backend. This is the behaviour Demo Helper 1.0.0 establishes as its baseline.
 */
class NormalBehaviorAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        run(e)
    }

    companion object {
        fun run(e: AnActionEvent?) {
            val project = e?.project
            DemoSupport.attempt("Normal behaviour: read a project file") {
                val file = DemoSupport.projectFile(project)
                val text = Files.readString(file)
                "read ${file.fileName} (${text.length} chars)"
            }
            DemoSupport.attempt("Normal behaviour: call local service") {
                val port = DemoSupport.localServicePort()
                val connection = URI.create("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val body = connection.inputStream.use { String(it.readAllBytes()) }
                connection.disconnect()
                "127.0.0.1:$port replied '$body'"
            }
        }
    }
}
