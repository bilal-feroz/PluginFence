package com.pluginfence.ai

/**
 * Ready-made endpoints for the model backends people actually use with PluginFence.
 *
 * The analyst speaks the OpenAI Chat Completions protocol with tool calling, which OpenAI, Groq,
 * Ollama, LM Studio and most gateways implement; a provider is therefore just a base URL, a
 * sensible default model and where to look for a key. Nothing about the agent changes when you
 * switch - which is the point of not binding the product to one vendor.
 */
enum class AiProvider(
    val displayName: String,
    val endpoint: String,
    val defaultModel: String,
    /** Environment variables consulted when no key is stored in the IDE credential store. */
    val environmentVariables: List<String>,
    val requiresKey: Boolean,
    val hint: String,
) {
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1",
        "gpt-4.1-mini",
        listOf("OPENAI_API_KEY"),
        true,
        "Best structured answers. Key from platform.openai.com (or the hackathon vault).",
    ),
    GROQ(
        "Groq",
        "https://api.groq.com/openai/v1",
        // Available on the free tier and reliably good at the analyst's tool protocol. Model
        // availability varies per account - Settings > Test Connection reports an unavailable model.
        "openai/gpt-oss-120b",
        listOf("GROQ_API_KEY"),
        true,
        "Very fast, free tier, OpenAI-compatible tool calling. Key from console.groq.com/keys.",
    ),
    OLLAMA(
        "Ollama (local)",
        "http://localhost:11434/v1",
        "llama3.2:3b",
        emptyList(),
        false,
        "Fully local - nothing leaves this machine. Run: ollama serve",
    ),
    CUSTOM(
        "Custom",
        "",
        "",
        listOf("OPENAI_API_KEY"),
        false,
        "Any OpenAI-compatible endpoint: LM Studio, vLLM, a gateway, ...",
    ),
    ;

    /** The combo box in settings renders providers by name. */
    override fun toString(): String = displayName

    companion object {
        /** The provider a configured endpoint belongs to, for key lookup and UI selection. */
        fun of(endpoint: String): AiProvider {
            val host = runCatching { java.net.URI(endpoint.trim()).host }.getOrNull()?.lowercase().orEmpty()
            return when {
                host.endsWith("groq.com") -> GROQ
                host.endsWith("openai.com") -> OPENAI
                AiSettings.isLocalEndpoint(endpoint) -> OLLAMA
                else -> CUSTOM
            }
        }

        /** Models known to handle the analyst's tool protocol well, offered as suggestions in settings. */
        fun suggestedModels(provider: AiProvider): List<String> = when (provider) {
            OPENAI -> listOf("gpt-4.1-mini", "gpt-4.1", "gpt-5-mini", "gpt-4o-mini")
            GROQ -> listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b", "llama-3.3-70b-versatile")
            OLLAMA -> listOf("llama3.2:3b", "llama3.1:8b", "qwen2.5:7b")
            CUSTOM -> emptyList()
        }
    }
}
