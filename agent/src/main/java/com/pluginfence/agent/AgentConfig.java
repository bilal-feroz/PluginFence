package com.pluginfence.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent configuration read from system properties at startup.
 * <ul>
 *   <li>{@code pluginfence.agent.instrument.packages} - comma separated package prefixes that are
 *       instrumented regardless of class loader (used by the forked-JVM integration tests).</li>
 *   <li>{@code pluginfence.agent.test.identity} - {@code id|name|version} identity attributed to
 *       those packages.</li>
 *   <li>{@code pluginfence.agent.instrumentBundled} - also instrument plugins bundled with the IDE
 *       (default {@code false}; bundled JetBrains plugins are treated as part of the platform).</li>
 *   <li>{@code pluginfence.debug} - verbose diagnostics on stderr.</li>
 * </ul>
 */
final class AgentConfig {

    static final String PLUGIN_CLASS_LOADER = "com.intellij.ide.plugins.cl.PluginClassLoader";
    static final String OWN_PLUGIN_ID = "com.pluginfence";

    final List<String> forcedPackagePrefixes;
    final String testIdentity;
    final boolean instrumentBundled;
    final boolean debug;

    private AgentConfig(List<String> forcedPackagePrefixes, String testIdentity, boolean instrumentBundled, boolean debug) {
        this.forcedPackagePrefixes = forcedPackagePrefixes;
        this.testIdentity = testIdentity;
        this.instrumentBundled = instrumentBundled;
        this.debug = debug;
    }

    static AgentConfig fromSystemProperties() {
        List<String> prefixes = new ArrayList<>();
        String raw = System.getProperty("pluginfence.agent.instrument.packages", "");
        for (String p : raw.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                prefixes.add(trimmed.replace('.', '/'));
            }
        }
        return new AgentConfig(
                Collections.unmodifiableList(prefixes),
                System.getProperty("pluginfence.agent.test.identity", ""),
                Boolean.getBoolean("pluginfence.agent.instrumentBundled"),
                Boolean.getBoolean("pluginfence.debug"));
    }

    boolean isForcedPackage(String internalClassName) {
        for (String prefix : forcedPackagePrefixes) {
            if (internalClassName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
