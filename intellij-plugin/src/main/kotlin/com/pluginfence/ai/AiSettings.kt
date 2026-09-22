package com.pluginfence.ai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Configuration of the AI analyst. Off by default: PluginFence's detection, attribution and
 * enforcement never depend on a model, and nothing is sent anywhere until the user turns this on.
 *
 * The API key is kept in the IDE's credential store ([PasswordSafe]) - a tool whose whole purpose
 * is protecting secrets should not write its own into an XML file. System properties
 * (`pluginfence.ai.*`) override every value so scripted demos and smoke tests can point the
 * analyst at a local or fake endpoint without touching user settings.
 */
@State(name = "PluginFenceAi", storages = [Storage("pluginfence-ai.xml", roamingType = RoamingType.DISABLED)])
class AiSettings : PersistentStateComponent<AiSettings.State> {

    class State {
        var enabled: Boolean = false
        var endpoint: String = DEFAULT_ENDPOINT
        var model: String = DEFAULT_MODEL
        var autoAnalyseCritical: Boolean = false
        var maxSteps: Int = 8
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // --- effective values (system property > stored setting) ---------------------------------

    val enabled: Boolean
        get() = System.getProperty("pluginfence.ai.enabled")?.toBooleanStrictOrNull() ?: state.enabled

    val endpoint: String
        get() = (System.getProperty("pluginfence.ai.endpoint") ?: state.endpoint).trim().trimEnd('/').ifBlank { DEFAULT_ENDPOINT }

    val model: String
        get() = (System.getProperty("pluginfence.ai.model") ?: state.model).trim().ifBlank { DEFAULT_MODEL }

    val autoAnalyseCritical: Boolean
        get() = System.getProperty("pluginfence.ai.autoAnalyse")?.toBooleanStrictOrNull() ?: state.autoAnalyseCritical

    val maxSteps: Int
        get() = state.maxSteps.coerceIn(2, 16)

    /** Resolution order: system property, credential store, OPENAI_API_KEY environment variable. */
    val apiKey: String
        get() = System.getProperty("pluginfence.ai.apiKey")
            ?: runCatching { PasswordSafe.instance.getPassword(credentialAttributes()) }.getOrNull()
            ?: System.getenv("OPENAI_API_KEY")
            ?: ""

    /** True when a request could actually be made. Local endpoints (Ollama, LM Studio) need no key. */
    val configured: Boolean
        get() = enabled && (apiKey.isNotBlank() || isLocalEndpoint(endpoint))

    val usesLocalEndpoint: Boolean get() = isLocalEndpoint(endpoint)

    @Volatile
    private var storedKeyPresent: Boolean? = null

    /**
     * [configured] for the UI thread: reading the credential store is a slow operation the platform
     * forbids on the EDT, so the stored key's presence is cached and, when unknown, resolved on a
     * pooled thread with [onResolved] called back on the EDT to redraw.
     */
    fun isConfiguredQuick(onResolved: (() -> Unit)? = null): Boolean {
        if (!enabled) return false
        if (isLocalEndpoint(endpoint)) return true
        if (System.getProperty("pluginfence.ai.apiKey") != null || !System.getenv("OPENAI_API_KEY").isNullOrBlank()) return true
        storedKeyPresent?.let { return it }
        ApplicationManager.getApplication().executeOnPooledThread {
            storedKeyPresent = storedApiKey().isNotBlank()
            if (onResolved != null) ApplicationManager.getApplication().invokeLater(onResolved)
        }
        return false
    }

    fun update(enabled: Boolean, endpoint: String, model: String, autoAnalyse: Boolean, apiKey: String?) {
        state.enabled = enabled
        state.endpoint = endpoint.trim().ifBlank { DEFAULT_ENDPOINT }
        state.model = model.trim().ifBlank { DEFAULT_MODEL }
        state.autoAnalyseCritical = autoAnalyse
        if (apiKey != null) {
            storeApiKey(apiKey)
            storedKeyPresent = apiKey.isNotBlank()
        }
    }

    fun storedApiKey(): String = runCatching { PasswordSafe.instance.getPassword(credentialAttributes()) }.getOrNull() ?: ""

    private fun storeApiKey(key: String) {
        runCatching {
            PasswordSafe.instance.set(credentialAttributes(), if (key.isBlank()) null else Credentials("pluginfence", key))
        }
    }

    private fun credentialAttributes() = CredentialAttributes(generateServiceName("PluginFence", "ai-api-key"))

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4.1-mini"

        fun getInstance(): AiSettings = ApplicationManager.getApplication().getService(AiSettings::class.java)

        fun isLocalEndpoint(endpoint: String): Boolean {
            val host = runCatching { java.net.URI(endpoint).host }.getOrNull()?.lowercase() ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
        }
    }
}
