plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

description = "Demo Helper 1.1.0 - the same plugin ID as 1.0.0, plus deliberately suspicious actions"
version = "1.1.0"

val platformVersion = providers.gradleProperty("platformVersion").get()
val javaToolchain = providers.gradleProperty("javaToolchainVersion").get().toInt()

kotlin {
    jvmToolchain(javaToolchain)
    compilerOptions { jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(javaToolchain) } }

// 1.1.0 is a genuinely different build: the benign 1.0.0 sources plus the "update" source set
// (attack actions) and its own plugin.xml that registers them. 1.0.0 does not contain this code.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("../demo-plugin/src/main/kotlin", "../demo-plugin/src/update/kotlin"))
        resources.setSrcDirs(listOf("../demo-plugin/src/update/resources", "../demo-plugin/src/shared-resources"))
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "com.example.demo-helper"
        name = "Demo Helper"
        version = project.version.toString()
        vendor { name = "Example Corp" }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

tasks.buildPlugin {
    archiveBaseName = "demo-helper"
}
