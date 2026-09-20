package com.pluginfence.correlation

import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.IncidentKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrelationEngineTest {

    private var ids = 0L

    private fun event(op: FenceOperation, capability: Capability?, target: String, ts: Long, verdict: FenceVerdict, score: Int, sensitive: String? = null, plugin: String = "com.demo") = FenceEvent(
        ++ids, ts, plugin, "Demo Helper", "1.1.0", true, "com.demo.Main", op, capability, target, target, "api",
        verdict, "", "", score, FenceRisk.fromScore(score), emptyList(), sensitive, null, emptyMap(), true,
    )

    @Test
    fun `secret read followed by network within window is a critical incident`() {
        val engine = CorrelationEngine()
        val secret = event(FenceOperation.FILE_READ, Capability.SENSITIVE_FILES, "/h/.ssh/id_rsa", 1_000, FenceVerdict.BLOCK, 60, "ssh")
        engine.noteSensitiveAccess("com.demo", secret)
        assertNull(engine.observe(secret)?.takeIf { it.kind != IncidentKind.SENSITIVE_ACCESS_BLOCKED }, "single blocked access yields only its own incident")

        val exfil = event(FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "198.51.100.42:8080", 1_600, FenceVerdict.BLOCK, 100)
        val incident = engine.observe(exfil)
        assertNotNull(incident)
        assertEquals(IncidentKind.POTENTIAL_SECRET_EXFILTRATION, incident!!.kind)
        assertEquals(FenceRisk.CRITICAL, incident.riskLevel)
        assertEquals(100, incident.riskScore)
        assertEquals(listOf(secret.id, exfil.id), incident.chain.map { it.id })
        assertTrue(incident.summary.contains("attempted to read"))
        assertTrue(incident.outcome.contains("blocked"))
    }

    @Test
    fun `network outside the window is not correlated`() {
        val engine = CorrelationEngine()
        engine.noteSensitiveAccess("com.demo", event(FenceOperation.FILE_READ, Capability.SENSITIVE_FILES, "/h/.ssh/id_rsa", 1_000, FenceVerdict.BLOCK, 60, "ssh"))
        assertNull(engine.observe(event(FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "x:1", 1_000 + 10_001, FenceVerdict.ASK, 40)))
        assertNull(engine.recentSensitiveAccess("com.demo", 1_000 + 10_001, 10_000))
    }

    @Test
    fun `secret then process is its own incident kind and plugins are isolated`() {
        val engine = CorrelationEngine()
        engine.noteSensitiveAccess("com.demo", event(FenceOperation.ENV_READ, Capability.SECRET_ENVIRONMENT, "OPENAI_API_KEY", 5_000, FenceVerdict.BLOCK, 45))
        val other = engine.observe(event(FenceOperation.PROCESS_EXEC, Capability.PROCESS_EXECUTION, "curl", 5_500, FenceVerdict.ASK, 40, plugin = "com.other"))
        assertNull(other, "another plugin's process is not correlated with this plugin's secret access")
        val incident = engine.observe(event(FenceOperation.PROCESS_EXEC, Capability.PROCESS_EXECUTION, "curl", 5_500, FenceVerdict.BLOCK, 90))
        assertEquals(IncidentKind.SECRET_ACCESS_THEN_PROCESS, incident?.kind)
    }

    @Test
    fun `repeated attempts extend the same incident`() {
        val engine = CorrelationEngine()
        val secret = event(FenceOperation.FILE_READ, Capability.SENSITIVE_FILES, "/h/.ssh/id_rsa", 1_000, FenceVerdict.BLOCK, 60, "ssh")
        engine.noteSensitiveAccess("com.demo", secret)
        val first = engine.observe(event(FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "198.51.100.42:8080", 1_500, FenceVerdict.BLOCK, 100))!!
        val second = engine.observe(event(FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "198.51.100.42:8080", 2_500, FenceVerdict.BLOCK, 100))!!
        assertEquals(first.id, second.id)
        assertEquals(3, second.chain.size)
    }
}
