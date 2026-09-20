package com.pluginfence.agent;

import com.pluginfence.bootstrap.DecisionProvider;
import com.pluginfence.bootstrap.GuardBridge;
import com.pluginfence.bootstrap.OperationType;
import com.pluginfence.bootstrap.SecurityDecision;
import com.pluginfence.bootstrap.SecurityRequest;
import com.pluginfence.bootstrap.Verdict;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Test double for the control plane: scripted verdicts, records every request. */
final class RecordingProvider implements DecisionProvider {

    final List<SecurityRequest> requests = new CopyOnWriteArrayList<>();
    final List<SecurityDecision> decisions = new CopyOnWriteArrayList<>();
    volatile Function<SecurityRequest, Verdict> policy = r -> Verdict.ALLOW;

    @Override
    public SecurityDecision decide(SecurityRequest request) {
        return new SecurityDecision(policy.apply(request), "scripted", "test", 42);
    }

    @Override
    public void record(SecurityRequest request, SecurityDecision decision) {
        requests.add(request);
        decisions.add(decision);
    }

    void blockEverything() {
        policy = r -> Verdict.BLOCK;
    }

    void allowEverything() {
        policy = r -> Verdict.ALLOW;
    }

    void block(OperationType type) {
        policy = r -> r.operation() == type ? Verdict.BLOCK : Verdict.ALLOW;
    }

    void reset() {
        requests.clear();
        decisions.clear();
        allowEverything();
    }

    SecurityRequest last() {
        return requests.get(requests.size() - 1);
    }

    SecurityRequest lastOf(OperationType type) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).operation() == type) return requests.get(i);
        }
        throw new AssertionError("no request of type " + type + " recorded; got " + requests);
    }

    RecordingProvider install() {
        GuardBridge.setProvider(this);
        GuardBridge.setEnforcementEnabled(true);
        return this;
    }

    void uninstall() {
        GuardBridge.clearProvider(this);
    }
}
