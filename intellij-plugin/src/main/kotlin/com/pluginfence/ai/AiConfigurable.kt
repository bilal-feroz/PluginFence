package com.pluginfence.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JLabel

/**
 * Settings > Tools > PluginFence. Everything about the AI analyst lives here, and the page says
 * in plain words what leaves the machine when it is on.
 */
class AiConfigurable : BoundConfigurable("PluginFence") {

    private class Draft {
        var enabled = false
        var endpoint = AiSettings.DEFAULT_ENDPOINT
        var model = AiSettings.DEFAULT_MODEL
        var autoAnalyse = false
        var apiKey = ""
    }

    private val draft = Draft()
    private var status: JLabel? = null
    private var providerCombo: javax.swing.JComboBox<AiProvider>? = null
    private var providerHint: JLabel? = null
    private var endpointField: javax.swing.JTextField? = null
    private var modelField: javax.swing.JTextField? = null

    private fun load() {
        val s = AiSettings.getInstance()
        draft.enabled = s.state.enabled
        draft.endpoint = s.state.endpoint
        draft.model = s.state.model
        draft.autoAnalyse = s.state.autoAnalyseCritical
        draft.apiKey = s.storedApiKey()
    }

    override fun createPanel(): DialogPanel {
        load()
        return panel {
            group("AI security analyst") {
                row {
                    checkBox("Enable the AI analyst").bindSelected(draft::enabled)
                        .comment(
                            "Adds \"Analyse with AI\" to incidents, updates and plugin trust reports. The analyst investigates " +
                                "PluginFence's recorded evidence with read-only tools and proposes a policy; enforcement itself never " +
                                "depends on a model.",
                        )
                }
                row("Provider:") {
                    // Picking a provider fills in its endpoint and a model known to handle tool calling,
                    // so switching between OpenAI, Groq and a local model is one click, not three edits.
                    val combo = comboBox(AiProvider.values().toList())
                        .applyToComponent {
                            selectedItem = AiProvider.of(draft.endpoint)
                            addActionListener {
                                val provider = selectedItem as? AiProvider ?: return@addActionListener
                                if (provider != AiProvider.CUSTOM) {
                                    endpointField?.text = provider.endpoint
                                    modelField?.text = provider.defaultModel
                                }
                                providerHint?.text = provider.hint
                            }
                        }
                    providerCombo = combo.component
                    providerHint = label(AiProvider.of(draft.endpoint).hint).component.apply {
                        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                    }
                }
                row("Endpoint:") {
                    endpointField = textField().bindText(draft::endpoint).align(AlignX.FILL)
                        .comment("Any OpenAI-compatible chat completions endpoint.")
                        .component
                }
                row("Model:") {
                    modelField = textField().bindText(draft::model).align(AlignX.FILL)
                        .comment("Must support tool calling. Suggestions: " + AiProvider.values().filter { it != AiProvider.CUSTOM }
                            .joinToString("; ") { "${it.displayName}: ${AiProvider.suggestedModels(it).take(2).joinToString(", ")}" })
                        .component
                }
                row("API key:") {
                    passwordField().bindText(draft::apiKey).align(AlignX.FILL)
                        .comment(
                            "Stored in the IDE credential store, never in a settings file. Leave empty for local endpoints; " +
                                "GROQ_API_KEY / OPENAI_API_KEY in the environment are used as a fallback.",
                        )
                }
                row {
                    checkBox("Automatically analyse CRITICAL incidents as they happen").bindSelected(draft::autoAnalyse)
                }
                row {
                    button("Test Connection") { testConnection() }
                    status = label("").component
                }
            }
            group("What is sent") {
                row {
                    text(
                        "Only what PluginFence already records: plugin ids, names, versions and manifests, redacted file paths, " +
                            "host names, executable names, verdicts and risk factors. File contents, environment variable values " +
                            "and request bodies are never captured by PluginFence, so they cannot be sent. " +
                            "Point the endpoint at a local model to keep everything on this machine.",
                    )
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        AiSettings.getInstance().update(draft.enabled, draft.endpoint, draft.model, draft.autoAnalyse, draft.apiKey)
    }

    override fun reset() {
        load()
        super.reset()
    }

    private fun testConnection() {
        val label = status ?: return
        val endpoint = draft.endpoint.trim().trimEnd('/')
        val model = draft.model.trim()
        val key = draft.apiKey
        label.text = "Testing $model at $endpoint ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = try {
                val client = OpenAiCompatibleClient(endpoint, key, model, requestTimeout = java.time.Duration.ofSeconds(30))
                val messages = JsonArray().apply {
                    add(JsonObject().apply { addProperty("role", "user"); addProperty("content", "Reply with the single word OK.") })
                }
                val reply = client.chat(messages, null)
                "Connected. $model replied: ${reply.content?.trim()?.take(40) ?: "(tool call)"}"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            ApplicationManager.getApplication().invokeLater { label.text = outcome }
        }
    }
}
