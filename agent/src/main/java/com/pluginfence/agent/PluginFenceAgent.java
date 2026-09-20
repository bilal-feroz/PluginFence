package com.pluginfence.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Entry point of the PluginFence JVM agent.
 * <pre>
 *   -javaagent:/path/to/plugin-fence-agent.jar
 * </pre>
 * The agent jar's manifest declares {@code Boot-Class-Path: plugin-fence-bootstrap.jar}, which the
 * JVM resolves next to the agent jar and appends to the boot class path before {@code premain}
 * runs. That makes {@code com.pluginfence.bootstrap.*} visible to every class loader in the IDE
 * with a single identity. As a fallback (e.g. when the jar was copied without its sibling) the
 * agent looks for the bootstrap jar itself and appends it programmatically.
 * <p>
 * This class deliberately avoids referencing any bootstrap type until visibility is confirmed,
 * so a broken installation degrades to "agent not active" instead of a startup crash.
 */
public final class PluginFenceAgent {

    static final String BOOTSTRAP_PROBE_CLASS = "com.pluginfence.bootstrap.GuardBridge";
    static final String BOOTSTRAP_JAR_NAME = "plugin-fence-bootstrap.jar";

    private PluginFenceAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        install(args, inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst, "agentmain");
    }

    private static void install(String args, Instrumentation inst, String mode) {
        try {
            if (!ensureBootstrapVisible(inst)) {
                System.err.println("[PluginFence] ERROR: bootstrap classes are not on the boot class path; "
                        + "expected " + BOOTSTRAP_JAR_NAME + " next to the agent jar. Agent disabled.");
                return;
            }
            AgentInstaller.install(inst, mode, args);
        } catch (Throwable t) {
            // Never let agent initialisation take the IDE down.
            System.err.println("[PluginFence] ERROR: agent failed to initialise, IDE continues unprotected: " + t);
            t.printStackTrace(System.err);
        }
    }

    static boolean ensureBootstrapVisible(Instrumentation inst) {
        if (isBootstrapVisible()) {
            return true;
        }
        File jar = locateBootstrapJar();
        if (jar == null) {
            return false;
        }
        try {
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            System.err.println("[PluginFence] appended " + jar + " to the boot class path");
        } catch (Throwable t) {
            System.err.println("[PluginFence] could not append " + jar + " to the boot class path: " + t);
            return false;
        }
        return isBootstrapVisible();
    }

    private static boolean isBootstrapVisible() {
        try {
            Class<?> c = Class.forName(BOOTSTRAP_PROBE_CLASS, false, null);
            return c.getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static File locateBootstrapJar() {
        try {
            URL location = PluginFenceAgent.class.getProtectionDomain().getCodeSource().getLocation();
            File agentJar = new File(location.toURI());
            File sibling = new File(agentJar.getParentFile(), BOOTSTRAP_JAR_NAME);
            if (sibling.isFile()) {
                return sibling;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        String explicit = System.getProperty("pluginfence.bootstrap.jar");
        if (explicit != null && new File(explicit).isFile()) {
            return new File(explicit);
        }
        return null;
    }
}
