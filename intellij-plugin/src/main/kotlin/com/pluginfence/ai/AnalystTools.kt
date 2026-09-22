package com.pluginfence.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.Incident
import com.pluginfence.model.PolicyDecision
import com.pluginfence.policy.CORRELATION_WINDOW_MS
import com.pluginfence.policy.DefaultPolicies
import com.pluginfence.policy.RiskWeights

/** What a tool call hands back: the JSON the model reads, and a one-liner the UI trace shows. */
data class ToolOutput(val json: String, val summary: String)

/**
 * The evidence the analyst may consult. Abstracted from [FenceEngine] so the agent loop can be
 * unit-tested against a scripted backend and a fake model server.
 */
interface ToolBackend {
    /** The task, phrased for the model, with just enough seed context to pick the first tool. */
    fun describeTask(task: AnalysisTask): String

    fun call(name: String, arguments: JsonObject): ToolOutput
}

/**
 * The analyst's tool set. Every tool is read-only and returns data PluginFence already holds -
 * redacted paths, hosts, executables, plugin metadata, policies. File contents and secret values
 * never enter the system in the first place, so they cannot be sent to a model either.
 */
object AnalystTools {

    const val SUBMIT = "submit_analysis"

    /**
     * Tool definitions in the OpenAI function-calling format.
     *
     * Kept terse on purpose: the whole array is resent with every round of the conversation, so a
     * paragraph of prose per tool is paid for four or five times in a single investigation. On a
     * free tier (Groq: 8k tokens/minute) that difference decides whether an analysis finishes in
     * one pass or stalls on rate limits. The detail that used to live here is in the system prompt,
     * which is sent once.
     */
    fun definitions(): JsonArray = JsonArray().apply {
        add(tool("get_incident", "One incident: its correlated chain of events, verdicts, rules and risk factors.", obj {
            prop("incident_id", "string", "Incident id"); required("incident_id")
        }))
        add(tool("get_plugin_profile", "Behaviour baseline per observed version: capabilities, hosts, processes, sensitive resources.", obj {
            prop("plugin_id", "string", "Plugin id"); required("plugin_id")
        }))
        add(tool("get_behavior_drift", "What the new version does that the previous one never did, with its drift risk score.", obj {
            prop("plugin_id", "string", "Plugin id"); required("plugin_id")
        }))
        add(tool("get_recent_events", "Recent intercepted operations, newest first.", obj {
            prop("plugin_id", "string", "Plugin id")
            prop("limit", "integer", "Default 12, max 40")
            required("plugin_id")
        }))
        add(tool("get_policy", "Effective ALLOW/ASK/BLOCK matrix, user overrides, always-allowed targets, defaults.", obj {
            prop("plugin_id", "string", "Plugin id"); required("plugin_id")
        }))
        add(tool("get_plugin_manifest", "The plugin's own plugin.xml: what it claims to be. Compare with what it did.", obj {
            prop("plugin_id", "string", "Plugin id"); required("plugin_id")
        }))
        add(tool("get_risk_model", "PluginFence's risk weights, thresholds and policy precedence.", obj {}))
        add(tool("list_plugins", "Third-party plugins seen, with version, vendor and trust status.", obj {}))
        add(tool(SUBMIT, "Submit the final analysis. Call once, after investigating. Ends the analysis.", obj {
            prop("verdict", "string", "MALICIOUS | SUSPICIOUS | INCONCLUSIVE | LIKELY_BENIGN | BENIGN")
            prop("confidence", "string", "HIGH | MEDIUM | LOW")
            prop("headline", "string", "One actionable sentence")
            prop("narrative", "string", "2-4 short paragraphs citing targets, versions and event ids")
            arr("evidence", "string", "Concrete evidence, one bullet each")
            add("recommendations", JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "Policy for this plugin; least privilege; only what the evidence supports")
                add("items", obj {
                    prop("capability", "string", "PROJECT_FILES | FILES_OUTSIDE_PROJECT | SENSITIVE_FILES | SECRET_ENVIRONMENT | NETWORK | PROCESS_EXECUTION")
                    prop("decision", "string", "ALLOW | ASK | BLOCK")
                    prop("reason", "string", "Why")
                    required("capability", "decision", "reason")
                })
            })
            add("target_changes", JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "Always-allowed targets to add/remove: host, executable or directory")
                add("items", obj {
                    prop("action", "string", "APPROVE | REVOKE")
                    prop("capability", "string", "Capability it belongs to")
                    prop("target", "string", "Host, executable or directory")
                    prop("reason", "string", "Why")
                    required("action", "capability", "target", "reason")
                })
            })
            arr("next_steps", "string", "What the developer should do next")
            required("verdict", "confidence", "headline", "narrative")
        }))
    }

    private fun tool(name: String, description: String, parameters: JsonObject): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", parameters)
        })
    }

    private class Schema(val obj: JsonObject) {
        val props = JsonObject()
        val req = JsonArray()

        fun prop(name: String, type: String, description: String) {
            props.add(name, JsonObject().apply { addProperty("type", type); addProperty("description", description) })
        }

        fun arr(name: String, itemType: String, description: String) {
            props.add(name, JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", description)
                add("items", JsonObject().apply { addProperty("type", itemType) })
            })
        }

        fun add(name: String, schema: JsonObject) = props.add(name, schema)

        fun required(vararg names: String) = names.forEach { req.add(it) }
    }

    private fun obj(build: Schema.() -> Unit): JsonObject {
        val s = Schema(JsonObject()).apply(build)
        return JsonObject().apply {
            addProperty("type", "object")
            add("properties", s.props)
            if (s.req.size() > 0) add("required", s.req)
        }
    }
}

