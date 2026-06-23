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
    val isLocal: Boolean get() = false
    val availableModels: List<String>
    val defaultModel: String
    val keyAcquisitionUrl: String
    val pricingInfo: String

    /**
     * Fetch the list of model ids actually available on the server (via GET /v1/models).
     * Local/custom providers override this; cloud providers keep their curated lists and
     * return an empty list here.
     */
    suspend fun fetchModels(
        apiKey: String,
        baseUrl: String? = null
    ): List<String> = emptyList()

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
