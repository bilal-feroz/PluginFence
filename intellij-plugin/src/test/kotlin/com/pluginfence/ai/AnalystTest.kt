package com.pluginfence.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pluginfence.model.Capability
import com.pluginfence.model.PolicyDecision
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The agent loop against a scripted OpenAI-compatible server: no network, no key. Verifies that
 * tool calls are executed and fed back with matching ids, that the structured submission is parsed,
 * and that every failure mode degrades to a result instead of an exception.
 */
class AnalystTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private val headers = CopyOnWriteArrayList<Map<String, String>>()
    private var script: MutableList<(JsonObject) -> String> = mutableListOf()
    private var statusCode = 200

    private val task = AnalysisTask.Incident("inc-1", "com.demo", "Demo Helper")

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            requests += JsonParser.parseString(body).asJsonObject
            headers += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
            val reply = if (statusCode != 200) """{"error":{"message":"bad key"}}""" else script.removeAt(0).invoke(requests.last())
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun endpoint() = "http://127.0.0.1:${server.address.port}/v1"

    private fun analyst(backend: ToolBackend, key: String = "test-key", maxSteps: Int = 8, onStep: (TraceStep) -> Unit = {}) =
        Analyst(OpenAiCompatibleClient(endpoint(), key, "fake-model", requestTimeout = Duration.ofSeconds(10)), backend, "fake-model", maxSteps, onStep)

    // --- scripted replies ---------------------------------------------------------------------

    private fun toolCallReply(vararg calls: Pair<String, String>): String {
        val toolCalls = JsonArray()
        calls.forEachIndexed { i, (name, args) ->
            toolCalls.add(JsonObject().apply {
                addProperty("id", "call_$i")
                addProperty("type", "function")
                add("function", JsonObject().apply { addProperty("name", name); addProperty("arguments", args) })
            })
        }
        val message = JsonObject().apply { addProperty("role", "assistant"); add("content", com.google.gson.JsonNull.INSTANCE); add("tool_calls", toolCalls) }
        return completion(message, "tool_calls")
    }

    private fun proseReply(text: String): String =
        completion(JsonObject().apply { addProperty("role", "assistant"); addProperty("content", text) }, "stop")

    private fun completion(message: JsonObject, finish: String): String = JsonObject().apply {
        add("choices", JsonArray().apply { add(JsonObject().apply { add("message", message); addProperty("finish_reason", finish) }) })
        add("usage", JsonObject().apply { addProperty("prompt_tokens", 100); addProperty("completion_tokens", 20) })
    }.toString()

    private val submission = """{
        "verdict": "malicious", "confidence": "HIGH",
        "headline": "Demo Helper 1.1.0 tried to exfiltrate an SSH key",
        "narrative": "It read ~/.ssh/id_rsa then opened a socket to 198.51.100.42.",
        "evidence": ["event 5: FILE_READ id_rsa BLOCKED", "event 6: NETWORK 198.51.100.42:8080 BLOCKED"],
        "recommendations": [
            {"capability": "NETWORK", "decision": "BLOCK", "reason": "no legitimate remote endpoint"},
            {"capability": "process", "decision": "deny", "reason": "sloppy spelling still maps"},
            {"capability": "bogus", "decision": "BLOCK", "reason": "ignored"}
        ],
        "target_changes": [{"action": "REVOKE", "capability": "NETWORK", "target": "198.51.100.42", "reason": "attacker host"}],
        "next_steps": ["Uninstall the plugin", "Rotate the key"]
    }""".replace("\n", " ")

    /** Records every tool call and answers with canned JSON. */
    private class ScriptedBackend : ToolBackend {
        val calls = CopyOnWriteArrayList<String>()
        override fun describeTask(task: AnalysisTask) = "Analyse ${task.key}"
        override fun call(name: String, arguments: JsonObject): ToolOutput {
            calls += "$name(${arguments})"
            return when (name) {
                "get_incident" -> ToolOutput("""{"id":"inc-1","chain":[{"event_id":5,"target":"~/.ssh/id_rsa"}]}""", "incident with 2 steps")
                "get_plugin_profile" -> ToolOutput("""{"versions":[{"version":"1.0.0"},{"version":"1.1.0"}]}""", "2 versions")
                "explode" -> throw IllegalStateException("tool blew up")
                else -> ToolOutput("""{"error":"unknown tool '$name'"}""", "unknown tool $name")
            }
        }
    }

    // --- tests ----------------------------------------------------------------------------------

    @Test
    fun `runs the tool loop and parses the structured submission`() {
        val backend = ScriptedBackend()
        script = mutableListOf(
            { toolCallReply("get_incident" to """{"incident_id":"inc-1"}""", "get_plugin_profile" to """{"plugin_id":"com.demo"}""") },
            { toolCallReply(AnalystTools.SUBMIT to submission) },
        )
        val steps = ArrayList<TraceStep>()

        val result = analyst(backend, onStep = { steps += it }).run(task)

        assertNull(result.error)
        assertEquals(AnalystVerdict.MALICIOUS, result.verdict)
        assertEquals(Confidence.HIGH, result.confidence)
        assertEquals("Demo Helper 1.1.0 tried to exfiltrate an SSH key", result.headline)
        assertEquals(2, result.evidence.size)
        assertEquals(listOf("get_incident({\"incident_id\":\"inc-1\"})", "get_plugin_profile({\"plugin_id\":\"com.demo\"})"), backend.calls)
        // sloppy but recognisable enum spellings map; unknown capability is dropped
        assertEquals(
            listOf(Capability.NETWORK to PolicyDecision.BLOCK, Capability.PROCESS_EXECUTION to PolicyDecision.BLOCK),
            result.recommendations.map { it.capability to it.decision },
        )
        assertEquals(1, result.targetChanges.size)
        assertFalse(result.targetChanges[0].approve)
        assertEquals("198.51.100.42", result.targetChanges[0].target)
        assertEquals(listOf("Uninstall the plugin", "Rotate the key"), result.nextSteps)
        assertEquals(200, result.promptTokens)
        assertEquals(40, result.completionTokens)
        assertEquals(listOf(TraceStep.Kind.TOOL, TraceStep.Kind.TOOL, TraceStep.Kind.FINAL), steps.map { it.kind })
        assertTrue(steps[0].title.startsWith("get_incident(incident_id=inc-1"))
        assertEquals("incident with 2 steps", steps[0].detail)

        // wire protocol: tools advertised, tool results echoed with matching ids, key sent as bearer
        val first = requests[0]
        assertTrue(first.getAsJsonArray("tools").size() >= 8)
        assertEquals("system", first.getAsJsonArray("messages")[0].asJsonObject["role"].asString)
        val second = requests[1].getAsJsonArray("messages")
        val toolMessages = second.filter { it.asJsonObject["role"].asString == "tool" }.map { it.asJsonObject }
        assertEquals(listOf("call_0", "call_1"), toolMessages.map { it["tool_call_id"].asString })
        assertTrue(toolMessages[0]["content"].asString.contains("id_rsa"))
        assertEquals("Bearer test-key", headers[0]["authorization"])
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `prose without a submission becomes an inconclusive result instead of an error`() {
        script = mutableListOf({ proseReply("Looks fine to me.\nNothing sensitive was touched.") })
        val result = analyst(ScriptedBackend()).run(task)
        assertNull(result.error)
        assertEquals(AnalystVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(Confidence.LOW, result.confidence)
        assertEquals("Looks fine to me.", result.headline)
        assertTrue(result.narrative.contains("Nothing sensitive"))
        assertTrue(result.recommendations.isEmpty())
    }

    @Test
    fun `http errors and refused connections become failed results`() {
        statusCode = 401
        script = mutableListOf()
        val failed = analyst(ScriptedBackend()).run(task)
        assertNotNull(failed.error)
        assertTrue(failed.error!!.contains("401"), failed.error)
        assertTrue(failed.error!!.contains("bad key"), failed.error)
        assertEquals(TraceStep.Kind.ERROR, failed.trace.last().kind)

        val dead = Analyst(OpenAiCompatibleClient("http://127.0.0.1:1/v1", "k", "m", requestTimeout = Duration.ofSeconds(5)), ScriptedBackend(), "m").run(task)
        assertNotNull(dead.error)
        assertTrue(dead.error!!.contains("Cannot connect"), dead.error)
    }

    @Test
    fun `unknown and failing tools are reported back to the model and the loop continues`() {
        val backend = ScriptedBackend()
        script = mutableListOf(
            { toolCallReply("nonexistent_tool" to "{}", "explode" to "{}") },
            { toolCallReply(AnalystTools.SUBMIT to """{"verdict":"BENIGN","confidence":"LOW","headline":"ok","narrative":"n"}""") },
        )
        val result = analyst(backend).run(task)
        assertNull(result.error)
        assertEquals(AnalystVerdict.BENIGN, result.verdict)
        val toolMessages = requests[1].getAsJsonArray("messages").filter { it.asJsonObject["role"].asString == "tool" }
        assertTrue(toolMessages[0].asJsonObject["content"].asString.contains("unknown tool"))
        assertTrue(toolMessages[1].asJsonObject["content"].asString.contains("tool blew up"))
    }

    @Test
    fun `step budget forces a final submission`() {
        val backend = ScriptedBackend()
        script = mutableListOf(
            { toolCallReply("get_incident" to """{"incident_id":"inc-1"}""") },
            { toolCallReply("get_incident" to """{"incident_id":"inc-1"}""") },
            { toolCallReply(AnalystTools.SUBMIT to """{"verdict":"SUSPICIOUS","confidence":"MEDIUM","headline":"out of budget","narrative":"n"}""") },
        )
        val result = analyst(backend, maxSteps = 2).run(task)
        assertNull(result.error)
        assertEquals(AnalystVerdict.SUSPICIOUS, result.verdict)
        assertEquals(2, backend.calls.size)
        assertTrue(requests[2].getAsJsonArray("messages").last().asJsonObject["content"].asString.contains("Call submit_analysis now"))
    }

    @Test
    fun `no authorization header for keyless local endpoints`() {
        script = mutableListOf({ proseReply("hi") })
        analyst(ScriptedBackend(), key = "").run(task)
        assertNull(headers[0]["authorization"])
        assertTrue(AiSettings.isLocalEndpoint("http://localhost:11434/v1"))
        assertTrue(AiSettings.isLocalEndpoint("http://127.0.0.1:8765/v1"))
        assertFalse(AiSettings.isLocalEndpoint("https://api.openai.com/v1"))
    }

    /** Seen from llama3.2:3b in the IDE: strings where the schema says array. Must never throw. */
    @Test
    fun `submission parsing survives strings in place of arrays and free-text recommendations`() {
        val args = EngineToolBackend.parseArguments(
            """{
                "verdict": "Suspicious", "confidence": "medium", "headline": "h", "narrative": "n",
                "evidence": "event 5 blocked a read of id_rsa",
                "recommendations": ["NETWORK: BLOCK - no legitimate endpoint", "Sensitive files: deny", "this one has no decision", {"capability":"PROCESS_EXECUTION","decision":"ASK","reason":"r"}],
                "target_changes": "revoke 198.51.100.42",
                "next_steps": ["[not json"]
            }""",
        )
        val result = EngineToolBackend.parseSubmission(task, "m", 0, args, emptyList(), 0, 0)
        assertEquals(AnalystVerdict.SUSPICIOUS, result.verdict)
        assertEquals(Confidence.MEDIUM, result.confidence)
        assertEquals(listOf("event 5 blocked a read of id_rsa"), result.evidence)
        assertEquals(
            listOf(
                Capability.NETWORK to PolicyDecision.BLOCK,
                Capability.SENSITIVE_FILES to PolicyDecision.BLOCK,
                Capability.PROCESS_EXECUTION to PolicyDecision.ASK,
            ),
            result.recommendations.map { it.capability to it.decision },
        )
        assertTrue(result.targetChanges.isEmpty(), "a bare string is not a usable target change")
        assertEquals(listOf("[not json"), result.nextSteps)
    }

    /** 429 is the free-tier reality; the client must ride it out rather than surface it as a failure. */
    @Test
    fun `rate limits are retried using the delay the provider asks for`() {
        val backend = ScriptedBackend()
        var served = 0
        server.removeContext("/v1/chat/completions")
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readAllBytes()
            served++
            val (status, payload) = if (served == 1) {
                429 to """{"error":{"message":"Rate limit reached. Please try again in 0.4s"}}"""
            } else {
                200 to toolCallReply(AnalystTools.SUBMIT to """{"verdict":"BENIGN","confidence":"LOW","headline":"fine","narrative":"n"}""")
            }
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val steps = ArrayList<TraceStep>()
        val started = System.currentTimeMillis()
        val result = analyst(backend, onStep = { steps += it }).run(task)
        val elapsed = System.currentTimeMillis() - started

        assertNull(result.error, "a rate limit must not fail the analysis")
        assertEquals(AnalystVerdict.BENIGN, result.verdict)
        assertEquals(2, served, "the request should have been retried exactly once")
        assertTrue(elapsed >= 400, "it should have waited the ~0.4s the provider asked for, waited ${elapsed}ms")
    }

    @Test
    fun `enum parsing is tolerant`() {
        assertEquals(Capability.PROCESS_EXECUTION, EngineToolBackend.capability("Process Execution"))
        assertEquals(Capability.SECRET_ENVIRONMENT, EngineToolBackend.capability("env secrets"))
        assertEquals(Capability.SENSITIVE_FILES, EngineToolBackend.capability("sensitive-files"))
        assertEquals(Capability.FILES_OUTSIDE_PROJECT, EngineToolBackend.capability("files outside project"))
        assertNull(EngineToolBackend.capability("teleport"))
        assertEquals(PolicyDecision.BLOCK, EngineToolBackend.decision("Deny"))
        assertEquals(PolicyDecision.ASK, EngineToolBackend.decision("prompt"))
        assertNull(EngineToolBackend.decision("maybe"))
        assertEquals(AnalystVerdict.LIKELY_BENIGN, AnalystVerdict.parse("likely benign"))
        assertEquals(AnalystVerdict.INCONCLUSIVE, AnalystVerdict.parse("¯\\_(ツ)_/¯"))
    }

    /**
     * The real thing: Groq's OpenAI-compatible endpoint, a real tool-calling model, the real agent
     * loop. Runs only when GROQ_API_KEY is set, so CI and contributors without a key are unaffected
     * and the key never has to live in the repo.
     *
     *   GROQ_API_KEY=... ./gradlew :intellij-plugin:test --tests "*AnalystTest*"
     */
    @Test
    fun `real groq model completes a structured investigation when a key is present`() {
        val key = System.getenv("GROQ_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank(), "GROQ_API_KEY not set; skipping real Groq test")
        val model = System.getenv("GROQ_MODEL").orEmpty().ifBlank { AiProvider.GROQ.defaultModel }

        val backend = ScriptedBackend()
        val steps = ArrayList<TraceStep>()
        val result = Analyst(
            OpenAiCompatibleClient(AiProvider.GROQ.endpoint, key, model, requestTimeout = Duration.ofSeconds(60)),
            backend, model, maxSteps = 6,
        ) { steps += it }.run(task)

        println("groq($model): verdict=${result.verdict} confidence=${result.confidence} tools=${backend.calls.size} " +
            "recommendations=${result.recommendations.size} duration=${result.durationMs}ms error=${result.error}")
        // Token budget is the demo-critical number: Groq's free tier allows 8000 per minute, and an
        // investigation that exceeds it stalls on visible retries.
        println("groq tokens: prompt=${result.promptTokens} completion=${result.completionTokens} total=${result.promptTokens + result.completionTokens}")
        val retries = result.trace.count { it.title.startsWith("Rate limited") }
        println("groq retries: $retries")
        println("groq headline: ${result.headline}")
        result.recommendations.forEach { println("  -> ${it.capability} = ${it.decision}: ${it.reason.take(90)}") }

        assertNull(result.error, "Groq run failed: ${result.error}")
        assertTrue(backend.calls.isNotEmpty(), "the model never called a tool - tool calling is broken for $model")
        assertTrue(result.headline.isNotBlank())
        // A capable model should reach a structured submission rather than trailing off into prose.
        assertTrue(
            result.trace.any { it.kind == TraceStep.Kind.FINAL },
            "no submit_analysis; trace=${result.trace.map { it.title }}",
        )
    }

    /**
     * Real model, real tool calling: runs only when a local Ollama is up (it is on the dev box).
     * Proves the wire format works against something other than our own fake.
     */
    @Test
    fun `real local model completes an investigation through ollama when available`() {
        val ollama = "http://127.0.0.1:11434"
        val up = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(java.net.URI.create("$ollama/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .let { it.statusCode() == 200 && it.body().contains("llama3.2") }
        }.getOrDefault(false)
        assumeTrue(up, "Ollama with llama3.2 not running; skipping real-model test")

        val backend = ScriptedBackend()
        val steps = ArrayList<TraceStep>()
        val result = Analyst(
            OpenAiCompatibleClient("$ollama/v1", "", "llama3.2:3b", requestTimeout = Duration.ofSeconds(120)),
            backend, "llama3.2:3b", maxSteps = 6,
        ) { steps += it }.run(task)

        println("ollama result: verdict=${result.verdict} headline='${result.headline}' error=${result.error} steps=${steps.map { it.title }}")
        assertNull(result.error, "real model run failed: ${result.error}")
        assertTrue(result.headline.isNotBlank())
    }
}
