package app.maskan.chat.data.remote.providers

import android.content.Context
import app.maskan.chat.R
import app.maskan.chat.data.remote.Message
import app.maskan.chat.ondevice.LlmEngine
import app.maskan.chat.ondevice.LocalPrompt
import app.maskan.chat.ondevice.ModelCatalog
import app.maskan.chat.ondevice.ModelStore
import app.maskan.chat.ondevice.OnDeviceModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * The thirteenth provider, and the only one with no server behind it.
 *
 * A provider and not a mode, deliberately: the model picker, folder instructions, memory,
 * documents, "Answer again", the cost prompt and the whole request assembly already work
 * through this interface, and a separate "offline mode" would have meant reimplementing every
 * one of them slightly differently. Picking it with no model downloaded opens the download
 * card; picking another provider afterwards is how someone goes back to their own key, and
 * that path already exists.
 *
 * What is different from every other provider, and why:
 *
 *  - **No API key.** `requiresApiKey` is false because there is nothing to have a key WITH.
 *  - **`isLocal`**, so documents are chunked at the local sizes, and **`contextTokens`**, so the
 *    request budget is derived from the window this file was actually built with (4,096) rather
 *    than from a constant chosen for "a local model" in the abstract.
 *  - **`canAutoTitle` is false unless the model is already in memory.** Naming a chat is a
 *    question the user did not ask, and loading 1.6 GB to answer it - possibly after they have
 *    left the app - is not a trade anyone would accept.
 *  - **No image, no vision, no video.** The `.task` this ships is text only.
 */
class OnDeviceProvider(
    private val context: Context,
    private val engine: LlmEngine,
    private val config: ProviderConfig
) : AiProvider {

    val model: OnDeviceModel get() = ModelCatalog.DEFAULT

    private val store = ModelStore(context)

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val nameAr: String = config.nameAr
    override val defaultBaseUrl: String = ""
    override val supportsCustomBaseUrl: Boolean = false
    override val isLocal: Boolean = true
    override val requiresApiKey: Boolean = false

    override val availableModels: List<String> = listOf(ModelCatalog.DEFAULT.id)
    override val defaultModel: String = ModelCatalog.DEFAULT.id
    override val keyAcquisitionUrl: String = ""
    override val pricingInfo: String = config.pricingInfo

    /** The window the FILE was built with. See AiProvider.contextTokens. */
    override val contextTokens: Int? get() = model.contextTokens

    /**
     * Per MODEL, not per provider.
     *
     * Qwen has a real system role and Gemma has none, and this provider can be pointed at
     * either. A constant here would make the MaskanCtx line describe a request that was not
     * sent - `systems=0` on one carrying a 413-token voice, or the reverse.
     */
    override val foldsSystemPrompt: Boolean get() = !model.systemRole

    override val promptShapeName: String? get() = model.promptShapeName

    /** See AiProvider.canAutoTitle, and the doc above. */
    override val canAutoTitle: Boolean get() = engine.isLoaded

    val isInstalled: Boolean get() = store.isInstalled(model)

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): String = sendMessageStreaming(apiKey, model, messages, baseUrl, imageData, imageMimeType)
        .toList()
        .joinToString("")

    override fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Flow<String> {
        val entry = this.model
        if (!store.isInstalled(entry)) {
            // The marker ErrorMapper turns into "download the model first", which is also what
            // sends the user to the card. Not a generic failure: a missing model is the one
            // error this provider has that the user can actually fix.
            throw Exception(ERROR_NO_MODEL)
        }
        val (system, turns) = split(messages, entry)
        return engine.generate(entry, system, turns).map { it }
    }

    /**
     * As [sendMessageStreaming], but a history too long for the window is refused, never trimmed.
     * For a chat moved here from another model (2.7, plan item 10): one born here keeps today's
     * trimming, so it sends exactly what it sent before.
     */
    fun sendMessageStreamingWhole(messages: List<Message>): Flow<String> {
        val entry = this.model
        if (!store.isInstalled(entry)) throw Exception(ERROR_NO_MODEL)
        val (system, turns) = split(messages, entry)
        return engine.generate(entry, system, turns, refuseTrim = true)
    }

    /**
     * The repository's message list, split into the system text and the turns.
     *
     * The system text is assembled by ChatRepository into `system` rows - there may be more
     * than one in an old chat - and joined here rather than sent as turns, because a `system`
     * turn is what the file's own template expects and a system row written as a user turn is
     * instructions the model reads as a question.
     *
     * A file with a system role is **never** sent an empty one. Its own template never omits
     * it (Qwen's even hard-codes "You are Qwen, created by Alibaba Cloud" into the prefix we
     * replace), and on the device a bare prompt with no system turn was measurably worse: the
     * three-sentence Arabic prompt came back in Chinese three times in four. The default below
     * is a short line in the app's own language, which is also the language the answer should
     * come back in.
     */
    private fun split(
        messages: List<Message>,
        entry: OnDeviceModel
    ): Pair<String?, List<LocalPrompt.Turn>> {
        val systemText = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content.textContent() }
            .trim()
        val turns = messages
            .filter { it.role != "system" }
            .map { LocalPrompt.Turn(it.role, it.content.textContent()) }
        val system = when {
            systemText.isNotEmpty() -> systemText
            entry.systemRole -> context.getString(R.string.ondevice_default_system)
            else -> null
        }
        return system to turns
    }

    companion object {
        const val ID = "ondevice"

        /** Recognised by ErrorMapper; see sendMessageStreaming. */
        const val ERROR_NO_MODEL = "ondevice model not downloaded"
    }
}
