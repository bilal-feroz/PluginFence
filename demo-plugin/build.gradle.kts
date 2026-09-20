plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

description = "Demo Helper 1.0.0 - a benign third-party plugin used to establish a behaviour baseline"
version = "1.0.0"

val platformVersion = providers.gradleProperty("platformVersion").get()
val javaToolchain = providers.gradleProperty("javaToolchainVersion").get().toInt()

kotlin { jvmToolchain(javaToolchain) }
java { toolchain { languageVersion = JavaLanguageVersion.of(javaToolchain) } }

sourceSets {
    main {
        resources.srcDir("src/shared-resources")
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
