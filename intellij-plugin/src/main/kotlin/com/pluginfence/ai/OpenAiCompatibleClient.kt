package com.pluginfence.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal client for the OpenAI Chat Completions API with tool calling.
 *
 * Deliberately dependency-free (JDK HttpClient + the platform's bundled Gson): it speaks the
 * de-facto standard that OpenAI, Ollama, LM Studio, vLLM and most gateways implement, so the same
 * code serves the hackathon key and a fully local model. No streaming - the agent loop's tool
 * calls are the progress indicator.
 */
class OpenAiCompatibleClient(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(90),
    /** Called before sleeping out a rate limit, so the UI can say why it paused. */
    private val onRetry: (attempt: Int, wait: Duration, status: Int) -> Unit = { _, _, _ -> },
) {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    /** One turn of the conversation as returned by the model. */
    class Reply(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val promptTokens: Int,
        val completionTokens: Int,
        /** The assistant message exactly as returned, to be echoed back before tool results. */
        val rawMessage: JsonObject,
    )

    class ToolCall(val id: String, val name: String, val arguments: JsonObject)

    class ApiException(message: String, val status: Int = 0) : IOException(message)

    /**
     * @param forceTool when set, the model must call that function instead of choosing freely.
     *        Used to make a model that drifted into prose deliver a structured answer.
     */
    fun chat(messages: JsonArray, tools: JsonArray?, forceTool: String? = null): Reply {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            if (tools != null && tools.size() > 0) {
                add("tools", tools)
                if (forceTool == null) {
                    addProperty("tool_choice", "auto")
                } else {
                    add("tool_choice", JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply { addProperty("name", forceTool) })
                    })
                }
            }
        }
        val request = HttpRequest.newBuilder(URI.create("$endpoint/chat/completions"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .also { if (apiKey.isNotBlank()) it.header("Authorization", "Bearer $apiKey") }
            .build()

        // Free tiers rate-limit aggressively (Groq: 8k tokens/minute), and an investigation that
        // dies half way through is worse than one that waits a moment. Providers tell us how long
        // to wait, so honour that rather than guessing.
        var attempt = 0
        while (true) {
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: java.net.ConnectException) {
                throw ApiException("Cannot connect to $endpoint (${e.message ?: "connection refused"})")
            } catch (e: java.net.http.HttpTimeoutException) {
                throw ApiException("The model did not answer within ${requestTimeout.seconds} s")
            }
            if (response.statusCode() / 100 == 2) return parse(response.body())

            val retryable = response.statusCode() == 429 || response.statusCode() in 500..599
            if (!retryable || attempt >= MAX_RETRIES) {
                throw ApiException("HTTP ${response.statusCode()} from $endpoint: ${errorMessage(response.body())}", response.statusCode())
            }
            val wait = retryDelay(response, attempt)
            onRetry(attempt + 1, wait, response.statusCode())
            try {
                Thread.sleep(wait.toMillis())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ApiException("Interrupted while waiting out a rate limit")
            }
            attempt++
        }
    }

    /** `Retry-After` header, else the "try again in 1.5s" the provider puts in the error, else backoff. */
    private fun retryDelay(response: HttpResponse<String>, attempt: Int): Duration {
        val header = response.headers().firstValue("retry-after").orElse(null)?.trim()?.toDoubleOrNull()
        if (header != null) return millis(header * 1000)

        val match = Regex("try again in ([0-9.]+)\\s*(ms|s)", RegexOption.IGNORE_CASE).find(response.body())
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val raw = if (match.groupValues[2].equals("ms", true)) value else value * 1000
            // a little headroom: token buckets refill continuously and returning a hair early just fails again
            return millis(raw + 500)
        }
        return millis((1000L shl attempt).toDouble())
    }

    private fun millis(value: Double): Duration = Duration.ofMillis(value.toLong().coerceIn(200L, MAX_WAIT_MS))

    private fun parse(body: String): Reply {
        val root = JsonParser.parseString(body).asJsonObject
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?: throw ApiException("Response contained no choices")
        val message = choice.getAsJsonObject("message") ?: throw ApiException("Response contained no message")
        val content = message.get("content")?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
        val calls = ArrayList<ToolCall>()
        message.getAsJsonArray("tool_calls")?.forEach { element ->
            val call = element.asJsonObject
            val function = call.getAsJsonObject("function") ?: return@forEach
            val name = function.get("name")?.asString ?: return@forEach
            val id = call.get("id")?.asString ?: "call_${calls.size}"
            calls += ToolCall(id, name, arguments(function.get("arguments")))
        }
        val usage = root.getAsJsonObject("usage")
        return Reply(
            content = content,
            toolCalls = calls,
            finishReason = choice.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString,
            promptTokens = usage?.get("prompt_tokens")?.asInt ?: 0,
            completionTokens = usage?.get("completion_tokens")?.asInt ?: 0,
            rawMessage = message,
        )
    }

    /** OpenAI sends arguments as a JSON string; some local servers send an object. Accept both. */
    private fun arguments(element: JsonElement?): JsonObject {
        if (element == null || element.isJsonNull) return JsonObject()
        if (element.isJsonObject) return element.asJsonObject
        val text = element.asString.trim()
        if (text.isEmpty()) return JsonObject()
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { JsonObject() }
    }

    private fun errorMessage(body: String): String = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")?.asString
    }.getOrNull()?.take(300) ?: body.take(200).replace('\n', ' ')

    private companion object {
        const val MAX_RETRIES = 3
        const val MAX_WAIT_MS = 30_000L
    }
}
