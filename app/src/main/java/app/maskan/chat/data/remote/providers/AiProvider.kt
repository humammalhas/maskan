package app.maskan.chat.data.remote.providers

import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.StreamPiece
import app.maskan.chat.data.remote.WebSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    /**
     * Whether this provider can make VIDEO through the async job API (POST /v1/videos and
     * friends). Gates the "Video model" setting and the composer entry.
     */
    val supportsVideoGeneration: Boolean get() = false

    /**
     * Whether the SELECTED image model can also change an existing photo (editImage). Cloud
     * image models that take an image in do; a local server needs a dedicated edit model,
     * which ModelFilter.editModelIn finds by name instead.
     */
    val supportsImageEditing: Boolean get() = false
    /**
     * Whether this provider may be asked a question the USER did not ask - today only the
     * automatic chat title.
     *
     * True everywhere a request is a network call someone is already paying for. It exists for
     * the on-device provider arriving in session 5: loading a model into memory costs seconds
     * and battery, and doing that to name a chat - when the user may not even be in the app any
     * more - is not a trade anyone would accept. That provider answers false unless its model
     * is already loaded.
     */
    val canAutoTitle: Boolean get() = true

    /**
     * Whether a request to this provider needs an API key.
     *
     * Defaults to the question the repository used to ask directly - a provider with a
     * user-supplied base URL is someone's own server and may well have no key at all. The
     * on-device provider answers false for a different reason: there is nothing to have a key
     * WITH, and the key gate would otherwise refuse every request before it was built.
     */
    val requiresApiKey: Boolean get() = !supportsCustomBaseUrl

    /**
     * The context window of the model this provider is about to answer with, when it is known
     * exactly, or null when it is not.
     *
     * Null for every provider that existed before the on-device one: a cloud catalogue's
     * windows differ per model and change without notice, and a user's own Ollama server could
     * be running anything. Where it IS known - a `.task` file whose KV cache is fixed at the
     * size it was built with - the request assembly spends against the real number instead of
     * a constant chosen for "a local model" in the abstract.
     */
    val contextTokens: Int? get() = null

    /**
     * Whether this provider has no system role and folds the system text into the first user
     * turn, the way Gemma's prompt template does.
     *
     * It changes nothing about what is assembled and everything about how the debug line reads:
     * after the fold the outgoing request genuinely has no system message, and a log line
     * saying `systems=0 voiceTokens=0` on a request that carries a 413-token voice would cost
     * somebody an afternoon. See logContext.
     */
    val foldsSystemPrompt: Boolean get() = false

    /**
     * What to call the wire shape of this provider's prompt in a log line, or null for the
     * ordinary chat-completions shape every cloud provider uses.
     *
     * Named per MODEL by the on-device provider, not per provider: it can be pointed at a file
     * with a real system role (ChatML) or at one with none (Gemma), and a constant would make
     * the debug line describe a request that was not sent.
     */
    val promptShapeName: String? get() = null

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
        baseUrl: String? = null,
        /** "WxH" from the composer's shape chips; providers that do not take it ignore it. */
        size: String? = null
    ): GeneratedImage = throw Exception("image generation unsupported")

    /**
     * Change an existing photo according to [prompt]. Only servers with an edit-capable model
     * (see ModelFilter.editModelIn) implement it; the default keeps the honest refusal.
     */
    suspend fun editImage(
        apiKey: String,
        model: String,
        prompt: String,
        imageDataUri: String,
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

    /**
     * [sendMessageStreaming] with the model's reasoning as well, where the provider sends it in a
     * field of its own (plan item 2; measured on Humam's keys: DeepSeek, Together, an Ollama server
     * behind Custom). The SAME request - nothing asks a model to think. The default has no
     * reasoning to give: every piece is answer.
     */
    fun sendMessageStreamingWithThinking(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String? = null,
        imageData: ByteArray? = null,
        imageMimeType: String? = null
    ): Flow<StreamPiece> =
        sendMessageStreaming(apiKey, model, messages, baseUrl, imageData, imageMimeType)
            .map { StreamPiece.Answer(it) }

    /**
     * Whether this provider can search the web itself when answering with [model]. Tested on
     * Humam's keys, not taken from documentation (RELEASE_2.7_PLAN.md item 11). Where it is false
     * the composer greys the entry out and says why.
     */
    fun searchesWeb(model: String): Boolean = false

    /**
     * The same reply with the provider's OWN web search switched on. The pages it read are handed
     * to [onSources] as they arrive - a provider may search more than once - on the stream's own
     * thread. A separate entry point rather than a flag on [sendMessageStreaming], so a request
     * without search cannot change: the golden-request tests pin that.
     *
     * The default refuses. Falling back to an ordinary reply would answer from memory while the
     * user believes it searched - the failure Open WebUI's API showed on 2026-10-23.
     */
    fun sendMessageStreamingWithSearch(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?,
        onSources: (List<WebSource>) -> Unit
    ): Flow<String> = throw UnsupportedOperationException("$id cannot search the web")
}

/**
 * A picture a provider just drew, held as bytes so the caller decides where it lands. Not a data
 * class: ByteArray equality is identity, which would make a generated equals() quietly wrong.
 */
class GeneratedImage(val bytes: ByteArray, val mimeType: String)