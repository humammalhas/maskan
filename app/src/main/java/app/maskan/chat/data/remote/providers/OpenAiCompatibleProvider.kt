package app.maskan.chat.data.remote.providers

import android.util.Base64
import app.maskan.chat.data.remote.ChatCompletionChunk
import app.maskan.chat.data.remote.ChatCompletionRequest
import app.maskan.chat.data.remote.ImageGenerationRequest
import app.maskan.chat.data.remote.ImageResponseParser
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.OpenAiCompatibleService
import app.maskan.chat.data.remote.parseSSEStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject

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

    // A whitelist, not a default: DeepSeek, Groq and Mistral host no image models at all, and
    // advertising a capability they lack put an empty, puzzling "Image model" list in Settings.
    // The UI now tells the user plainly that those providers cannot draw. OpenRouter draws
    // through its own path - see generateImage.
    override val supportsImageGeneration: Boolean get() = id in IMAGE_CAPABLE_IDS

    /**
     * OpenRouter serves video jobs in (nearly) the same shape as the local proxy - POST
     * /api/v1/videos, poll, /content - so it rides the same client. Billed per second.
     */
    override val supportsVideoGeneration: Boolean get() = id == "openrouter"

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
            if (serverless.isNotEmpty()) {
                // The dedicated=false filter is about CHAT models and does not return the
                // serverless image models - FLUX.1-schnell, the one that actually works on an
                // ordinary key, is missing from it while dedicated-only image models are present.
                // Taking image ids from the filtered list therefore offered nothing but 403s.
                // Read the image bucket from the FULL catalogue instead.
                val fullJson = runCatching { apiService.listModels(auth) }.getOrNull()
                return buildResult(serverless, serverlessJson, imageSource = fullJson ?: serverlessJson)
            }
        }

        val response = apiService.listModels(auth)

        // OpenRouter keeps its video models behind ?output_modalities=video - none of them is in
        // the default catalogue. Failing that call must not break the chat list either.
        if (id == "openrouter") {
            val videoJson = runCatching { apiService.listModels(auth, outputModalities = "video") }.getOrNull()
            val base = buildResult(ModelFilter.chatModelsOnly(ModelFilter.idsFrom(response)), response)
            return if (videoJson != null) base.copy(videoIds = ModelFilter.videoIdsFrom(videoJson)) else base
        }

        // Venice keeps its image models behind ?type=image; the default list is chat only, which
        // is why 38 of them were invisible. Failing that call must not break the chat list.
        if (id == "venice") {
            val imageJson = runCatching { apiService.listModels(auth, type = "image") }.getOrNull()
            if (imageJson != null) {
                return buildResult(
                    ModelFilter.chatModelsOnly(ModelFilter.idsFrom(response)),
                    response,
                    imageSource = imageJson
                )
            }
        }

        return buildResult(ModelFilter.chatModelsOnly(ModelFilter.idsFrom(response)), response)
    }

    private fun buildResult(
        ids: List<String>,
        raw: JsonElement,
        imageSource: JsonElement = raw
    ): FetchedModels {
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
            freeIds = ModelFilter.freeIdsFrom(raw).intersect(ids.toSet()),
            // NOT intersected with ids: chatModelsOnly deliberately strips image models out of
            // the chat list, so an image model is never in ids by construction.
            imageIds = ModelFilter.imageIdsFrom(imageSource),
            videoIds = if (supportsVideoGeneration) ModelFilter.videoIdsFrom(raw) else emptyList()
        )
    }

    /**
     * Draw an image via POST /v1/images/generations - the path OpenAI, Together and Venice share.
     *
     * Asks for base64 rather than a URL so the bytes arrive in this response: no CDN round trip,
     * and nothing about the picture travels anywhere the prompt did not already go. Together
     * spells the option "base64" where everyone else says "b64_json"; ImageResponseParser copes
     * with either coming back, and falls back to downloading a URL if one is all we get.
     */
    override suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        baseUrl: String?,
        size: String?
    ): GeneratedImage {
        val auth = if (apiKey.isNotBlank()) "Bearer $apiKey" else ""
        // OpenRouter draws through the chat endpoint, not /v1/images/generations - it has no
        // such endpoint at all.
        if (id == "openrouter") return generateImageViaChat(auth, model, prompt)
        // Together calls it "base64"; OpenAI's gpt-image-* models reject the parameter outright
        // and always answer with base64, so for OpenAI the field is omitted entirely.
        val format = when (id) {
            "together" -> "base64"
            "openai" -> null
            else -> "b64_json"
        }
        val response = try {
            apiService.createImage(
                authorization = auth,
                request = ImageGenerationRequest(model = model, prompt = prompt, responseFormat = format)
            )
        } catch (e: retrofit2.HttpException) {
            // Together's image endpoint answers 403 with an EMPTY body when the account's
            // "Allow passthrough models" toggle is off - the single switch that gates all of its
            // image models. With no provider wording to surface, name the cause ourselves so
            // ErrorMapper can attach the how-to-fix hint instead of a dead "not on your plan".
            if (id == "together" && e.code() == 403) {
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                throw app.maskan.chat.data.remote.ApiHttpException(
                    code = 403,
                    providerMessage = body?.let { app.maskan.chat.data.remote.extractProviderMessage(it) }
                        ?: "image models on Together require third-party data sharing (the \"Allow passthrough models\" account setting)"
                )
            }
            throw e
        }
        return ImageResponseParser.parse(response) { url ->
            apiService.downloadUrl(url).bytes()
        }
    }

    /**
     * OpenRouter's third request path: ask the ordinary chat endpoint to answer with a picture.
     *
     * modalities: ["image","text"] tells the router the reply may be an image; the picture comes
     * back as a data: URL in choices[0].message.images, which is exactly one of the shapes
     * ImageResponseParser already reads - so the message object is handed to it as-is.
     */
    private suspend fun generateImageViaChat(
        auth: String,
        model: String,
        prompt: String
    ): GeneratedImage {
        val request = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                })
            })
            put("modalities", buildJsonArray {
                add(JsonPrimitive("image"))
                add(JsonPrimitive("text"))
            })
        }
        val response = apiService.createChatCompletionRaw(auth, request)
        val message = ((response as? JsonObject)?.get("choices") as? JsonArray)
            ?.firstOrNull()?.let { it as? JsonObject }
            ?.get("message") as? JsonObject
            ?: throw Exception("Provider returned no image")
        return ImageResponseParser.parse(message) { url ->
            apiService.downloadUrl(url).bytes()
        }
    }

    /**
     * Live balance for the two providers that publish one. "\u0631\u0635\u064a\u062f\u0643: $3.20" answers the
     * free-or-paid question better than any badge - a badge is a guess, a balance is a fact.
     */
    override suspend fun fetchBalance(apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val auth = "Bearer $apiKey"
        return when (id) {
            "openrouter" -> {
                val data = (apiService.openRouterCredits(auth) as? JsonObject)
                    ?.get("data") as? JsonObject ?: return null
                val total = (data["total_credits"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ?: return null
                val used = (data["total_usage"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
                "$" + String.format(java.util.Locale.US, "%.2f", total - used)
            }
            "deepseek" -> {
                val info = ((apiService.deepSeekBalance(auth) as? JsonObject)
                    ?.get("balance_infos") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                val amount = (info["total_balance"] as? JsonPrimitive)?.contentOrNull ?: return null
                val currency = (info["currency"] as? JsonPrimitive)?.contentOrNull ?: ""
                "$amount $currency".trim()
            }
            else -> null
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
    companion object {
        /** The OpenAI-compatible providers that can actually draw, verified on device. */
        private val IMAGE_CAPABLE_IDS = setOf("openai", "together", "venice", "openrouter")
    }
}
