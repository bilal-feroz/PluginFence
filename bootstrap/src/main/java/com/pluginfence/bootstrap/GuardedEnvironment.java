package com.pluginfence.bootstrap;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Read-only view of the process environment returned to instrumented {@code System.getenv()}
 * callers. Variables whose access policy is BLOCK/ASK are simply absent from the view, so a
 * plugin enumerating the environment cannot harvest secrets it is not allowed to read.
 */
final class GuardedEnvironment extends AbstractMap<String, String> {

    private final Map<String, String> delegate;
    private final Predicate<String> visible;
    private volatile Map<String, String> filtered;

    GuardedEnvironment(Map<String, String> delegate, Predicate<String> visible) {
        this.delegate = delegate;
        this.visible = visible;
    }

    private Map<String, String> filtered() {
        Map<String, String> f = filtered;
        if (f == null) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : delegate.entrySet()) {
                if (visible.test(e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
            f = Collections.unmodifiableMap(out);
            filtered = f;
        }
        return f;
    }

    @Override
    public String get(Object key) {
        if (!(key instanceof String) || !visible.test((String) key)) {
            return null;
        }
        return delegate.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        return filtered().entrySet();
    }
}
