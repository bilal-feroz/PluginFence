package com.pluginfence.bootstrap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A normalized description of an intercepted operation.
 * <p>
 * <b>Privacy invariant:</b> a request never carries secret material. File contents,
 * environment variable values, request bodies and credential-bearing arguments are
 * never captured; targets are redacted by {@link Redactor} before they get here.
 */
public final class SecurityRequest {

    /** Metadata keys populated by {@link GuardHooks}. */
    public static final String META_HOST = "host";
    public static final String META_PORT = "port";
    public static final String META_SCHEME = "scheme";
    public static final String META_EXECUTABLE = "executable";
    public static final String META_ARGS = "args";
    public static final String META_KIND = "kind";

    private final long eventId;
    private final long timestamp;
    private final PluginIdentity identity;
    private final String sourceClass;
    private final OperationType operation;
    private final String target;
    private final String api;
    private final Map<String, String> metadata;

    public SecurityRequest(long eventId,
                           long timestamp,
                           PluginIdentity identity,
                           String sourceClass,
                           OperationType operation,
                           String target,
                           String api,
                           Map<String, String> metadata) {
        this.eventId = eventId;
        this.timestamp = timestamp;
        this.identity = identity == null ? PluginIdentity.UNKNOWN : identity;
        this.sourceClass = sourceClass == null ? "" : sourceClass;
        this.operation = operation;
        this.target = target == null ? "" : target;
        this.api = api == null ? "" : api;
        this.metadata = metadata == null || metadata.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public long eventId() { return eventId; }
    public long timestamp() { return timestamp; }
    public PluginIdentity identity() { return identity; }
    public String sourceClass() { return sourceClass; }
    public OperationType operation() { return operation; }

    /** Primary target: normalized path, {@code host:port}, executable, or env var name. */
    public String target() { return target; }

    /** The intercepted API, e.g. {@code java.nio.file.Files.readString}. */
    public String api() { return api; }

    public Map<String, String> metadata() { return metadata; }

    public String metadata(String key) { return metadata.get(key); }

    @Override
    public String toString() {
        return "SecurityRequest{#" + eventId + " " + identity.key() + " " + operation + " " + target + "}";
    }
}
