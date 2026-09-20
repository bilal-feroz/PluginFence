plugins {
    base
}

group = "com.pluginfence"
version = providers.gradleProperty("pluginFenceVersion").get()

allprojects {
    group = "com.pluginfence"
    version = rootProject.version
}

// ---------------------------------------------------------------------------------------------
// Convenience aggregate tasks
// ---------------------------------------------------------------------------------------------

tasks.register("assembleAgent") {
    group = "pluginfence"
    description = "Builds the JVM agent + bootstrap jars into agent/build/dist"
    dependsOn(":agent:agentDist")
}

tasks.register("buildDemoPlugins") {
    group = "pluginfence"
    description = "Builds Demo Helper 1.0.0 and 1.1.0 plugin zips into build/demo"
    dependsOn(":demo-plugin:buildPlugin", ":demo-plugin-update:buildPlugin")
    doLast {
        val out = layout.buildDirectory.dir("demo").get().asFile
        out.mkdirs()
        listOf(":demo-plugin", ":demo-plugin-update").forEach { path ->
            val dist = project(path).layout.buildDirectory.dir("distributions").get().asFile
            dist.listFiles { f -> f.extension == "zip" }?.forEach { zip ->
                zip.copyTo(out.resolve(zip.name), overwrite = true)
            }
        }
        println("Demo plugin zips written to ${out.absolutePath}")
    }
}

tasks.register("runFenceIde") {
    group = "pluginfence"
    description = "Starts IntelliJ IDEA with the PluginFence agent, the PluginFence plugin and Demo Helper 1.0.0"
    dependsOn(":intellij-plugin:runFenceIde")
}

tasks.register("runFenceIdeUpdated") {
    group = "pluginfence"
    description = "Starts IntelliJ IDEA with the PluginFence agent, the PluginFence plugin and Demo Helper 1.1.0"
    dependsOn(":intellij-plugin:runFenceIdeUpdated")
}
