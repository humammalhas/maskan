package app.maskan.chat.data.remote.providers

import android.util.Base64
import app.maskan.chat.data.remote.ChatCompletionChunk
import app.maskan.chat.data.remote.ChatCompletionRequest
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.OpenAiCompatibleService
import app.maskan.chat.data.remote.parseSSEStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class OpenAiCompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val nameAr: String,
    override val defaultBaseUrl: String,
    override val supportsCustomBaseUrl: Boolean,
    override val supportsVision: Boolean = false,
    override val availableModels: List<String>,
    override val defaultModel: String,
    override val keyAcquisitionUrl: String,
    override val pricingInfo: String,
    private val apiService: OpenAiCompatibleService
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun buildMessages(
        messages: List<Message>,
        imageData: ByteArray?,
        imageMimeType: String?
    ): List<Message> {
        if (imageData == null || imageMimeType == null) return messages
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex == -1) return messages
        val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
        return messages.toMutableList().apply {
            val original = this[lastUserIndex]
            this[lastUserIndex] = Message(
                role = original.role,
                content = MessageContent.WithImage(
                    text = original.content.textContent(),
                    imageBase64 = base64,
                    mimeType = imageMimeType
                )
            )
        }
    }

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): String {
        val request = ChatCompletionRequest(
            model = model,
            messages = buildMessages(messages, imageData, imageMimeType)
        )
        val response = apiService.createChatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
        return response.choices.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from $displayName API")
    }

    override fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Flow<String> {
        val request = ChatCompletionRequest(
            model = model,
            messages = buildMessages(messages, imageData, imageMimeType),
            stream = true
        )
        val call = apiService.createChatCompletionStream(
            authorization = "Bearer $apiKey",
            request = request
        )
        return parseSSEStream(call) { data ->
            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
            chunk.choices.firstOrNull()?.delta?.content
        }
    }
}
