package com.pluginfence.bootstrap;

/** Deterministic severity buckets derived from a 0..100 risk score. */
public enum RiskLevel {
    INFO, LOW, MEDIUM, HIGH, CRITICAL;

    public static RiskLevel fromScore(int score) {
        if (score >= 80) return CRITICAL;
        if (score >= 60) return HIGH;
        if (score >= 40) return MEDIUM;
        if (score >= 20) return LOW;
        return INFO;
    }
}
