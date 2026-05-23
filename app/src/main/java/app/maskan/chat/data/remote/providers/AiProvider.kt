package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.Message

interface AiProvider {
    val id: String
    val displayName: String
    val nameAr: String
    val defaultBaseUrl: String
    val supportsCustomBaseUrl: Boolean
    val availableModels: List<String>
    val defaultModel: String
    val keyAcquisitionUrl: String
    val pricingInfo: String

    suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String? = null
    ): String
}
