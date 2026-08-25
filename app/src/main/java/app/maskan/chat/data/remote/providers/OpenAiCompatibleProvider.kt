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
import kotlinx.serialization.json.JsonElement

class OpenAiCompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val nameAr: String,
    override val defaultBaseUrl: String,
    override val supportsCustomBaseUrl: Boolean,
    override val supportsVision: Boolean = false,
    override val isLocal: Boolean = false,
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

    /**
     * Cloud providers rotate models constantly, so the bundled list in ProviderConfigs is only a
     * fallback: this asks the provider itself which models it currently serves.
     * Every OpenAI-compatible provider we support exposes GET {baseUrl}v1/models.
     */
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): FetchedModels {
        val auth = if (apiKey.isNotBlank()) "Bearer $apiKey" else ""

        // Together lists every model it hosts, and most need a dedicated endpoint - an ordinary
        // key gets 403 on them. Ask for the non-dedicated subset first; if that yields nothing
        // (param ignored or unsupported), fall back to the full list rather than an empty picker.
        if (id == "together") {
            val serverlessJson = apiService.listModels(auth, dedicated = false)
            val serverless = ModelFilter.chatModelsOnly(ModelFilter.idsFrom(serverlessJson))
            if (serverless.isNotEmpty()) return buildResult(serverless, serverlessJson)
        }

        val response = apiService.listModels(auth)
        return buildResult(ModelFilter.chatModelsOnly(ModelFilter.idsFrom(response)), response)
    }

    private fun buildResult(ids: List<String>, raw: JsonElement): FetchedModels {
        val published = ModelFilter.visionIdsFrom(raw)
        // OpenAI publishes no per-model capability data, but its whole current chat lineup accepts
        // image input (documented), so mark those instead of hiding the camera on all of them.
        val known = if (id == "openai") {
            ids.filter { model ->
                val lower = model.lowercase()
                lower.startsWith("gpt-5") || lower.startsWith("gpt-4o") || lower.startsWith("gpt-4.1")
            }.toSet()
        } else {
            emptySet()
        }
        return FetchedModels(
            ids = ids,
            visionIds = (published + known).intersect(ids.toSet()),
            freeIds = ModelFilter.freeIdsFrom(raw).intersect(ids.toSet())
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
