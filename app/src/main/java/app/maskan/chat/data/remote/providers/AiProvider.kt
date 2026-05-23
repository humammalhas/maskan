package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.Message
import kotlinx.coroutines.flow.Flow

interface AiProvider {
    val id: String
    val displayName: String
    val nameAr: String
    val defaultBaseUrl: String
    val supportsCustomBaseUrl: Boolean
    val supportsVision: Boolean get() = false
    val availableModels: List<String>
    val defaultModel: String
    val keyAcquisitionUrl: String
    val pricingInfo: String

    suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String? = null,
        imageData: ByteArray? = null,
        imageMimeType: String? = null
    ): String

    fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String? = null,
        imageData: ByteArray? = null,
        imageMimeType: String? = null
    ): Flow<String>
}
