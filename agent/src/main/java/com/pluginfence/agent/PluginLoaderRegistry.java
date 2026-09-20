package com.pluginfence.agent;

import com.pluginfence.bootstrap.GuardBridge;
import com.pluginfence.bootstrap.GuardLog;
import com.pluginfence.bootstrap.PluginIdentity;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves the plugin that owns a class loader and decides whether classes from that loader
 * should be instrumented.
 * <p>
 * IntelliJ defines every plugin's classes through a {@code com.intellij.ide.plugins.cl.PluginClassLoader}
 * that exposes its {@code PluginId} and {@code PluginDescriptor}. Those are read reflectively (this agent
 * does not depend on IntelliJ) exactly once per loader and cached; the resulting {@link PluginIdentity}
 * is registered with {@link GuardBridge} and its token is baked into rewritten call sites, so the
 * runtime hook path is a single array lookup with no stack walking.
 * <p>
 * Attribution is evidence-based: if the loader is not a plugin loader or reflection fails, the
 * class is attributed to {@link PluginIdentity#UNKNOWN}, never to a guessed plugin.
 */
final class PluginLoaderRegistry {

    /** Per-loader decision. */
    static final class LoaderInfo {
        final PluginIdentity identity;
        final int token;
        final boolean instrument;
        final String skipReason;

        LoaderInfo(PluginIdentity identity, int token, boolean instrument, String skipReason) {
            this.identity = identity;
            this.token = token;
            this.instrument = instrument;
            this.skipReason = skipReason;
        }
    }

    private static final LoaderInfo NOT_A_PLUGIN = new LoaderInfo(PluginIdentity.UNKNOWN, 0, false, "not a plugin class loader");

    private final AgentConfig config;
    private final Map<ClassLoader, LoaderInfo> cache = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile LoaderInfo forcedInfo;

    PluginLoaderRegistry(AgentConfig config) {
        this.config = config;
    }

    /** Identity used for classes matched by {@code pluginfence.agent.instrument.packages}. */
    LoaderInfo forcedInfo(ClassLoader loader) {
        LoaderInfo info = forcedInfo;
        if (info == null) {
            String[] parts = (config.testIdentity + "||").split("\\|", -1);
            PluginIdentity identity = new PluginIdentity(
                    parts[0].isEmpty() ? "com.pluginfence.forced" : parts[0],
                    parts[1].isEmpty() ? "Forced instrumentation" : parts[1],
                    parts[2].isEmpty() ? null : parts[2],
                    "test", false, "forced:" + describeLoader(loader), loader);
            info = new LoaderInfo(identity, GuardBridge.registerIdentity(identity), true, null);
            forcedInfo = info;
        }
        return info;
    }

    LoaderInfo describe(ClassLoader loader) {
        if (loader == null) {
            return NOT_A_PLUGIN;
        }
        LoaderInfo cached = cache.get(loader);
        if (cached != null) {
            return cached;
        }
        LoaderInfo info = resolve(loader);
        cache.put(loader, info);
        if (config.debug) {
            GuardLog.info("loader " + describeLoader(loader) + " -> " + info.identity
                    + (info.instrument ? " [instrument]" : " [skip: " + info.skipReason + "]"));
        }
        return info;
    }

    private LoaderInfo resolve(ClassLoader loader) {
        if (!AgentConfig.PLUGIN_CLASS_LOADER.equals(loader.getClass().getName())) {
            return NOT_A_PLUGIN;
        }
        String pluginId = null;
        String name = null;
        String version = null;
        String vendor = null;
        boolean bundled = false;
        try {
            Object id = invoke(loader, "getPluginId");
            pluginId = id == null ? null : String.valueOf(invokeOrToString(id, "getIdString"));
            Object descriptor = invoke(loader, "getPluginDescriptor");
            if (descriptor != null) {
                name = str(invoke(descriptor, "getName"));
                version = str(invoke(descriptor, "getVersion"));
                vendor = str(invoke(descriptor, "getVendor"));
                Object b = invoke(descriptor, "isBundled");
                bundled = b instanceof Boolean && (Boolean) b;
            }
        } catch (Throwable t) {
            GuardLog.warn("could not resolve plugin identity for " + describeLoader(loader), t);
        }
        PluginIdentity identity = new PluginIdentity(pluginId, name, version, vendor, bundled, describeLoader(loader), loader);
        if (AgentConfig.OWN_PLUGIN_ID.equals(pluginId)) {
            return new LoaderInfo(identity, 0, false, "PluginFence itself is exempt");
        }
        if (bundled && !config.instrumentBundled) {
            return new LoaderInfo(identity, 0, false, "bundled platform plugin");
        }
        int token = GuardBridge.registerIdentity(identity);
        return new LoaderInfo(identity, token, true, null);
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = findMethod(target.getClass(), method);
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object invokeOrToString(Object target, String method) {
        try {
            Object v = invoke(target, method);
            return v == null ? target.toString() : v;
        } catch (Throwable t) {
            return target.toString();
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // try interfaces and superclasses
            }
            for (Class<?> itf : c.getInterfaces()) {
                Method m = findMethod(itf, name);
                if (m != null) return m;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String describeLoader(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        String s;
        try {
            s = loader.toString();
        } catch (Throwable t) {
            s = loader.getClass().getName();
        }
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
