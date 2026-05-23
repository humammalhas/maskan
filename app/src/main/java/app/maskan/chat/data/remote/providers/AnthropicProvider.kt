package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.AnthropicMessage
import app.maskan.chat.data.remote.AnthropicRequest
import app.maskan.chat.data.remote.AnthropicService
import app.maskan.chat.data.remote.Message

class AnthropicProvider(
    private val config: ProviderConfig,
    private val apiService: AnthropicService
) : AiProvider {

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val nameAr: String = config.nameAr
    override val defaultBaseUrl: String = config.baseUrl
    override val supportsCustomBaseUrl: Boolean = false
    override val availableModels: List<String> = config.models
    override val defaultModel: String = config.defaultModel
    override val keyAcquisitionUrl: String = config.keyAcquisitionUrl
    override val pricingInfo: String = config.pricingInfo

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?
    ): String {
        val systemPrompt = messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }

        val conversationMessages = messages
            .filter { it.role != "system" }
            .map { AnthropicMessage(role = it.role, content = it.content) }

        val request = AnthropicRequest(
            model = model,
            maxTokens = 4096,
            system = systemPrompt,
            messages = conversationMessages
        )

        val response = apiService.createMessage(
            apiKey = apiKey,
            request = request
        )

        response.error?.let { error ->
            throw Exception("Anthropic API error: ${error.message ?: error.type ?: "Unknown error"}")
        }

        return response.content
            ?.filter { it.type == "text" }
            ?.mapNotNull { it.text }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Empty response from Anthropic API")
    }
}
