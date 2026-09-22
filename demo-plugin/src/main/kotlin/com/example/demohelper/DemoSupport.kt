package com.example.demohelper

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared plumbing for the demo plugin: fixture locations, a tiny local HTTP service that stands
 * in for "the plugin's backend", and a log the tool window displays.
 *
 * Everything here is deliberately harmless. The plugin never touches real credentials and never
 * sends data anywhere outside the machine.
 */
object DemoSupport {

    const val PLUGIN_ID = "com.example.demo-helper"

    /**
     * Every action this plugin can register, in display order. 1.0.0 declares only the first;
     * 1.1.0 declares all five. Looking them up by id keeps both builds working from one list and
     * avoids expanding the action group by hand, which the platform forbids.
     */
    val ACTION_IDS = listOf(
        "DemoHelper.Normal",
        "DemoHelper.SecretRead",
        "DemoHelper.Exfil",
        "DemoHelper.Process",
        "DemoHelper.Attack",
    )

    /** Documentation-only address (RFC 5737 TEST-NET-2); never routable on the public internet. */
    const val EXFIL_HOST = "198.51.100.42"
    const val EXFIL_PORT = 8080

    private val log = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var server: HttpServer? = null

    /** Read from this plugin's own descriptor (patched at build time), so no platform registry API is needed. */
    val version: String by lazy {
        runCatching {
            DemoSupport::class.java.getResourceAsStream("/META-INF/plugin.xml")?.use { String(it.readAllBytes()) }
                ?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1) }
        }.getOrNull() ?: "?"
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** Root of the fake home directory: `<fixtures>/home` (contains .ssh/id_rsa etc). */
    fun fakeHome(): Path = fixturesRoot().resolve("home")

    fun fakeSshKey(): Path = fakeHome().resolve(".ssh").resolve("id_rsa")

    fun fixturesRoot(): Path {
        val configured = System.getProperty("pluginfence.demo.fixtures")
        if (!configured.isNullOrBlank()) {
            val p = Paths.get(configured)
            if (Files.isDirectory(p)) return p
        }
        return extractedFixtures()
    }

    /** Without the system property, unpack bundled fixtures into the IDE's system directory once. */
    private fun extractedFixtures(): Path {
        val root = Paths.get(PathManager.getSystemPath(), "pluginfence-demo")
        val marker = root.resolve(".extracted")
        if (!Files.exists(marker)) {
            for (name in listOf("home/.ssh/id_rsa", "project/notes.txt")) {
                val target = root.resolve(name)
                Files.createDirectories(target.parent)
                DemoSupport::class.java.getResourceAsStream("/demo-fixtures/$name")?.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            }
            Files.createDirectories(root)
            Files.writeString(marker, "ok")
        }
        return root
    }

    /** An ordinary file inside the open project (falls back to the fixture project file). */
    fun projectFile(project: Project?): Path {
        val base = project?.basePath ?: ProjectManager.getInstance().openProjects.firstOrNull()?.basePath
        if (base != null) {
            for (candidate in listOf("README.md", "settings.gradle.kts", "build.gradle.kts", ".gitignore")) {
                val p = Paths.get(base, candidate)
                if (Files.isRegularFile(p)) return p
            }
        }
        return fixturesRoot().resolve("project").resolve("notes.txt")
    }

    // --- local service --------------------------------------------------------------------------

    /** Starts (once) a loopback HTTP server that plays the role of the plugin's legitimate backend. */
    fun localServicePort(): Int {
        server?.let { return it.address.port }
        synchronized(this) {
            server?.let { return it.address.port }
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/health") { exchange ->
                val body = "demo-helper-ok".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            s.start()
            server = s
            return s.address.port
        }
    }

    fun stopLocalService() {
        server?.stop(0)
        server = null
    }

    // --- log ------------------------------------------------------------------------------------

    private val ideLog = com.intellij.openapi.diagnostic.Logger.getInstance(DemoSupport::class.java)

    fun log(message: String) {
        val line = "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))}  $message"
        ideLog.info("Demo Helper: $message")
        log.add(line)
        while (log.size > 200) log.removeAt(0)
        listeners.forEach { l -> ApplicationManager.getApplication().invokeLater { l() } }
    }

    fun logLines(): List<String> = log.toList()

    fun addLogListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeLogListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Runs an operation off the EDT and logs how it went from the plugin's point of view. */
    fun attempt(title: String, block: () -> String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            log("> $title")
            try {
                val result = block()
                log("  OK: $result")
            } catch (e: SecurityException) {
                log("  DENIED: ${e.message}")
            } catch (e: Throwable) {
                log("  FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun javaExecutable(): String {
        val home = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Paths.get(home, "bin", exe).toString()
    }
}
