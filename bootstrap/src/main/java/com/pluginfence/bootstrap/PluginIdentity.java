package com.pluginfence.bootstrap;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Attribution metadata for the code that performed an intercepted operation.
 * Resolved by the agent from the IntelliJ plugin class loader that defined the calling class.
 * When attribution is not possible the {@link #UNKNOWN} identity is used - never a guessed plugin.
 */
public final class PluginIdentity {

    public static final String UNKNOWN_DISPLAY_NAME = "Unknown third-party plugin";

    /** Token 0 is always the unknown identity. */
    public static final PluginIdentity UNKNOWN =
            new PluginIdentity(null, UNKNOWN_DISPLAY_NAME, null, null, false, "unknown", null);

    private final String pluginId;
    private final String pluginName;
    private final String pluginVersion;
    private final String vendor;
    private final boolean bundled;
    private final String loaderDescription;
    private final WeakReference<ClassLoader> loaderRef;

    public PluginIdentity(String pluginId,
                          String pluginName,
                          String pluginVersion,
                          String vendor,
                          boolean bundled,
                          String loaderDescription,
                          ClassLoader loader) {
        this.pluginId = pluginId == null || pluginId.isBlank() ? null : pluginId;
        this.pluginName = pluginName == null || pluginName.isBlank()
                ? (this.pluginId == null ? UNKNOWN_DISPLAY_NAME : this.pluginId)
                : pluginName;
        this.pluginVersion = pluginVersion == null || pluginVersion.isBlank() ? null : pluginVersion;
        this.vendor = vendor;
        this.bundled = bundled;
        this.loaderDescription = loaderDescription == null ? "" : loaderDescription;
        this.loaderRef = loader == null ? null : new WeakReference<>(loader);
    }

    /** True when a real plugin ID could be resolved. */
    public boolean isKnown() {
        return pluginId != null;
    }

    public String pluginId() { return pluginId; }
    public String pluginName() { return pluginName; }
    public String pluginVersion() { return pluginVersion; }
    public String vendor() { return vendor; }
    public boolean isBundled() { return bundled; }
    public String loaderDescription() { return loaderDescription; }

    /** The class loader of the attributed plugin, if still alive. */
    public ClassLoader loader() {
        return loaderRef == null ? null : loaderRef.get();
    }

    /** Human readable name, e.g. {@code Demo Helper 1.1.0}. */
    public String displayName() {
        if (!isKnown()) return UNKNOWN_DISPLAY_NAME;
        return pluginVersion == null ? pluginName : pluginName + " " + pluginVersion;
    }

    /** Stable key used for policies/baselines. */
    public String key() {
        return isKnown() ? pluginId : "unknown";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginIdentity)) return false;
        PluginIdentity that = (PluginIdentity) o;
        return bundled == that.bundled
                && Objects.equals(pluginId, that.pluginId)
                && Objects.equals(pluginVersion, that.pluginVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, pluginVersion, bundled);
    }

    @Override
    public String toString() {
        return "PluginIdentity{" + key() + (pluginVersion == null ? "" : "@" + pluginVersion)
                + (bundled ? ", bundled" : "") + "}";
    }
}