/** The real backend: read-only views over the engine's evidence. */
class EngineToolBackend(private val engine: FenceEngine) : ToolBackend {

    override fun describeTask(task: AnalysisTask): String = when (task) {
        is AnalysisTask.Incident -> {
            val incident = engine.incident(task.incidentId)
            "Analyse incident ${task.incidentId}" +
                (incident?.let { " (\"${it.title}\", risk ${it.riskScore}/100 ${it.riskLevel.label}, ${it.chain.size} step(s))" } ?: "") +
                " attributed to plugin \"${task.pluginName}\" (id ${task.pluginId}). " +
                "Start with get_incident, then get_plugin_profile, get_behavior_drift and get_policy for the plugin. " +
                "Decide whether this looks like credential theft/exfiltration, a benign operation caught by policy, or something in between, " +
                "explain the sequence in plain English, and recommend the permission matrix this plugin should have from now on."
        }
        is AnalysisTask.UpdateReview ->
            "Review the update of plugin \"${task.pluginName}\" (id ${task.pluginId}) from version ${task.oldVersion} to ${task.newVersion}. " +
                "Use get_behavior_drift, get_plugin_profile, get_recent_events and get_policy. " +
                "Explain what the new version does that the old one never did, whether those additions are plausible for a plugin of this kind, " +
                "and whether the update should be trusted with its current permissions."
        is AnalysisTask.TrustReport ->
            "Write a trust report for plugin \"${task.pluginName}\" (id ${task.pluginId}). " +
                "Use get_plugin_profile, get_recent_events, get_policy and get_behavior_drift. " +
                "Summarise everything PluginFence has observed the plugin doing, judge whether its current permission matrix is appropriate " +
                "(least privilege), and recommend concrete changes if any are warranted."
    }

