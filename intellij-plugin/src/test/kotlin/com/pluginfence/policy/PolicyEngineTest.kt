package com.pluginfence.policy

import com.pluginfence.baseline.BaselineEngine
import com.pluginfence.classify.PathScope
import com.pluginfence.classify.SecretEnvClassifier
import com.pluginfence.classify.SensitivePathClassifier
import com.pluginfence.correlation.CorrelationEngine
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.OperationRequest
import com.pluginfence.model.PolicyDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PolicyEngineTest {

    private val project = "C:\\work\\proj"
    private lateinit var policies: PolicyStore
    private lateinit var grants: GrantStore
    private lateinit var baselines: BaselineEngine
    private lateinit var correlation: CorrelationEngine
    private lateinit var engine: PolicyEngine
    private var ids = 0L

    @BeforeEach
    fun setUp() {
        policies = PolicyStore()
        grants = GrantStore()
        baselines = BaselineEngine()
        correlation = CorrelationEngine()
        engine = PolicyEngine(
            SensitivePathClassifier(userHome = "C:\\Users\\dev"), SecretEnvClassifier(),
            { PathScope(listOf(project), listOf("C:\\ide\\config")) }, policies, grants, baselines, correlation,
        )
    }

    private fun request(op: FenceOperation, target: String, meta: Map<String, String> = emptyMap(), plugin: String = "com.demo", version: String = "1.0.0", ts: Long = 1_000_000) =
        OperationRequest(++ids, ts, plugin, "Demo", version, true, false, "Example", "com.demo.Main", op, target, "api", meta)

    private fun network(host: String, port: Int, scheme: String? = null, ts: Long = 1_000_000) =
        request(FenceOperation.NETWORK_CONNECT, "$host:$port", listOfNotNull("host" to host, "port" to port.toString(), scheme?.let { "scheme" to it }).toMap(), ts = ts)

    // --- P0 policy tests -----------------------------------------------------------------------

    @Test
    fun `project read is allowed`() {
        val e = engine.evaluate(request(FenceOperation.FILE_READ, "$project\\README.md"), trusted = false)
        assertEquals(Capability.PROJECT_FILES, e.capability)
        assertEquals(FenceVerdict.ALLOW, e.decision.verdict)
        assertEquals(0, e.decision.riskScore)
    }

    @Test
    fun `ssh key read is blocked and rated high`() {
        val e = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\Users\\dev\\.ssh\\id_rsa"), trusted = false)
        assertEquals(Capability.SENSITIVE_FILES, e.capability)
        assertEquals(FenceVerdict.BLOCK, e.decision.verdict)
        assertEquals("sensitive.default", e.decision.ruleId)
        assertEquals(RiskWeights.CREDENTIAL_STORE, e.decision.riskScore)
        assertEquals(FenceRisk.HIGH, e.decision.riskLevel)
        assertEquals("ssh", e.sensitiveMatch?.category)
    }

    @Test
    fun `fixture ssh key anywhere on disk is still sensitive`() {
        val e = engine.evaluate(request(FenceOperation.FILE_READ, "D:\\repo\\demo-fixtures\\home\\.ssh\\id_rsa"), trusted = false)
        assertEquals(FenceVerdict.BLOCK, e.decision.verdict)
    }

    @Test
    fun `secret environment variable is blocked, ordinary one allowed and ungoverned`() {
        val secret = engine.evaluate(request(FenceOperation.ENV_READ, "OPENAI_API_KEY"), trusted = false)
        assertEquals(FenceVerdict.BLOCK, secret.decision.verdict)
        assertEquals(Capability.SECRET_ENVIRONMENT, secret.capability)
        val plain = engine.evaluate(request(FenceOperation.ENV_READ, "PATH"), trusted = false)
        assertEquals(FenceVerdict.ALLOW, plain.decision.verdict)
        assertEquals(null, plain.capability)
    }

    @Test
    fun `new external destination asks, loopback is allowed`() {
        val external = engine.evaluate(network("api.example.com", 443, "https"), trusted = false)
        assertEquals(FenceVerdict.ASK, external.decision.verdict)
        assertEquals("network.default", external.decision.ruleId)
        assertTrue(external.riskFactors.any { it.id == "network.new" })
        val loopback = engine.evaluate(network("127.0.0.1", 8080, "http"), trusted = false)
        assertEquals(FenceVerdict.ALLOW, loopback.decision.verdict)
        assertEquals("network.loopback", loopback.decision.ruleId)
    }

    @Test
    fun `raw ip and plaintext raise risk`() {
        val e = engine.evaluate(network("198.51.100.42", 8080, "http"), trusted = false)
        assertEquals(RiskWeights.NEW_DESTINATION + RiskWeights.RAW_IP + RiskWeights.PLAINTEXT, e.decision.riskScore)
        assertEquals(FenceRisk.HIGH, e.decision.riskLevel)
    }

    @Test
    fun `process execution asks by default and flags shells`() {
        val java = engine.evaluate(request(FenceOperation.PROCESS_EXEC, "java"), trusted = false)
        assertEquals(FenceVerdict.ASK, java.decision.verdict)
        assertEquals(RiskWeights.PROCESS_EXECUTION, java.decision.riskScore)
        val shell = engine.evaluate(request(FenceOperation.PROCESS_EXEC, "powershell.exe"), trusted = false)
        assertEquals(RiskWeights.PROCESS_EXECUTION + RiskWeights.HIGH_RISK_EXECUTABLE, shell.decision.riskScore)
    }

    @Test
    fun `outside project asks, ide internal is allowed`() {
        val outside = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\Users\\dev\\Documents\\notes.txt"), trusted = false)
        assertEquals(Capability.FILES_OUTSIDE_PROJECT, outside.capability)
        assertEquals(FenceVerdict.ASK, outside.decision.verdict)
        assertEquals("C:\\Users\\dev\\Documents", outside.approvalTarget)
        val internal = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\ide\\config\\options\\x.xml"), trusted = false)
        assertEquals(FenceVerdict.ALLOW, internal.decision.verdict)
        assertEquals("monitor.ungoverned", internal.decision.ruleId)
    }

    @Test
    fun `explicit user overrides are respected in precedence order`() {
        policies.setDecision("com.demo", Capability.SENSITIVE_FILES, PolicyDecision.ALLOW)
        val allowed = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\Users\\dev\\.ssh\\id_rsa"), trusted = false)
        assertEquals(FenceVerdict.ALLOW, allowed.decision.verdict)
        assertEquals("policy.user.allow", allowed.decision.ruleId)

        policies.setDecision("com.demo", Capability.NETWORK, PolicyDecision.BLOCK)
        policies.approve("com.demo", Capability.NETWORK, "api.example.com")
        val blocked = engine.evaluate(network("api.example.com", 443, "https"), trusted = false)
        assertEquals(FenceVerdict.BLOCK, blocked.decision.verdict, "user BLOCK beats a target approval")
        assertEquals("policy.user.block", blocked.decision.ruleId)

        policies.setDecision("com.demo", Capability.NETWORK, null)
        val approved = engine.evaluate(network("api.example.com", 443, "https"), trusted = false)
        assertEquals(FenceVerdict.ALLOW, approved.decision.verdict)
        assertEquals("approval.target", approved.decision.ruleId)
    }

    @Test
    fun `allow once grant is consumed exactly once`() {
        grants.grantOnce("com.demo", Capability.PROCESS_EXECUTION, "java")
        assertEquals(FenceVerdict.ALLOW, engine.evaluate(request(FenceOperation.PROCESS_EXEC, "java"), trusted = false).decision.verdict)
        assertEquals(FenceVerdict.ASK, engine.evaluate(request(FenceOperation.PROCESS_EXEC, "java"), trusted = false).decision.verdict)
    }

    @Test
    fun `trusted plugins are monitored not enforced`() {
        val e = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\Users\\dev\\.ssh\\id_rsa"), trusted = true)
        assertEquals(FenceVerdict.ALLOW, e.decision.verdict)
        assertEquals("exempt.trusted", e.decision.ruleId)
        assertEquals(RiskWeights.CREDENTIAL_STORE, e.decision.riskScore, "risk is still reported")
    }

    // --- correlation and drift context ---------------------------------------------------------

    @Test
    fun `network right after sensitive access is blocked as exfiltration`() {
        val secret = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\Users\\dev\\.ssh\\id_rsa", ts = 1_000_000), trusted = false)
        correlation.noteSensitiveAccess("com.demo", event(secret, FenceOperation.FILE_READ, "C:\\Users\\dev\\.ssh\\id_rsa", 1_000_000))

        val exfil = engine.evaluate(network("198.51.100.42", 8080, ts = 1_000_900), trusted = false)
        assertEquals(FenceVerdict.BLOCK, exfil.decision.verdict)
        assertEquals("correlation.exfiltration", exfil.decision.ruleId)
        assertNotNull(exfil.correlatedWith)
        assertEquals(RiskWeights.NEW_DESTINATION + RiskWeights.RAW_IP + RiskWeights.SECRET_THEN_EXFIL, exfil.decision.riskScore)
        assertEquals(FenceRisk.CRITICAL, exfil.decision.riskLevel)

        val later = engine.evaluate(network("198.51.100.42", 8080, ts = 1_000_000 + 11_000), trusted = false)
        assertEquals(FenceVerdict.ASK, later.decision.verdict, "outside the window it is an ordinary new destination")
    }

    @Test
    fun `new behaviour after update adds risk`() {
        val previous = BehaviorProfile("com.demo", "Demo", "1.0.0", setOf(Capability.PROJECT_FILES, Capability.NETWORK), setOf("127.0.0.1"), emptySet(), emptySet(), 1, 2, 5)
        baselines = BaselineEngine(listOf(previous))
        engine = PolicyEngine(SensitivePathClassifier(userHome = "C:\\Users\\dev"), SecretEnvClassifier(), { PathScope(listOf(project), emptyList()) }, policies, grants, baselines, correlation)

        val e = engine.evaluate(request(FenceOperation.FILE_READ, "C:\\Users\\dev\\.ssh\\id_rsa", version = "1.1.0"), trusted = false)
        assertTrue(e.newBehavior)
        assertEquals(RiskWeights.CREDENTIAL_STORE + RiskWeights.NEW_BEHAVIOR_AFTER_UPDATE, e.decision.riskScore)
        assertEquals(FenceRisk.CRITICAL, e.decision.riskLevel)

        val known = engine.evaluate(request(FenceOperation.FILE_READ, "$project\\a.txt", version = "1.1.0"), trusted = false)
        assertFalse(known.newBehavior)
    }

    private fun event(e: Evaluation, op: FenceOperation, target: String, ts: Long) = FenceEvent(
        ++ids, ts, "com.demo", "Demo", "1.0.0", true, "com.demo.Main", op, e.capability, target, e.approvalTarget, "api",
        e.decision.verdict, e.decision.reason, e.decision.ruleId, e.decision.riskScore, e.decision.riskLevel, e.riskFactors,
        e.sensitiveMatch?.category, e.pathRelation, emptyMap(), true,
    )
}
