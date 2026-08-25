package app.maskan.chat.data.remote.providers

import android.util.Base64
import app.maskan.chat.data.remote.GeminiContent
import app.maskan.chat.data.remote.GeminiInlineData
import app.maskan.chat.data.remote.GeminiPart
import app.maskan.chat.data.remote.GeminiRequest
import app.maskan.chat.data.remote.GeminiService
import app.maskan.chat.data.remote.GeminiStreamChunk
import app.maskan.chat.data.remote.GeminiSystemInstruction
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.parseSSEStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class GeminiProvider(
    private val config: ProviderConfig,
    private val apiService: GeminiService
) : AiProvider {

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val nameAr: String = config.nameAr
    override val defaultBaseUrl: String = config.baseUrl
    override val supportsCustomBaseUrl: Boolean = false
    override val supportsVision: Boolean = config.supportsVision
    override val isLocal: Boolean = config.isLocal
    override val availableModels: List<String> = config.models
    override val defaultModel: String = config.defaultModel
    override val keyAcquisitionUrl: String = config.keyAcquisitionUrl
    override val pricingInfo: String = config.pricingInfo

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun buildRequest(
        messages: List<Message>,
        imageData: ByteArray?,
        imageMimeType: String?
    ): GeminiRequest {
        val systemMessages = messages.filter { it.role == "system" }
        val systemInstruction = systemMessages
            .joinToString("\n") { it.content.textContent() }
            .takeIf { it.isNotBlank() }
            ?.let { GeminiSystemInstruction(parts = listOf(GeminiPart(text = it))) }

        val nonSystemMessages = messages.filter { it.role != "system" }
        val lastUserIndex = nonSystemMessages.indexOfLast { it.role == "user" }

        val contents = nonSystemMessages.mapIndexed { index, msg ->
            val role = if (msg.role == "assistant") "model" else msg.role
            if (index == lastUserIndex && msg.role == "user" && imageData != null && imageMimeType != null) {
                val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
                // Drop the text part when the image has no caption: an empty text part is not a
                // valid content part (Venice rejects the equivalent with a 400).
                val text = msg.content.textContent()
                GeminiContent(
                    role = role,
                    parts = buildList {
                        add(GeminiPart(inlineData = GeminiInlineData(mimeType = imageMimeType, data = base64)))
                        if (text.isNotBlank()) add(GeminiPart(text = text))
                    }
                )
            } else {
                GeminiContent(
                    role = role,
                    parts = listOf(GeminiPart(text = msg.content.textContent()))
                )
            }
        }

        return GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction
        )
    }

    /**
     * Gemini's list endpoint returns every hosted model, including embedding and image models,
     * under fully-qualified names ("models/gemini-x"). Only entries advertising generateContent
     * can serve a chat turn, and the chat call takes the bare id, so strip the prefix.
     */
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): FetchedModels {
        val response = apiService.listModels(apiKey = apiKey)
        val ids = response.models
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .map { it.name.removePrefix("models/") }
        val chatIds = ModelFilter.chatModelsOnly(ids)
        // The generateContent models in the Gemini lineup are all multimodal on input.
        return FetchedModels(ids = chatIds, visionIds = chatIds.toSet())
    }

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): String {
        val request = buildRequest(messages, imageData, imageMimeType)

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

    override fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Flow<String> {
        val request = buildRequest(messages, imageData, imageMimeType)
        val call = apiService.streamGenerateContent(
            model = model,
            apiKey = apiKey,
            request = request
        )
        return parseSSEStream(call) { data ->
            val chunk = json.decodeFromString<GeminiStreamChunk>(data)
            chunk.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull()
                ?.text
        }
    }
}
