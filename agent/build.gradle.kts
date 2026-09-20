import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
}

description = "PluginFence JVM instrumentation agent (java.lang.instrument + ASM call-site rewriting)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("javaToolchainVersion").get().toInt())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

dependencies {
    // Bootstrap classes are provided at runtime by the boot class path (Boot-Class-Path manifest entry),
    // so they must not be shaded into the agent jar.
    compileOnly(project(":bootstrap"))
    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(project(":bootstrap"))
    // asm-util gives us CheckClassAdapter so tests can verify rewritten bytecode.
    testImplementation("org.ow2.asm:asm-util:9.10.1")
    // Real Kotlin stdlib so the kotlin.io delegation path is exercised end to end.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val agentManifest = mapOf(
    "Premain-Class" to "com.pluginfence.agent.PluginFenceAgent",
    "Agent-Class" to "com.pluginfence.agent.PluginFenceAgent",
    "Can-Retransform-Classes" to "true",
    "Can-Redefine-Classes" to "false",
    // Resolved relative to the agent jar: the bootstrap jar is placed next to it by agentDist.
    "Boot-Class-Path" to "plugin-fence-bootstrap.jar",
    "Implementation-Title" to "PluginFence Agent",
    "Implementation-Version" to project.version,
)

tasks.jar {
    manifest { attributes(agentManifest) }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName = "plugin-fence-agent"
    archiveClassifier = ""
    archiveVersion = ""
    manifest { attributes(agentManifest) }
    // IntelliJ ships its own ASM; relocate ours so the two never collide on the system class path.
    relocate("org.objectweb.asm", "com.pluginfence.agent.shaded.asm")
    exclude("META-INF/maven/**", "META-INF/LICENSE*", "META-INF/NOTICE*", "module-info.class")
}

val agentDist by tasks.registering(Copy::class) {
    group = "pluginfence"
    description = "Assembles plugin-fence-agent.jar + plugin-fence-bootstrap.jar side by side"
    dependsOn(tasks.shadowJar, ":bootstrap:jar")
    from(tasks.shadowJar.map { it.archiveFile })
    from(project(":bootstrap").tasks.named<Jar>("jar").map { it.archiveFile })
    into(layout.buildDirectory.dir("dist"))
}

tasks.assemble { dependsOn(agentDist) }

// Unit tests: in-process ASM rewriting, no agent attached.
tasks.test {
    useJUnitPlatform { excludeTags("agent") }
}

// Integration tests: a forked JVM started with the real -javaagent and the real bootstrap jar.
val agentTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs tests inside a JVM started with -javaagent:plugin-fence-agent.jar"
    dependsOn(agentDist)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("agent") }
    val distDir = layout.buildDirectory.dir("dist")
    inputs.dir(distDir)
    doFirst {
        val agentJar = distDir.get().file("plugin-fence-agent.jar").asFile
        jvmArgs(
            "-javaagent:${agentJar.absolutePath}",
            "-Dpluginfence.agent.instrument.packages=com.pluginfence.agent.subject",
            "-Dpluginfence.agent.test.identity=com.pluginfence.test.subject|Subject Plugin|9.9.9",
            "-Dpluginfence.debug=true",
        )
    }
}

tasks.check { dependsOn(agentTest) }
