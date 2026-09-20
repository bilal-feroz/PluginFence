import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // Single place for plugin versions; subprojects apply them without a version.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
        id("org.jetbrains.intellij.platform") version "2.19.0"
        id("com.gradleup.shadow") version "9.6.1"
    }
}

plugins {
    // Auto-provisions the JDK required by the target IntelliJ Platform (Java 25 for 2026.2).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Settings-level plugin recommended for multi-module IntelliJ Platform builds.
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

rootProject.name = "plugin-fence"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include(
    ":bootstrap",          // tiny, dependency-free classes appended to the JVM boot class path
    ":agent",              // java.lang.instrument agent (ASM call-site rewriting)
    ":intellij-plugin",    // PluginFence IntelliJ plugin: policy engine, baselines, drift, UI
    ":demo-plugin",        // Demo Helper 1.0.0 – benign behaviour only
    ":demo-plugin-update", // Demo Helper 1.1.0 – same plugin ID, adds suspicious behaviour
)