    override fun call(name: String, arguments: JsonObject): ToolOutput = when (name) {
        "get_incident" -> incident(arguments.str("incident_id"))
        "get_plugin_profile" -> profile(arguments.str("plugin_id"))
        "get_behavior_drift" -> drift(arguments.str("plugin_id"))
        "get_recent_events" -> events(arguments.str("plugin_id"), arguments.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt ?: 12)
        "get_policy" -> policy(arguments.str("plugin_id"))
        "get_risk_model" -> riskModel()
        "list_plugins" -> plugins()
        "get_plugin_manifest" -> manifest(arguments.str("plugin_id"))
        else -> ToolOutput("""{"error":"unknown tool '$name'"}""", "unknown tool $name")
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""

    // --- tools ----------------------------------------------------------------------------------

    private fun incident(id: String): ToolOutput {
        val incident = engine.incident(id) ?: engine.incidents().firstOrNull { it.id.contains(id) }
            ?: return ToolOutput("""{"error":"no incident with id '$id'"}""", "incident $id not found")
        return ToolOutput(incidentJson(incident).toString(), "${incident.title}, ${incident.chain.size} step(s), risk ${incident.riskScore}")
    }

    private fun profile(pluginId: String): ToolOutput {
        val profiles = engine.profiles(pluginId)
        if (profiles.isEmpty()) return ToolOutput("""{"plugin_id":"$pluginId","versions":[]}""", "no baseline for $pluginId")
        val json = JsonObject().apply {
            addProperty("plugin_id", pluginId)
            addProperty("plugin_name", profiles.last().pluginName)
            add("versions", JsonArray().apply { profiles.forEach { add(profileJson(it)) } })
        }
        val hosts = profiles.flatMap { it.networkHosts }.distinct()
        return ToolOutput(json.toString(), "${profiles.size} version(s): ${profiles.joinToString { it.version }}; hosts ${hosts.ifEmpty { listOf("none") }.joinToString()}")
    }

    private fun drift(pluginId: String): ToolOutput {
        val drift = engine.baselines.drift(pluginId)
            ?: return ToolOutput("""{"plugin_id":"$pluginId","drift":null,"note":"only one version observed, or no differences"}""", "no drift for $pluginId")
        return ToolOutput(driftJson(drift).toString(), "${drift.oldVersion} -> ${drift.newVersion}: ${drift.newCapabilityCount} new, risk ${drift.riskScore}")
    }

    private fun events(pluginId: String, limit: Int): ToolOutput {
        val n = limit.coerceIn(1, 40)
        val events = engine.events().filter { pluginId.isBlank() || it.pluginId == pluginId }.take(n)
        val json = JsonArray().apply { events.forEach { add(eventJson(it)) } }
        val prevented = events.count { it.prevented }
        return ToolOutput(json.toString(), "${events.size} event(s), $prevented prevented")
    }

    private fun policy(pluginId: String): ToolOutput {
        val policy = engine.policies.policy(pluginId)
        val json = JsonObject().apply {
            addProperty("plugin_id", pluginId)
            add("effective", JsonObject().apply { Capability.values().forEach { addProperty(it.name, engine.policies.effective(pluginId, it).name) } })
            add("customised", JsonArray().apply { Capability.values().filter { engine.policies.isOverridden(pluginId, it) }.forEach { add(it.name) } })
            add("always_allowed", JsonObject().apply {
                policy?.approvedTargets?.forEach { (cap, targets) -> add(cap.name, JsonArray().apply { targets.forEach { add(it) } }) }
            })
            add("defaults", JsonObject().apply { DefaultPolicies.DEFAULTS.forEach { (cap, d) -> addProperty(cap.name, d.name) } })
            addProperty("trusted_platform_plugin", engine.governedPlugins().firstOrNull { it.pluginId == pluginId }?.trusted ?: false)
        }
        val custom = Capability.values().count { engine.policies.isOverridden(pluginId, it) }
        return ToolOutput(json.toString(), if (custom == 0) "shipped defaults" else "$custom customised capabilit${if (custom == 1) "y" else "ies"}")
    }

    private fun riskModel(): ToolOutput {
        val json = JsonObject().apply {
            add("weights", JsonObject().apply {
                addProperty("credential_store_file", RiskWeights.CREDENTIAL_STORE)
                addProperty("other_sensitive_file", RiskWeights.SENSITIVE_FILE)
                addProperty("secret_environment_variable", RiskWeights.SECRET_ENVIRONMENT)
                addProperty("process_execution", RiskWeights.PROCESS_EXECUTION)
                addProperty("shell_or_interpreter_executable", RiskWeights.HIGH_RISK_EXECUTABLE)
                addProperty("new_network_destination", RiskWeights.NEW_DESTINATION)
                addProperty("raw_ip_destination", RiskWeights.RAW_IP)
                addProperty("plaintext_protocol", RiskWeights.PLAINTEXT)
                addProperty("file_outside_project", RiskWeights.OUTSIDE_PROJECT)
                addProperty("new_behaviour_after_update", RiskWeights.NEW_BEHAVIOR_AFTER_UPDATE)
                addProperty("sensitive_access_then_network_or_process_within_window", RiskWeights.SECRET_THEN_EXFIL)
            })
            addProperty("correlation_window_seconds", CORRELATION_WINDOW_MS / 1000)
            add("severity", JsonObject().apply {
                addProperty("INFO", "0-19"); addProperty("LOW", "20-39"); addProperty("MEDIUM", "40-59"); addProperty("HIGH", "60-79"); addProperty("CRITICAL", "80-100")
            })
            add("policy_precedence", JsonArray().apply {
                listOf(
                    "trusted platform plugin -> allow (monitor only)", "one-time grant (Allow Once)", "explicit user BLOCK",
                    "explicit user ALLOW", "always-allowed target", "sensitive credential file (default BLOCK)",
                    "secret environment variable (default BLOCK)", "network/process within 10 s of sensitive access -> BLOCK",
                    "process execution (default ASK)", "project file (default ALLOW)", "file outside project (default ASK)",
                    "network: loopback allowed, else default ASK", "fallback: allow and monitor",
                ).forEach { add(it) }
            })
            addProperty("enforcement", "Verdicts are deterministic and computed at the call site before the operation runs; the analyst only recommends.")
        }
        return ToolOutput(json.toString(), "weights, thresholds and precedence")
    }

    /**
     * Real context, not just the event: the plugin's own descriptor, read from its jar through the
     * class loader the agent attributed the events to. Lets the analyst say "this plugin describes
     * itself as X, yet it reached for Y".
     */
    private fun manifest(pluginId: String): ToolOutput {
        val loader = engine.pluginClassLoader(pluginId)
            ?: return ToolOutput("""{"plugin_id":"$pluginId","error":"plugin class loader not available (plugin not loaded)"}""", "manifest unavailable")
        val xml = runCatching { loader.getResource("META-INF/plugin.xml")?.readText() }.getOrNull()
            ?: return ToolOutput("""{"plugin_id":"$pluginId","error":"META-INF/plugin.xml not found"}""", "manifest not found")
        val actions = Regex("<action\\b([^>]*)>").findAll(xml).map { m ->
            val attrs = m.groupValues[1]
            JsonObject().apply {
                addProperty("id", attribute(attrs, "id")); addProperty("class", attribute(attrs, "class")); addProperty("text", attribute(attrs, "text"))
            }
        }.toList()
        val extensions = Regex("<extensions\\b[^>]*>(.*?)</extensions>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .flatMap { block -> Regex("<([a-zA-Z][\\w.-]*)\\b").findAll(block.groupValues[1]).map { it.groupValues[1] } }
            .groupingBy { it }.eachCount()
        val json = JsonObject().apply {
            addProperty("plugin_id", pluginId)
            addProperty("name", plain(tag(xml, "name")))
            addProperty("version", plain(tag(xml, "version")))
            addProperty("vendor", plain(tag(xml, "vendor")))
            addProperty("vendor_url", attribute(Regex("<vendor\\b([^>]*)>").find(xml)?.groupValues?.get(1) ?: "", "url"))
            addProperty("description", plain(tag(xml, "description")).take(400))
            add("depends", JsonArray().apply { Regex("<depends\\b[^>]*>([^<]+)</depends>").findAll(xml).forEach { add(it.groupValues[1].trim()) } })
            add("extensions", JsonObject().apply { extensions.forEach { (k, v) -> addProperty(k, v) } })
            add("actions", JsonArray().apply { actions.forEach { add(it) } })
        }
        val summary = "${plain(tag(xml, "name")).ifBlank { pluginId }}: ${actions.size} action(s), ${extensions.size} extension type(s)" +
            if (extensions.isNotEmpty()) " (${extensions.keys.take(4).joinToString()})" else ""
        return ToolOutput(json.toString(), summary)
    }

    private fun tag(xml: String, name: String): String =
        Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: ""

    private fun attribute(attrs: String, name: String): String =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: ""

    private fun plain(raw: String): String = raw
        .replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun plugins(): ToolOutput {
        val list = engine.governedPlugins()
        val json = JsonArray().apply {
            list.forEach { p ->
                add(JsonObject().apply {
                    addProperty("plugin_id", p.pluginId); addProperty("name", p.name); addProperty("version", p.version)
                    addProperty("vendor", p.vendor); addProperty("trusted", p.trusted)
                })
            }
        }
        return ToolOutput(json.toString(), "${list.size} plugin(s)")
    }

    // --- JSON views (everything here is already redacted by the hooks) --------------------------

    private fun incidentJson(i: Incident) = JsonObject().apply {
        addProperty("id", i.id); addProperty("kind", i.kind.name); addProperty("title", i.title)
        addProperty("plugin_id", i.pluginId); addProperty("plugin_name", i.pluginName); addProperty("plugin_version", i.pluginVersion)
        addProperty("time", iso(i.timestamp)); addProperty("summary", i.summary); addProperty("outcome", i.outcome)
        addProperty("risk_score", i.riskScore); addProperty("risk_level", i.riskLevel.name)
        add("chain", JsonArray().apply { i.chain.forEach { add(eventJson(it)) } })
    }

    private fun eventJson(e: FenceEvent) = JsonObject().apply {
        addProperty("event_id", e.id); addProperty("time", iso(e.timestamp))
        addProperty("plugin_id", e.pluginId); addProperty("plugin_version", e.pluginVersion)
        addProperty("operation", e.operation.name); addProperty("capability", e.capability?.name)
        addProperty("target", e.target); addProperty("api", e.api); addProperty("source_class", e.sourceClass)
        addProperty("verdict", e.verdict.name); addProperty("reason", e.reason); addProperty("rule", e.ruleId)
        addProperty("risk_score", e.riskScore); addProperty("risk_level", e.riskLevel.name)
        e.sensitiveCategory?.let { addProperty("sensitive_category", it) }
        e.pathRelation?.let { addProperty("path_relation", it.name) }
        add("risk_factors", JsonArray().apply { e.riskFactors.forEach { add("${it.label} (+${it.points})") } })
        val meta = e.metadata.filterKeys { it in setOf("host", "port", "scheme", "executable", "args", "url") }
        if (meta.isNotEmpty()) add("metadata", JsonObject().apply { meta.forEach { (k, v) -> addProperty(k, v) } })
    }

    private fun profileJson(p: BehaviorProfile) = JsonObject().apply {
        addProperty("version", p.version)
        add("capabilities", JsonArray().apply { p.capabilities.forEach { add(it.name) } })
        add("network_hosts", JsonArray().apply { p.networkHosts.forEach { add(it) } })
        add("processes", JsonArray().apply { p.processes.forEach { add(it) } })
        add("sensitive_resources", JsonArray().apply { p.sensitiveResources.forEach { add(it) } })
        addProperty("first_seen", iso(p.firstSeen)); addProperty("last_seen", iso(p.lastSeen)); addProperty("event_count", p.eventCount)
    }

    private fun driftJson(d: BehaviorDrift) = JsonObject().apply {
        addProperty("plugin_id", d.pluginId); addProperty("plugin_name", d.pluginName)
        addProperty("old_version", d.oldVersion); addProperty("new_version", d.newVersion)
        add("added_capabilities", JsonArray().apply { d.addedCapabilities.forEach { add(it.name) } })
        add("removed_capabilities", JsonArray().apply { d.removedCapabilities.forEach { add(it.name) } })
        add("added_hosts", JsonArray().apply { d.addedHosts.forEach { add(it) } })
        add("added_processes", JsonArray().apply { d.addedProcesses.forEach { add(it) } })
        add("added_sensitive_resources", JsonArray().apply { d.addedSensitiveResources.forEach { add(it) } })
        addProperty("risk_score", d.riskScore); addProperty("risk_level", d.riskLevel.name)
        add("risk_factors", JsonArray().apply { d.riskFactors.forEach { add("${it.label} (+${it.points})") } })
        addProperty("detected_at", iso(d.detectedAt))
    }

    private fun iso(ts: Long): String = java.time.Instant.ofEpochMilli(ts).toString()

    companion object {
        /** Parses a submit_analysis payload into a result; tolerant of sloppy enum spelling. */
        /**
         * Tolerant on purpose: small local models send strings where the schema says array, prose
         * where it says object, and creative spellings of every enum. Anything unusable is dropped;
         * nothing here may throw, because a throw would turn a finished investigation into a failure.
         */
        fun parseSubmission(task: AnalysisTask, model: String, startedAt: Long, args: JsonObject, trace: List<TraceStep>, promptTokens: Int, completionTokens: Int): AnalysisResult {
            val recommendations = ArrayList<Recommendation>()
            for (el in array(args, "recommendations")) {
                if (el.isJsonObject) {
                    val o = el.asJsonObject
                    val cap = capability(str(o.get("capability"))) ?: continue
                    val dec = decision(str(o.get("decision"))) ?: continue
                    recommendations += Recommendation(cap, dec, str(o.get("reason")) ?: "")
                } else {
                    looseRecommendation(str(el) ?: continue)?.let { recommendations += it }
                }
            }
            val targets = ArrayList<TargetChange>()
            for (el in array(args, "target_changes")) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val cap = capability(str(o.get("capability"))) ?: continue
                val target = str(o.get("target"))?.trim().orEmpty()
                if (target.isEmpty()) continue
                val approve = str(o.get("action"))?.trim()?.uppercase()?.startsWith("APPROVE") == true
                targets += TargetChange(approve, cap, target, str(o.get("reason")) ?: "")
            }
            return AnalysisResult(
                task = task,
                model = model,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                verdict = AnalystVerdict.parse(str(args.get("verdict"))),
                confidence = Confidence.parse(str(args.get("confidence"))),
                headline = str(args.get("headline"))?.trim().orEmpty().ifEmpty { "Analysis complete" },
                narrative = str(args.get("narrative"))?.trim().orEmpty(),
                evidence = strings(array(args, "evidence")),
                recommendations = recommendations,
                targetChanges = targets,
                nextSteps = strings(array(args, "next_steps")),
                trace = trace,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
            )
        }

        /** An array whatever the model sent: a real array, a JSON string containing one, or a lone value. */
        private fun array(args: JsonObject, key: String): JsonArray {
            val el = args.get(key) ?: return JsonArray()
            return when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonNull -> JsonArray()
                el.isJsonPrimitive && el.asString.trim().startsWith("[") ->
                    runCatching { JsonParser.parseString(el.asString).asJsonArray }.getOrElse { JsonArray().apply { add(el) } }
                else -> JsonArray().apply { add(el) }
            }
        }

        private fun str(el: com.google.gson.JsonElement?): String? = when {
            el == null || el.isJsonNull -> null
            el.isJsonPrimitive -> el.asString
            else -> el.toString()
        }

        /** "NETWORK: BLOCK - no legitimate endpoint" and similar free-text recommendations. */
        private fun looseRecommendation(text: String): Recommendation? {
            val tokens = text.split(Regex("[^A-Za-z_]+")).filter { it.isNotBlank() }
            val dec = tokens.firstNotNullOfOrNull { decision(it) } ?: return null
            val cap = capability(text.substringBefore(":").ifBlank { text }) ?: tokens.firstNotNullOfOrNull { capability(it) } ?: return null
            return Recommendation(cap, dec, text.trim())
        }

        private fun strings(array: JsonArray?): List<String> =
            array?.mapNotNull { runCatching { str(it)?.trim() }.getOrNull() }?.filter { it.isNotEmpty() } ?: emptyList()

        fun capability(raw: String?): Capability? {
            val key = raw?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_') ?: return null
            Capability.values().firstOrNull { it.name == key }?.let { return it }
            return when {
                key.startsWith("PROCESS") || key.contains("COMMAND") -> Capability.PROCESS_EXECUTION
                key.startsWith("NETWORK") || key.contains("SOCKET") -> Capability.NETWORK
                key.contains("ENV") || key.contains("SECRET") && !key.contains("FILE") -> Capability.SECRET_ENVIRONMENT
                key.contains("SENSITIVE") || key.contains("CREDENTIAL") -> Capability.SENSITIVE_FILES
                key.contains("OUTSIDE") -> Capability.FILES_OUTSIDE_PROJECT
                key.contains("PROJECT") -> Capability.PROJECT_FILES
                else -> null
            }
        }

        fun decision(raw: String?): PolicyDecision? {
            val key = raw?.trim()?.uppercase() ?: return null
            return PolicyDecision.values().firstOrNull { it.name == key }
                ?: when {
                    key.startsWith("BLOCK") || key.startsWith("DENY") -> PolicyDecision.BLOCK
                    key.startsWith("ALLOW") || key.startsWith("PERMIT") -> PolicyDecision.ALLOW
                    key.startsWith("ASK") || key.startsWith("PROMPT") -> PolicyDecision.ASK
                    else -> null
                }
        }

        /** Convenience for tests and diagnostics. */
        fun parseArguments(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    }
}
