package com.pluginfence.policy

import com.pluginfence.model.Capability
import com.pluginfence.model.PluginPolicy
import com.pluginfence.model.PolicyDecision
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** In-memory policy table; the engine persists it through [onChange]. Thread-safe. */
class PolicyStore(initial: Collection<PluginPolicy> = emptyList(), private val onChange: () -> Unit = {}) : PolicyLookup {

    private val policies = ConcurrentHashMap<String, PluginPolicy>()

    init {
        initial.forEach { policies[it.pluginId] = it }
    }

    override fun policy(pluginId: String): PluginPolicy? = policies[pluginId]

    fun all(): List<PluginPolicy> = policies.values.sortedBy { it.pluginId }

    fun effective(pluginId: String, capability: Capability): PolicyDecision =
        policies[pluginId]?.overrides?.get(capability) ?: DefaultPolicies.of(capability)

    fun isOverridden(pluginId: String, capability: Capability): Boolean =
        policies[pluginId]?.overrides?.containsKey(capability) == true

    fun setDecision(pluginId: String, capability: Capability, decision: PolicyDecision?) {
        policies.compute(pluginId) { _, existing ->
            val base = existing ?: PluginPolicy(pluginId)
            val overrides = base.overrides.toMutableMap()
            if (decision == null) overrides.remove(capability) else overrides[capability] = decision
            base.copy(overrides = overrides)
        }
        onChange()
    }

    fun approve(pluginId: String, capability: Capability, target: String) {
        if (target.isBlank()) return
        policies.compute(pluginId) { _, existing ->
            val base = existing ?: PluginPolicy(pluginId)
            val approved = base.approvedTargets.toMutableMap()
            approved[capability] = (approved[capability] ?: emptySet()) + target
            base.copy(approvedTargets = approved)
        }
        onChange()
    }

    fun revoke(pluginId: String, capability: Capability, target: String) {
        policies.computeIfPresent(pluginId) { _, existing ->
            val approved = existing.approvedTargets.toMutableMap()
            approved[capability] = (approved[capability] ?: emptySet()).filterNot { it.equals(target, ignoreCase = true) }.toSet()
            existing.copy(approvedTargets = approved)
        }
        onChange()
    }

    fun reset(pluginId: String) {
        policies.remove(pluginId)
        onChange()
    }
}

/** One-shot "Allow Once" grants, consumed by the next matching request. */
class GrantStore : GrantLookup {
    private val grants = HashMap<String, Int>()

    fun grantOnce(pluginId: String, capability: Capability, target: String) {
        synchronized(grants) { grants.merge(key(pluginId, capability, target), 1, Int::plus) }
    }

    override fun consumeOnce(pluginId: String, capability: Capability, target: String): Boolean {
        val k = key(pluginId, capability, target)
        synchronized(grants) {
            val n = grants[k] ?: return false
            if (n <= 1) grants.remove(k) else grants[k] = n - 1
            return true
        }
    }

    fun pending(): Map<String, Int> = synchronized(grants) { grants.toMap() }

    private fun key(pluginId: String, capability: Capability, target: String) =
        "$pluginId|${capability.name}|${target.lowercase(Locale.ROOT)}"
}
