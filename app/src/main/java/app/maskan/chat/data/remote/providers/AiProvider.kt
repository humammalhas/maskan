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

    /**
     * Whether this provider can DRAW images (as opposed to reading them - that is
     * [supportsVision]). Gates the "Image model" setting, so a provider whose catalogue lists
     * image models but whose request path is not implemented yet never offers a broken button.
     */
    val supportsImageGeneration: Boolean get() = false
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
    ): FetchedModels = FetchedModels()

    /**
     * The account's remaining balance, formatted for display, where the provider has an
     * endpoint for it (OpenRouter, DeepSeek). Null means "not knowable here", which is most
     * providers - and a real number beats any free/paid badge we could invent.
     */
    suspend fun fetchBalance(apiKey: String): String? = null

    /**
     * Ask this provider to draw [prompt] with [model] and hand back the raw bytes.
     *
     * Defaults to unsupported: only some providers generate images, and the UI only offers it
     * once the user has picked an image model for the provider, so this is the safety net rather
     * than the common case. The marker text is what ErrorMapper turns into a localized message.
     */
    suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        baseUrl: String? = null
    ): GeneratedImage = throw Exception("image generation unsupported")

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

/**
 * A picture a provider just drew, held as bytes so the caller decides where it lands. Not a data
 * class: ByteArray equality is identity, which would make a generated equals() quietly wrong.
 */
class GeneratedImage(val bytes: ByteArray, val mimeType: String)