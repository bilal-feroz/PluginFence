import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

description = "PluginFence IntelliJ plugin: policy engine, baselines, drift detection, correlation and UI"

val platformVersion = providers.gradleProperty("platformVersion").get()
val javaToolchain = providers.gradleProperty("javaToolchainVersion").get().toInt()

kotlin {
    jvmToolchain(javaToolchain)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaToolchain)
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion)
    }

    // The bootstrap classes are provided by the agent on the boot class path at runtime.
    // They must NOT be bundled: IntelliJ's plugin class loader is self-first and a second copy
    // would break class identity with the instrumented call sites.
    compileOnly(project(":bootstrap"))

    testImplementation(project(":bootstrap"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.pluginfence"
        name = "PluginFence"
        version = project.version.toString()
        description = providers.fileContents(layout.projectDirectory.file("src/main/resources/META-INF/description.html")).asText
        vendor {
            name = "PluginFence"
            url = "https://github.com/bilal-feroz/Plugin-Fence"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
    // One shared sandbox for every run task so baselines and policies survive between runs.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

// ---------------------------------------------------------------------------------------------
// Running the IDE with the agent attached
// ---------------------------------------------------------------------------------------------

val agentDistDir = project(":agent").layout.buildDirectory.dir("dist")
val demoFixtures = rootProject.layout.projectDirectory.dir("demo-fixtures")

fun RunIdeTask.attachPluginFenceAgent() {
    dependsOn(":agent:agentDist")
    val agentJar = agentDistDir.map { it.file("plugin-fence-agent.jar").asFile.absolutePath }
    val fixtures = demoFixtures.asFile.absolutePath
    val projectToOpen = rootProject.layout.projectDirectory.asFile.absolutePath
    val debug = providers.gradleProperty("pluginfenceDebug").orElse("false")
    val autorun = providers.gradleProperty("demoAutorun").orElse("")
    val exitAfter = providers.gradleProperty("demoExitAfter").orElse("")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("-javaagent:${agentJar.get()}")
            add("-Dpluginfence.demo.fixtures=$fixtures")
            add("-Dpluginfence.debug=${debug.get()}")
            // Scripted demo / smoke test: ./gradlew runFenceIde -PdemoAutorun=normal -PdemoExitAfter=30000
            if (autorun.get().isNotBlank()) add("-Dpluginfence.demo.autorun=${autorun.get()}")
            if (exitAfter.get().isNotBlank()) add("-Dpluginfence.demo.exitAfter=${exitAfter.get()}")
        }
    })
    // Open the repository itself so "project file" reads have a project to be relative to.
    argumentProviders.add(CommandLineArgumentProvider { listOf(projectToOpen) })
}

tasks {
    runIde {
        attachPluginFenceAgent()
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    buildSearchableOptions {
        enabled = false
    }
}

// Demo run configurations. Both share one sandbox so the Demo Helper 1.0.0 baseline recorded in
// the first run is still there when 1.1.0 - a genuinely different build with the same plugin ID -
// is started by the second one. prepareSandbox syncs the plugins directory, so switching between
// the two tasks swaps the installed demo plugin version deterministically.
val sharedSandbox = intellijPlatform.sandboxContainer.map { it.dir("IU-$platformVersion") }

intellijPlatformTesting.runIde.register("runFenceIde") {
    sandboxDirectory = sharedSandbox
    plugins {
        localPlugin(dependencies.project(":demo-plugin"))
    }
    task {
        attachPluginFenceAgent()
    }
}

intellijPlatformTesting.runIde.register("runFenceIdeUpdated") {
    sandboxDirectory = sharedSandbox
    plugins {
        localPlugin(dependencies.project(":demo-plugin-update"))
    }
    task {
        attachPluginFenceAgent()
    }
}
