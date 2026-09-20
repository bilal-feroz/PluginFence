package com.pluginfence.baseline

import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.policy.RiskWeights
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineEngineTest {

    private var ids = 0L

    private fun event(version: String, op: FenceOperation, capability: Capability?, target: String, ts: Long,
                      sensitive: String? = null, host: String? = null, verdict: FenceVerdict = FenceVerdict.ALLOW) = FenceEvent(
        ++ids, ts, "com.demo", "Demo Helper", version, true, "com.demo.Main", op, capability, target, target, "api",
        verdict, "", "", 0, FenceRisk.INFO, emptyList(), sensitive, null, if (host != null) mapOf("host" to host) else emptyMap(), true,
    )

    @Test
    fun `same behaviour across versions produces no drift`() {
        val engine = BaselineEngine()
        engine.observe(event("1.0.0", FenceOperation.FILE_READ, Capability.PROJECT_FILES, "/p/a", 100))
        engine.observe(event("1.0.0", FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "127.0.0.1:80", 101, host = "127.0.0.1"))
        val obs = engine.observe(event("1.1.0", FenceOperation.FILE_READ, Capability.PROJECT_FILES, "/p/b", 200))
        assertNotNull(obs)
        assertNull(obs!!.drift, "1.1.0 only did what 1.0.0 did")
        assertTrue(engine.allDrifts().isEmpty())
    }

    @Test
    fun `new host is drift, new sensitive access is high risk drift`() {
        val engine = BaselineEngine()
        engine.observe(event("1.0.0", FenceOperation.FILE_READ, Capability.PROJECT_FILES, "/p/a", 100))
        engine.observe(event("1.0.0", FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "127.0.0.1:80", 101, host = "127.0.0.1"))

        val hostObs = engine.observe(event("1.1.0", FenceOperation.NETWORK_CONNECT, Capability.NETWORK, "198.51.100.42:8080", 200, host = "198.51.100.42", verdict = FenceVerdict.BLOCK))!!
        val hostDrift = hostObs.drift!!
        assertTrue(hostObs.driftChanged)
        assertEquals(setOf("198.51.100.42"), hostDrift.addedHosts)
        assertTrue(hostDrift.addedCapabilities.isEmpty(), "NETWORK itself was already known")
        assertEquals(RiskWeights.NEW_BEHAVIOR_AFTER_UPDATE + RiskWeights.NEW_DESTINATION + RiskWeights.RAW_IP, hostDrift.riskScore)
        assertEquals(FenceRisk.HIGH, hostDrift.riskLevel)

        val sshObs = engine.observe(event("1.1.0", FenceOperation.FILE_READ, Capability.SENSITIVE_FILES, "/h/.ssh/id_rsa", 201, sensitive = "ssh", verdict = FenceVerdict.BLOCK))!!
        val drift = sshObs.drift!!
        assertEquals(setOf(Capability.SENSITIVE_FILES), drift.addedCapabilities)
        assertEquals(setOf("ssh"), drift.addedSensitiveResources)
        assertEquals(100, drift.riskScore)
        assertEquals(FenceRisk.CRITICAL, drift.riskLevel)
        assertTrue(drift.highRisk)

        val procObs = engine.observe(event("1.1.0", FenceOperation.PROCESS_EXEC, Capability.PROCESS_EXECUTION, "powershell.exe", 202, verdict = FenceVerdict.ASK))!!
        assertEquals(setOf("powershell.exe"), procObs.drift!!.addedProcesses)
        assertEquals(3, procObs.drift!!.addedCapabilities.size - 1 + procObs.drift!!.addedHosts.size + procObs.drift!!.addedProcesses.size)
        assertEquals(1, engine.allDrifts().size, "one drift record per version pair")
        assertEquals("1.0.0", engine.allDrifts()[0].oldVersion)
        assertEquals("1.1.0", engine.allDrifts()[0].newVersion)
    }

    @Test
    fun `high risk notification fires once when the threshold is crossed`() {
        val engine = BaselineEngine()
        engine.observe(event("1.0.0", FenceOperation.FILE_READ, Capability.PROJECT_FILES, "/p/a", 100))
        val first = engine.observe(event("1.1.0", FenceOperation.FILE_READ, Capability.SENSITIVE_FILES, "/h/.ssh/id_rsa", 200, sensitive = "ssh"))!!
        assertTrue(first.becameHighRisk)
        val second = engine.observe(event("1.1.0", FenceOperation.FILE_READ, Capability.SENSITIVE_FILES, "/h/.aws/credentials", 201, sensitive = "aws"))!!
        assertFalse(second.becameHighRisk)
        assertTrue(second.driftChanged)
    }

    @Test
    fun `baseline survives round trip through profiles and previous version lookup`() {
        val profile = BehaviorProfile("com.demo", "Demo", "1.0.0", setOf(Capability.NETWORK), setOf("api.example.com"), emptySet(), emptySet(), 1, 2, 3)
        val engine = BaselineEngine(listOf(profile))
        assertTrue(engine.isKnownHost("com.demo", "API.EXAMPLE.COM"))
        assertFalse(engine.isKnownHost("com.demo", "evil.example"))
        assertEquals("1.0.0", engine.previousVersionProfile("com.demo", "1.1.0")?.version)
        assertNull(engine.previousVersionProfile("com.demo", "1.0.0"))
        assertNull(engine.previousVersionProfile("com.other", "1.0.0"))
    }

    @Test
    fun `unknown plugins and ungoverned events are not baselined`() {
        val engine = BaselineEngine()
        assertNull(engine.observe(event("1.0.0", FenceOperation.FILE_READ, null, "/ide/x", 1)))
        assertTrue(engine.allProfiles().isEmpty())
    }
}
