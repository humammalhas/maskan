package app.maskan.chat.data.remote.providers

import android.util.Base64
import app.maskan.chat.data.remote.GeminiContent
import app.maskan.chat.data.remote.GeminiImageExtractor
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
    override val supportsImageGeneration: Boolean = true

    /** Veo, through the same key - see VeoVideoClient. Billed per second of video. */
    override val supportsVideoGeneration: Boolean = true

    /** Gemini's image models take a photo in the same generateContent call that draws. */
    override val supportsImageEditing: Boolean = true
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
        // Veo models do not advertise generateContent (they are predictLongRunning), so they
        // are picked out of the UNFILTERED catalogue by name. Only ids this key can see are
        // listed - a key without Veo access simply gets no video picker entries.
        val videoIds = response.models
            .map { it.name.removePrefix("models/") }
            .filter { it.lowercase().contains("veo") }
            .sorted()
        val ids = response.models
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .map { it.name.removePrefix("models/") }
        val chatIds = ModelFilter.chatModelsOnly(ids)
        // Gemini's image models advertise generateContent like everything else, so they arrive in
        // the same list and chatModelsOnly then strips them out - correctly, they cannot hold a
        // conversation. Pick them back out of the UNFILTERED ids for the image bucket.
        val imageIds = ModelFilter.imageIdsFromNames(ids)
        // The generateContent models in the Gemini lineup are all multimodal on input.
        return FetchedModels(ids = chatIds, visionIds = chatIds.toSet(), imageIds = imageIds, videoIds = videoIds)
    }

    /**
     * Draw with a Gemini image model.
     *
     * Same generateContent endpoint as chat, with responseModalities asking for a picture. Both
     * TEXT and IMAGE are requested: some revisions reject an IMAGE-only request, and an extra
     * text part costs nothing since the extractor ignores it.
     */
    override suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        baseUrl: String?,
        size: String?
    ): GeneratedImage {
        val request = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(prompt)) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            })
        }

        val response = apiService.generateContentRaw(
            model = model,
            apiKey = apiKey,
            request = request
        )

        return GeminiImageExtractor.extract(response)
            ?: throw Exception(
                "Gemini API error: " + (GeminiImageExtractor.failureReason(response)
                    ?: "no image in response")
            )
    }

    override suspend fun editImage(
        apiKey: String,
        model: String,
        prompt: String,
        imageDataUri: String,
        baseUrl: String?
    ): GeneratedImage {
        val comma = imageDataUri.indexOf(',')
        val mime = imageDataUri.substring(0, maxOf(comma, 0)).removePrefix("data:").substringBefore(';')
            .ifBlank { "image/jpeg" }
        val data = imageDataUri.substring(comma + 1)
        val request = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", JsonPrimitive(mime))
                                put("data", JsonPrimitive(data))
                            })
                        })
                        add(buildJsonObject { put("text", JsonPrimitive(prompt)) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            })
        }
        val response = apiService.generateContentRaw(model = model, apiKey = apiKey, request = request)
        return GeminiImageExtractor.extract(response)
            ?: throw Exception(
                "Gemini API error: " + (GeminiImageExtractor.failureReason(response)
                    ?: "no image in response")
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
