package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.ChatCompletionRequest
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.OpenAiCompatibleService

class OpenAiCompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val nameAr: String,
    override val defaultBaseUrl: String,
    override val supportsCustomBaseUrl: Boolean,
    override val availableModels: List<String>,
    override val defaultModel: String,
    override val keyAcquisitionUrl: String,
    override val pricingInfo: String,
    private val apiService: OpenAiCompatibleService
) : AiProvider {

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?
    ): String {
        val request = ChatCompletionRequest(
            model = model,
            messages = messages
        )
        val response = apiService.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        return response.choices.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from $displayName API")
    }
}
