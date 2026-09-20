package com.pluginfence.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide rendezvous point between the instrumentation agent and the IDE control plane.
 * <p>
 * Lives on the boot class path so that a single instance is shared by every class loader
 * in the JVM. Thread-safe, dependency-free, and safe to use when no provider is registered:
 * in that case operations are allowed and recorded into a bounded buffer that the control
 * plane drains once it comes up. PluginFence must never brick the IDE.
 */
public final class GuardBridge {

    public static final String VERSION = "0.1.0";

    /** Upper bound on events buffered while no provider is registered. */
    private static final int PENDING_CAPACITY = 512;

    private static volatile DecisionProvider provider;
    private static volatile boolean agentInstalled;
    private static volatile String agentInfo = "";
    private static volatile boolean enforcementEnabled = true;

    private static final CopyOnWriteArrayList<PluginIdentity> IDENTITIES = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<BufferedEvent> PENDING = new ArrayDeque<>();
    private static final Object PENDING_LOCK = new Object();

    private static volatile java.util.function.Supplier<java.util.Map<String, String>> diagnostics;

    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final AtomicLong INTERCEPTED = new AtomicLong();
    private static final AtomicLong PREVENTED = new AtomicLong();
    private static final AtomicLong PROVIDER_FAILURES = new AtomicLong();

    static {
        IDENTITIES.add(PluginIdentity.UNKNOWN); // token 0
    }

    private GuardBridge() {
    }

    // --- agent state ------------------------------------------------------------------------

    /** Called once by the agent's {@code premain}. */
    public static void markAgentInstalled(String info) {
        agentInstalled = true;
        agentInfo = info == null ? "" : info;
    }

    public static boolean isAgentInstalled() {
        return agentInstalled;
    }

    public static String agentInfo() {
        return agentInfo;
    }

    /** Installed by the agent so the IDE plugin can show instrumentation statistics. */
    public static void setDiagnostics(java.util.function.Supplier<java.util.Map<String, String>> supplier) {
        diagnostics = supplier;
    }

    public static java.util.Map<String, String> diagnostics() {
        java.util.function.Supplier<java.util.Map<String, String>> s = diagnostics;
        if (s == null) return java.util.Collections.emptyMap();
        try {
            java.util.Map<String, String> m = s.get();
            return m == null ? java.util.Collections.emptyMap() : m;
        } catch (Throwable t) {
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * Global kill switch. When disabled, hooks still record but never prevent anything.
     * Used as a safety valve if enforcement ever misbehaves.
     */
    public static void setEnforcementEnabled(boolean enabled) {
        enforcementEnabled = enabled;
    }

    public static boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    // --- identities -------------------------------------------------------------------------

    /**
     * Registers an identity and returns the token the agent embeds into instrumented bytecode.
     * Identities are registered once per plugin class loader, so the list stays tiny.
     */
    public static int registerIdentity(PluginIdentity identity) {
        if (identity == null) return 0;
        synchronized (IDENTITIES) {
            int existing = IDENTITIES.indexOf(identity);
            if (existing >= 0 && IDENTITIES.get(existing).loader() == identity.loader()) {
                return existing;
            }
            IDENTITIES.add(identity);
            return IDENTITIES.size() - 1;
        }
    }

    public static PluginIdentity identity(int token) {
        if (token < 0 || token >= IDENTITIES.size()) return PluginIdentity.UNKNOWN;
        return IDENTITIES.get(token);
    }

    public static List<PluginIdentity> identities() {
        return new ArrayList<>(IDENTITIES);
    }

    // --- provider ---------------------------------------------------------------------------

    public static void setProvider(DecisionProvider newProvider) {
        provider = newProvider;
    }

    /** Clears the provider only if it is still the given instance (plugin unload safety). */
    public static void clearProvider(DecisionProvider expected) {
        if (provider == expected) {
            provider = null;
        }
    }

    public static DecisionProvider provider() {
        return provider;
    }

    public static boolean isProviderRegistered() {
        return provider != null;
    }

    /** Returns and clears events recorded while no provider was registered. */
    public static List<BufferedEvent> drainPending() {
        synchronized (PENDING_LOCK) {
            List<BufferedEvent> out = new ArrayList<>(PENDING);
            PENDING.clear();
            return out;
        }
    }

    // --- evaluation (called by GuardHooks) --------------------------------------------------

    public static long nextEventId() {
        return EVENT_SEQUENCE.incrementAndGet();
    }

    static SecurityDecision decide(SecurityRequest request) {
        INTERCEPTED.incrementAndGet();
        DecisionProvider p = provider;
        if (p == null) {
            return SecurityDecision.MONITOR_ONLY;
        }
        try {
            SecurityDecision decision = p.decide(request);
            if (decision == null) {
                return SecurityDecision.MONITOR_ONLY;
            }
            if (decision.prevents() && !enforcementEnabled) {
                return new SecurityDecision(Verdict.MONITOR,
                        "Enforcement disabled: " + decision.reason(), decision.ruleId(), decision.riskScore());
            }
            return decision;
        } catch (Throwable t) {
            // Fail open: a control-plane bug must never break the IDE or the plugin being observed.
            PROVIDER_FAILURES.incrementAndGet();
            GuardLog.warn("decision provider failed, allowing operation", t);
            return SecurityDecision.MONITOR_ONLY;
        }
    }

    static void record(SecurityRequest request, SecurityDecision decision) {
        if (decision.prevents()) {
            PREVENTED.incrementAndGet();
        }
        DecisionProvider p = provider;
        if (p == null) {
            synchronized (PENDING_LOCK) {
                if (PENDING.size() >= PENDING_CAPACITY) {
                    PENDING.pollFirst();
                }
                PENDING.addLast(new BufferedEvent(request, decision));
            }
            return;
        }
        try {
            p.record(request, decision);
        } catch (Throwable t) {
            PROVIDER_FAILURES.incrementAndGet();
            GuardLog.warn("decision provider failed to record event", t);
        }
    }

    // --- statistics -------------------------------------------------------------------------

    public static long interceptedCount() {
        return INTERCEPTED.get();
    }

    public static long preventedCount() {
        return PREVENTED.get();
    }

    public static long providerFailureCount() {
        return PROVIDER_FAILURES.get();
    }

    /** An event captured before the control plane registered. */
    public static final class BufferedEvent {
        private final SecurityRequest request;
        private final SecurityDecision decision;

        BufferedEvent(SecurityRequest request, SecurityDecision decision) {
            this.request = request;
            this.decision = decision;
        }

        public SecurityRequest request() { return request; }
        public SecurityDecision decision() { return decision; }
    }
}
