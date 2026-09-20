package com.pluginfence.bootstrap;

/**
 * The control plane contract. Implemented by the PluginFence IDE plugin and registered
 * through {@link GuardBridge#setProvider(DecisionProvider)}.
 * <p>
 * {@link #decide} runs synchronously on the intercepted thread and must be fast
 * (in-memory policy lookup only, no UI, no disk). {@link #record} may hand the event
 * to a background queue.
 */
public interface DecisionProvider {

    SecurityDecision decide(SecurityRequest request);

    void record(SecurityRequest request, SecurityDecision decision);
}
