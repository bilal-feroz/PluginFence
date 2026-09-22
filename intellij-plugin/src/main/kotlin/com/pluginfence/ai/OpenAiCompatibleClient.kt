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

    fun chat(messages: JsonArray, tools: JsonArray?): Reply {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            if (tools != null && tools.size() > 0) {
                add("tools", tools)
                addProperty("tool_choice", "auto")
            }
        }
        val builder = HttpRequest.newBuilder(URI.create("$endpoint/chat/completions"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: java.net.ConnectException) {
            throw ApiException("Cannot connect to $endpoint (${e.message ?: "connection refused"})")
        } catch (e: java.net.http.HttpTimeoutException) {
            throw ApiException("The model did not answer within ${requestTimeout.seconds} s")
        }
        if (response.statusCode() / 100 != 2) {
            throw ApiException("HTTP ${response.statusCode()} from $endpoint: ${errorMessage(response.body())}", response.statusCode())
        }
        return parse(response.body())
    }

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
}
