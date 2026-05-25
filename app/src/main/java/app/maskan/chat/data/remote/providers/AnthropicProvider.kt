package app.maskan.chat.data.remote.providers

import android.util.Base64
import app.maskan.chat.data.remote.AnthropicMessage
import app.maskan.chat.data.remote.AnthropicMessageContent
import app.maskan.chat.data.remote.AnthropicRequest
import app.maskan.chat.data.remote.AnthropicService
import app.maskan.chat.data.remote.AnthropicStreamEvent
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.parseSSEStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class AnthropicProvider(
    private val config: ProviderConfig,
    private val apiService: AnthropicService
) : AiProvider {

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val nameAr: String = config.nameAr
    override val defaultBaseUrl: String = config.baseUrl
    override val supportsCustomBaseUrl: Boolean = false
    override val supportsVision: Boolean = config.supportsVision
    override val availableModels: List<String> = config.models
    override val defaultModel: String = config.defaultModel
    override val keyAcquisitionUrl: String = config.keyAcquisitionUrl
    override val pricingInfo: String = config.pricingInfo

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun buildRequest(
        model: String,
        messages: List<Message>,
        stream: Boolean,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Pair<String?, AnthropicRequest> {
        val systemPrompt = messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content.textContent() }
            .takeIf { it.isNotBlank() }

        val conversationMessages = messages
            .filter { it.role != "system" }
            .mapIndexed { index, msg ->
                val isLastUser = index == messages.filter { it.role != "system" }.indexOfLast { it.role == "user" }
                if (isLastUser && msg.role == "user" && imageData != null && imageMimeType != null) {
                    val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
                    AnthropicMessage(
                        role = msg.role,
                        content = AnthropicMessageContent.WithImage(
                            text = msg.content.textContent(),
                            imageBase64 = base64,
                            mimeType = imageMimeType
                        )
                    )
                } else {
                    AnthropicMessage(
                        role = msg.role,
                        content = AnthropicMessageContent.Text(msg.content.textContent())
                    )
                }
            }

        return systemPrompt to AnthropicRequest(
            model = model,
            maxTokens = 4096,
            system = systemPrompt,
            messages = conversationMessages,
            stream = stream
        )
    }

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): String {
        val (_, request) = buildRequest(model, messages, stream = false, imageData, imageMimeType)

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

    override fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Flow<String> {
        val (_, request) = buildRequest(model, messages, stream = true, imageData, imageMimeType)
        val call = apiService.createMessageStream(
            apiKey = apiKey,
            request = request
        )
        return parseSSEStream(call) { data ->
            val event = json.decodeFromString<AnthropicStreamEvent>(data)
            if (event.type == "content_block_delta") event.delta?.text else null
        }
    }
}
