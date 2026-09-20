package com.pluginfence.bootstrap;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal, throttled diagnostics for boot-class-path code (no logging framework available here).
 * Writes to {@code System.err}, which IntelliJ captures into idea.log.
 */
public final class GuardLog {

    private static final int MAX_MESSAGES = 200;
    private static final AtomicInteger EMITTED = new AtomicInteger();
    private static volatile boolean debug = Boolean.getBoolean("pluginfence.debug");

    private GuardLog() {
    }

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static void info(String message) {
        emit("INFO", message, null);
    }

    public static void debug(String message) {
        if (debug) emit("DEBUG", message, null);
    }

    public static void warn(String message, Throwable t) {
        emit("WARN", message, t);
    }

    private static void emit(String level, String message, Throwable t) {
        int n = EMITTED.incrementAndGet();
        if (n > MAX_MESSAGES && !debug) {
            return; // never flood idea.log
        }
        StringBuilder sb = new StringBuilder("[PluginFence] ").append(level).append(' ').append(message);
        if (t != null) {
            sb.append(": ").append(t.getClass().getName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < Math.min(5, trace.length); i++) {
                sb.append("\n    at ").append(trace[i]);
            }
        }
        System.err.println(sb);
    }
}
