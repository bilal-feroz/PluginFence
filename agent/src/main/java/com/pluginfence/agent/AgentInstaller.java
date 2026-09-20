package com.pluginfence.agent;

import com.pluginfence.bootstrap.GuardBridge;
import com.pluginfence.bootstrap.GuardLog;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wires the transformer into the JVM once bootstrap visibility has been confirmed. */
final class AgentInstaller {

    private static volatile FenceTransformer transformer;

    private AgentInstaller() {
    }

    static void install(Instrumentation inst, String mode, String args) {
        if (transformer != null) {
            GuardLog.info("agent already installed; ignoring second " + mode);
            return;
        }
        AgentConfig config = AgentConfig.fromSystemProperties();
        GuardLog.setDebug(config.debug);
        PluginLoaderRegistry registry = new PluginLoaderRegistry(config);
        FenceTransformer t = new FenceTransformer(config, registry);
        inst.addTransformer(t, false);
        transformer = t;

        GuardBridge.setDiagnostics(() -> diagnostics(t, config));
        GuardBridge.markAgentInstalled("PluginFence agent " + GuardBridge.VERSION + " via " + mode
                + " on " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        GuardLog.info(GuardBridge.agentInfo() + "; " + HookRules.all().size() + " call-site rules"
                + (config.forcedPackagePrefixes.isEmpty() ? "" : "; forced packages " + config.forcedPackagePrefixes));
    }

    private static Map<String, String> diagnostics(FenceTransformer t, AgentConfig config) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("agent.mode", GuardBridge.agentInfo());
        m.put("rules", Integer.toString(HookRules.all().size()));
        m.put("classes.seen", Long.toString(t.classesSeen()));
        m.put("classes.instrumented", Long.toString(t.classesInstrumented()));
        m.put("callSites.rewritten", Long.toString(t.callSitesRewritten()));
        m.put("transform.failures", Long.toString(t.failures()));
        m.put("instrumentBundled", Boolean.toString(config.instrumentBundled));
        return m;
    }
}
