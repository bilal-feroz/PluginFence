plugins {
    `java-library`
}

description = "PluginFence bootstrap bridge: dependency-free classes appended to the JVM boot class path"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaToolchainVersion").get().toInt())
    }
}

tasks.withType<JavaCompile>().configureEach {
    // The bootstrap jar must load on any JetBrains Runtime that could host the agent.
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName = "plugin-fence-bootstrap"
    archiveVersion = ""
    manifest {
        attributes(
            "Implementation-Title" to "PluginFence Bootstrap",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "com.pluginfence.bootstrap",
        )
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
