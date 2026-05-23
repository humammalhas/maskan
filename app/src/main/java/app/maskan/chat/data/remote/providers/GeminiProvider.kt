package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.GeminiContent
import app.maskan.chat.data.remote.GeminiPart
import app.maskan.chat.data.remote.GeminiRequest
import app.maskan.chat.data.remote.GeminiService
import app.maskan.chat.data.remote.GeminiSystemInstruction
import app.maskan.chat.data.remote.Message

class GeminiProvider(
    private val config: ProviderConfig,
    private val apiService: GeminiService
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
        val systemMessages = messages.filter { it.role == "system" }
        val systemInstruction = systemMessages
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }
            ?.let { GeminiSystemInstruction(parts = listOf(GeminiPart(text = it))) }

        val contents = messages
            .filter { it.role != "system" }
            .map { msg ->
                GeminiContent(
                    role = if (msg.role == "assistant") "model" else msg.role,
                    parts = listOf(GeminiPart(text = msg.content))
                )
            }

        val request = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction
        )

        val response = apiService.generateContent(
            model = model,
            apiKey = apiKey,
            request = request
        )

        response.error?.let { error ->
            throw Exception("Gemini API error: ${error.message ?: error.status ?: "Unknown error"}")
        }

        return response.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Empty response from Gemini API")
    }
}
