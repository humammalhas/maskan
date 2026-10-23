package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.AnthropicMessage
import app.maskan.chat.data.remote.AnthropicMessageContent
import app.maskan.chat.data.remote.AnthropicRequest
import app.maskan.chat.data.remote.AnthropicService
import app.maskan.chat.data.remote.AnthropicStreamEvent
import app.maskan.chat.data.remote.AnthropicSystemBlock
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.SearchStream
import app.maskan.chat.data.remote.WebSource
import app.maskan.chat.data.remote.parseSSEStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    override val isLocal: Boolean = config.isLocal
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
                    val base64 = java.util.Base64.getEncoder().encodeToString(imageData)
                    AnthropicMessage(
                        role = msg.role,
                        content = AnthropicMessageContent.WithImage(
                            text = msg.content.textContent(),
                            imageBase64 = base64,
                            mimeType = imageMimeType
                        )
                    )
                } else {
                    // An EARLIER turn that carried a photo (ChatRepository.photosToCarry): the
                    // bytes are already base64 in the message row, so they go straight across.
                    val carried = msg.content as? MessageContent.WithImage
                    AnthropicMessage(
                        role = msg.role,
                        content = if (carried != null) {
                            AnthropicMessageContent.WithImage(
                                text = carried.text,
                                imageBase64 = carried.imageBase64,
                                mimeType = carried.mimeType
                            )
                        } else {
                            AnthropicMessageContent.Text(msg.content.textContent())
                        }
                    )
                }
            }

        return systemPrompt to AnthropicRequest(
            model = model,
            maxTokens = 4096,
            system = systemPrompt?.let { listOf(AnthropicSystemBlock(text = it)) },
            messages = conversationMessages,
            stream = stream
        )
    }

    /**
     * Anthropic exposes GET /v1/models (x-api-key + anthropic-version headers), so the app can
     * discover new Claude snapshots without an app update.
     */
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): FetchedModels {
        val response = apiService.listModels(apiKey = apiKey)
        val ids = ModelFilter.chatModelsOnly(response.data.map { it.id })
        // Anthropic ships no per-model capability data, but every Claude model in the current
        // lineup accepts image input.
        return FetchedModels(ids = ids, visionIds = ids.toSet())
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

    /** Every Claude model takes the server-side web_search tool (tested on claude-sonnet-5). */
    override fun searchesWeb(model: String): Boolean = true

    override fun sendMessageStreamingWithSearch(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?,
        onSources: (List<WebSource>) -> Unit
    ): Flow<String> {
        val (_, request) = buildRequest(model, messages, stream = true, imageData, imageMimeType)
        val call = apiService.createMessageStream(
            apiKey = apiKey,
            request = request.copy(tools = listOf(WEB_SEARCH_TOOL))
        )
        return parseSSEStream(call) { data ->
            val piece = SearchStream.anthropic(data)
            if (piece.sources.isNotEmpty()) onSources(piece.sources)
            piece.text
        }
    }

    private companion object {
        /**
         * Anthropic runs the search on its side and bills it per search; max_uses caps one
         * answer at three, which is what an ordinary question needs.
         */
        val WEB_SEARCH_TOOL = buildJsonObject {
            put("type", "web_search_20250305")
            put("name", "web_search")
            put("max_uses", 3)
        }
    }
}
