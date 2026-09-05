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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class LocalProvider(
    private val config: ProviderConfig,
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : AiProvider {

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val nameAr: String = config.nameAr
    override val defaultBaseUrl: String = config.baseUrl
    override val supportsCustomBaseUrl: Boolean = true
    override val supportsVision: Boolean = config.supportsVision
    override val isLocal: Boolean = config.isLocal

    /**
     * Only the Custom URL provider draws. Ollama and LM Studio are LLM runtimes - neither
     * serves /v1/images/generations (verified 2026-08-28), so they keep the honest
     * "cannot generate images" answer in Settings. Custom URL is where LOCAL image generation
     * lives: LocalAI natively, or ComfyUI/SD-WebUI behind an OpenAI-compatible proxy. The
     * prompt and the picture never leave the user's own network - for a privacy-first app
     * this is not a feature, it is the argument.
     */
    override val supportsImageGeneration: Boolean = config.id == "custom"

    /** Video rides on the same server as images: the Custom URL provider, and only it. */
    override val supportsVideoGeneration: Boolean = config.id == "custom"
    override val availableModels: List<String> = config.models
    override val defaultModel: String = config.defaultModel
    override val keyAcquisitionUrl: String = config.keyAcquisitionUrl
    override val pricingInfo: String = config.pricingInfo

    private val serviceCache = LinkedHashMap<String, OpenAiCompatibleService>(8, 0.75f, false)

    private fun getService(baseUrl: String): OpenAiCompatibleService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        serviceCache[normalizedUrl]?.let { return it }
        if (serviceCache.size >= 5) {
            serviceCache.remove(serviceCache.keys.first())
        }
        return serviceCache.getOrPut(normalizedUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OpenAiCompatibleService::class.java)
        }
    }

    private fun resolveUrl(baseUrl: String?): String {
        val effectiveUrl = baseUrl ?: defaultBaseUrl
        if (effectiveUrl.isBlank()) {
            throw Exception("No server URL configured. Please enter your $displayName server URL in Settings.")
        }
        return effectiveUrl
    }

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

    // Image renders on someone's own GPU are minutes, not seconds - a local Flux 2 run takes
    // ~150 s where the shared client's read timeout is 60 s. A dedicated client keeps the long
    // wait confined to this one path; chat keeps its tight timeout.
    private val imageServiceCache = LinkedHashMap<String, OpenAiCompatibleService>(4, 0.75f, false)

    private fun getImageService(baseUrl: String): OpenAiCompatibleService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        imageServiceCache[normalizedUrl]?.let { return it }
        if (imageServiceCache.size >= 3) {
            imageServiceCache.remove(imageServiceCache.keys.first())
        }
        return imageServiceCache.getOrPut(normalizedUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(
                    okHttpClient.newBuilder()
                        .readTimeout(300, TimeUnit.SECONDS)
                        .build()
                )
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OpenAiCompatibleService::class.java)
        }
    }

    /**
     * Draw via POST /v1/images/generations on the user's own server - the same request path
     * OpenAI uses, which is exactly why it is the contract here: LocalAI implements it natively
     * and a ComfyUI or SD-WebUI proxy can implement it in ~100 lines. Asks for base64 so the
     * bytes arrive in the response body and nothing leaves the user's network.
     */
    override suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        baseUrl: String?,
        size: String?
    ): GeneratedImage {
        val service = getImageService(resolveUrl(baseUrl))
        val response = service.createImage(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else "",
            request = ImageGenerationRequest(
                model = model,
                prompt = prompt,
                responseFormat = "b64_json",
                size = size
            )
        )
        val image = ImageResponseParser.parse(response) { url ->
            service.downloadUrl(url).bytes()
        }
        // Local servers may hand back WebP (a ComfyUI proxy can even return an ANIMATED WebP -
        // a short video wearing an image's clothes) or JPEG while the parser assumes PNG. The
        // magic bytes know best, and the stored mime type is what Save-to-phone names the file by.
        return GeneratedImage(image.bytes, sniffMime(image.bytes) ?: image.mimeType)
    }

    /**
     * Edit: the same request as [generateImage] with the photo in the image field. The server
     * expands the instruction itself ("change the shirt to blue" becomes a precise edit that
     * says what to keep), so the words go through untouched, in any language.
     */
    override suspend fun editImage(
        apiKey: String,
        model: String,
        prompt: String,
        imageDataUri: String,
        baseUrl: String?
    ): GeneratedImage {
        val service = getImageService(resolveUrl(baseUrl))
        val response = service.createImage(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else "",
            request = ImageGenerationRequest(
                model = model,
                prompt = prompt,
                responseFormat = "b64_json",
                image = imageDataUri
            )
        )
        val image = ImageResponseParser.parse(response) { url ->
            service.downloadUrl(url).bytes()
        }
        return GeneratedImage(image.bytes, sniffMime(image.bytes) ?: image.mimeType)
    }

    /** Content-type from magic bytes; null means "no opinion, keep what the parser said". */
    private fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        return when {
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
            else -> null
        }
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): FetchedModels {
        val service = getService(resolveUrl(baseUrl))
        val response = service.listModels(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else ""
        )
        // Local servers are the loosest of the lot (Ollama, LM Studio, llama.cpp forks all differ),
        // so go through the same tolerant parser as the cloud providers.
        val allIds = ModelFilter.idsFrom(response)

        // Best-effort: Ollama's native tag list. It says which models see images, and its
        // names are the chat models - anything the server lists that Ollama does not know is
        // something else. Any other server 404s here and both uses fall back.
        val ollamaTags = try { service.listOllamaTags() } catch (e: Exception) { null }

        // The server's own account of itself (see health() in the service): the image and video
        // buckets come from there when it exists, so nothing here guesses from names. A user
        // typed "wan-mp4" into the image model field once because the app could not tell.
        val health = try {
            service.health(if (apiKey.isNotBlank()) "Bearer $apiKey" else "") as? JsonObject
        } catch (e: Exception) {
            null
        }
        fun healthList(key: String): List<String>? =
            (health?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        val videoIds = healthList("video_models") ?: emptyList()
        val imageIds = healthList("image_models") ?: run {
            val ollamaNames = ModelFilter.ollamaModelNames(ollamaTags)
            if (health != null && ollamaNames.isNotEmpty()) {
                // A proxy that has /health but no image_models yet: the ids it serves that are
                // neither Ollama chat models nor video models are its image models.
                allIds.filter { it !in ollamaNames && it !in videoIds }
            } else {
                ModelFilter.imageIdsFromNames(allIds)
            }
        }
        val ids = ModelFilter.chatModelsOnly(allIds).filter { it !in imageIds && it !in videoIds }

        val visionIds = if (ollamaTags != null) ModelFilter.ollamaVisionIds(ollamaTags, ids) else emptySet()
        // Everything on the user's own machine is free by construction - tag it so the picker
        // can say so, the same way OpenRouter's published zero prices do.
        return FetchedModels(
            ids = ids,
            visionIds = visionIds,
            freeIds = (ids + imageIds + videoIds).toSet(),
            imageIds = imageIds.sorted(),
            videoIds = videoIds.sorted()
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
        val service = getService(resolveUrl(baseUrl))
        val request = ChatCompletionRequest(
            model = model,
            messages = buildMessages(messages, imageData, imageMimeType)
        )

        val response = service.createChatCompletion(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else "",
            request = request
        )

        return response.choices.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from $displayName")
    }

    override fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Flow<String> {
        val service = getService(resolveUrl(baseUrl))
        val request = ChatCompletionRequest(
            model = model,
            messages = buildMessages(messages, imageData, imageMimeType),
            stream = true
        )
        val call = service.createChatCompletionStream(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else "",
            request = request
        )
        return parseSSEStream(call) { data ->
            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
            chunk.choices.firstOrNull()?.delta?.content
        }
    }
}
