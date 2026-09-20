package com.pluginfence.bootstrap;

/** Result of evaluating a {@link SecurityRequest} against policy. */
public final class SecurityDecision {

    public static final SecurityDecision MONITOR_ONLY =
            new SecurityDecision(Verdict.MONITOR, "No policy provider registered; monitoring only", "fallback.monitor", 0);

    private final Verdict verdict;
    private final String reason;
    private final String ruleId;
    private final int riskScore;
    private final RiskLevel riskLevel;

    public SecurityDecision(Verdict verdict, String reason, String ruleId, int riskScore) {
        this.verdict = verdict == null ? Verdict.MONITOR : verdict;
        this.reason = reason == null ? "" : reason;
        this.ruleId = ruleId == null ? "" : ruleId;
        this.riskScore = Math.max(0, Math.min(100, riskScore));
        this.riskLevel = RiskLevel.fromScore(this.riskScore);
    }

    public static SecurityDecision allow(String reason, String ruleId, int riskScore) {
        return new SecurityDecision(Verdict.ALLOW, reason, ruleId, riskScore);
    }

    public static SecurityDecision block(String reason, String ruleId, int riskScore) {
        return new SecurityDecision(Verdict.BLOCK, reason, ruleId, riskScore);
    }

    public static SecurityDecision ask(String reason, String ruleId, int riskScore) {
        return new SecurityDecision(Verdict.ASK, reason, ruleId, riskScore);
    }

    public Verdict verdict() { return verdict; }
    public String reason() { return reason; }
    public String ruleId() { return ruleId; }
    public int riskScore() { return riskScore; }
    public RiskLevel riskLevel() { return riskLevel; }

    public boolean prevents() { return verdict.prevents(); }

    @Override
    public String toString() {
        return verdict + "(" + ruleId + ", " + riskScore + "/" + riskLevel + ")";
    }
}
