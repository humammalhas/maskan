package app.maskan.chat.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.maskan.chat.data.local.DocumentEntity
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.SystemPromptPreset
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.AiProvider
import app.maskan.chat.data.remote.providers.OnDeviceProvider
import app.maskan.chat.data.remote.providers.ProviderOrder
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.BuildConfig
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import android.content.Intent
import android.util.Log
import app.maskan.chat.data.remote.ApiHttpException
import app.maskan.chat.data.remote.SearchStream
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.ExportFormat
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.util.ErrorMapper
import app.maskan.chat.util.ImageStore
import app.maskan.chat.util.ProjectMemory
import app.maskan.chat.util.CameraCapture
import app.maskan.chat.util.DocumentChunks
import app.maskan.chat.util.DocumentExtract
import app.maskan.chat.util.ImageUtils
import app.maskan.chat.util.PdfPageImages
import app.maskan.chat.util.TokenEstimate
import app.maskan.chat.video.VideoJobs
import app.maskan.chat.video.VideoOptions
import app.maskan.chat.video.VideoProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** A file that has been read, waiting for the user to accept what reading it will cost. */
data class DocumentCost(
    val doc: DocumentExtract.Doc,
    val tokens: Int,
    val requests: Int
)

/** A PDF with no text in it, waiting for a yes to send its first pages as pictures. */
data class ScannedOffer(
    val uri: Uri,
    val name: String,
    val pages: Int
)

/**
 * The notes pass, while it is running. Absent when nothing is running - which includes a
 * document that is half read and waiting for the user to tap Continue, because a half-read
 * document is a fact about the DOCUMENT and lives on its row, not here.
 */
data class Reading(
    val documentId: Long,
    val done: Int,
    val total: Int,
    /** Non-zero while backing off from a rate limit, so the card can say why nothing moves. */
    val waitingSeconds: Int = 0
)

/**
 * A destructive message action waiting for the user to say yes.
 *
 * [followers] is how many rows go with it, which is the whole reason there is a dialog: "edit
 * and resend" on the last message costs nothing, and on the fourth message of a twelve-message
 * conversation it costs eight messages. The user is told which of those two they are doing.
 */
data class PendingMessageAction(
    val messageId: Long,
    val kind: Kind,
    val followers: Int,
    /** The current text, for EDIT - the dialog opens on it. */
    val text: String = ""
) {
    enum class Kind { EDIT, DELETE }
}

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val selectedProviderId: String = "deepseek",
    val selectedModel: String = ProviderRegistry.getDefaultProvider().defaultModel,
    val currentPreset: SystemPromptPreset? = null,
    /**
     * The conversation's stored systemPromptId, verbatim.
     *
     * [currentPreset] cannot carry this: it is null both for "no preset, deliberately" and for
     * a custom prompt, which are two different things to show in the header, and null again for
     * "not chosen yet", which is a third. The id is the only thing that tells them apart.
     */
    val presetId: String? = null,
    val presetSelected: Boolean = false,
    /** What this chat is called. Shown in the header, where it can be tapped to rename it. */
    val title: String = "",
    val pendingImageBytes: ByteArray? = null,
    val pendingImageMimeType: String? = null,
    val pendingFileText: String? = null,
    val pendingFileName: String? = null,
    /** DocumentExtract.KIND_* of the pending file, for the "what is not read" line. */
    val pendingFileKind: String? = null,
    /** DocumentExtract.WARN_* of the pending file. A small file has no card to carry it. */
    val pendingFileWarning: String? = null,
    /** Files attached to this conversation, and how much of each has been read. */
    val documents: List<DocumentEntity> = emptyList(),
    /** True while a picked file is being extracted - a 200-page PDF takes a moment. */
    val readingFile: Boolean = false,
    val documentCost: DocumentCost? = null,
    val scannedOffer: ScannedOffer? = null,
    /** Rendered pages of a scan, waiting to go with the next message. */
    val pendingPages: List<ByteArray>? = null,
    val pendingPagesName: String? = null,
    val reading: Reading? = null,
    /**
     * The model this chat could be moved onto to recover from the current error. Non-null only
     * when the send failed BECAUSE the conversation is pinned to a model that no longer works
     * and a different model is currently selected for that provider - i.e. only when the offer
     * would actually fix something.
     */
    val recoverableModel: String? = null,
    /**
     * The composer is armed to DRAW the next message instead of chatting it. A mode rather than
     * a separate screen so the picture lands in the same conversation as the talk around it.
     */
    val imageMode: Boolean = false,
    /** Armed to make a VIDEO of the next message (with the attached photo, if any). */
    val videoMode: Boolean = false,
    /** Armed to EDIT the attached photo with the next message as the instruction. */
    val editMode: Boolean = false,
    /**
     * The next message asks the provider to search the web first (2.7). Unlike the three above
     * it does not change the request path - it is an ordinary chat reply with the provider's own
     * search switched on - and it disarms after one message, because every search is billed.
     */
    val webSearchMode: Boolean = false,
    /**
     * What the empty assistant placeholder is waiting for while a BLOCKING request runs -
     * "image" or "edit" - and since when. A chat reply streams words into that bubble so it
     * needs nothing; a drawing or an edit sits silent for minutes and needs a sentence.
     */
    val pendingKind: String? = null,
    val pendingSince: Long = 0L,
    /** The composer's shape/length choices - remembered across sessions, see VideoOptions. */
    val videoSize: String = VideoOptions.DEFAULT_SIZE,
    val videoSeconds: Int = VideoOptions.DEFAULT_SECONDS,
    val imageSize: String = VideoOptions.DEFAULT_IMAGE_SIZE,
    /** The server's own price for the armed video choice (Venice quotes; others do not). */
    val videoQuote: Double? = null,
    /**
     * The chosen image and video models, mirrored into state ON PURPOSE.
     *
     * They live in KeyRepository, and the composer used to read them through plain function
     * calls. That works only while something else happens to recompose: changing the model from
     * the + menu writes a preference, which is invisible to Compose, so the menu subtitle and the
     * cost chips would go on showing the old model until the screen was left and re-entered.
     */
    val selectedImageModelName: String = "",
    val selectedVideoModelName: String = "",
    /** True while the chat model is rewriting the user's description into an image prompt. */
    val improvingPrompt: Boolean = false,
    /**
     * The rewritten prompt, waiting for the user to accept or edit it. Held here rather than
     * pushed straight into the composer so the user always sees what changed before it is drawn.
     */
    val improvedPrompt: String? = null,
    /** The folder this conversation is in, its name and colour: "remember this", the header chip. */
    val folderId: Long? = null,
    val folderName: String = "",
    val folderColorHex: String? = null,
    /**
     * Set when a fact has just been remembered: the folder it went into, or GLOBAL_SCOPE for the
     * shared file. The screen turns it into a toast and a trip to the editor, then clears it.
     */
    val rememberedFolderId: Long? = null,
    /**
     * Live render state per pending video message, fed by the WorkManager worker's progress
     * data. Absent for a message whose worker has not reported yet (the bubble shows
     * "waiting"), and never persisted - the database holds only the job id.
     */
    val videoProgress: Map<Long, VideoProgress> = emptyMap(),
    /** An edit or a delete, waiting for the confirm dialog. */
    val pendingMessageAction: PendingMessageAction? = null
)

class ChatViewModel(
    application: Application,
    private val chatRepository: ChatRepository,
    private val keyRepository: KeyRepository,
    private val preferenceRepository: PreferenceRepository,
    private val imageStore: ImageStore
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentConversationId: Long = -1
    private var messageCollectionJob: kotlinx.coroutines.Job? = null
    private var streamingJob: Job? = null
    private var videoWatchJob: Job? = null
    private var readingJob: Job? = null

    /**
     * Work ids whose finish has already been folded into the message list. WorkManager keeps
     * reporting finished work for a while, and without this every later progress tick of some
     * OTHER video would re-read the whole conversation.
     */
    private val settledVideoWork = HashSet<java.util.UUID>()

    /**
     * Whether the model this chat is pinned to is actually GONE (as opposed to merely
     * unusable right now). Set when the recovery offer is raised, read when the user accepts
     * it, and it decides whether the old model is also dropped from the picker.
     */
    private var pinnedModelIsDead = false

    /**
     * The assistant placeholder the running image/edit request is writing into. A failure
     * carries no message id of its own, and the "could not be made" notification still needs a
     * stable id so it replaces that request's entry instead of stacking a new one.
     */
    private var lastRenderMessageId: Long = 0L

    fun loadConversation(conversationId: Long) {
        messageCollectionJob?.cancel()
        currentConversationId = conversationId
        watchVideoJobs(conversationId)
        _uiState.value = ChatUiState(
            selectedProviderId = _uiState.value.selectedProviderId,
            selectedModel = _uiState.value.selectedModel,
            isLoading = true,
            imageSize = preferenceRepository.getImageSize() ?: VideoOptions.DEFAULT_IMAGE_SIZE
        )

        messageCollectionJob = viewModelScope.launch {
            val conversation = chatRepository.getConversationById(conversationId)
            val preset = when (conversation?.systemPromptId) {
                null -> null
                ChatRepository.PRESET_NONE -> null
                "en_to_ar" -> {
                    val dialect = conversation.dialectId?.let { Dialect.fromId(it) } ?: Dialect.MSA
                    Presets.enToArPreset(dialect)
                }
                ChatRepository.PRESET_CUSTOM -> null
                else -> Presets.getById(conversation.systemPromptId)
            }
            val providerId = conversation?.providerId ?: "deepseek"
            // The CHAT's model first. Every request has always used the conversation's own
            // modelId; this line used to read the provider's Settings choice instead, so the
            // camera, the search entry and (from 2.7) the header asked about a model that was
            // not the one answering - the "model is always the same" report, seen from inside.
            val model = conversation?.modelId?.trim()?.takeIf { it.isNotBlank() }
                ?: keyRepository.getSelectedModel(providerId)
                ?: ProviderRegistry.getProvider(providerId)?.defaultModel
                ?: ProviderRegistry.getDefaultProvider().defaultModel

            val folder = conversation?.folderId?.let { chatRepository.getFolder(it) }
            _uiState.value = _uiState.value.copy(
                selectedProviderId = providerId,
                selectedModel = model,
                currentPreset = preset,
                presetId = conversation?.systemPromptId,
                presetSelected = conversation?.systemPromptId != null,
                title = conversation?.title.orEmpty(),
                folderId = folder?.id,
                folderName = folder?.name.orEmpty(),
                folderColorHex = folder?.colorHex,
                selectedImageModelName = keyRepository.getSelectedImageModel(providerId).orEmpty(),
                selectedVideoModelName = keyRepository.getSelectedVideoModel(providerId).orEmpty(),
                // A remembered "576x1024" means nothing to Veo and "16:9" nothing to Wan.
                videoSize = VideoOptions.validSize(providerId, preferenceRepository.getVideoSize()),
                videoSeconds = VideoOptions.validSeconds(providerId, preferenceRepository.getVideoSeconds())
            )

            refreshDocuments()

            chatRepository.getMessagesForConversation(conversationId).collect { messages ->
                _uiState.value = _uiState.value.copy(
                    messages = messages,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Choose - or change - this chat's system prompt.
     *
     * The same call before the first message and half way through: the repository rewrites the
     * chat's one system row either way, so the NEXT request obeys the new choice and the
     * request shape does not change. Nothing already said is touched.
     */
    fun setPreset(preset: SystemPromptPreset, dialect: Dialect? = null) {
        viewModelScope.launch {
            val dialectId = if (preset.id == "en_to_ar") (dialect?.id ?: Dialect.MSA.id) else null
            chatRepository.applyPreset(currentConversationId, preset.id, dialectId)
            _uiState.value = _uiState.value.copy(
                currentPreset = preset,
                presetId = preset.id,
                presetSelected = true
            )
            refreshMessages()
        }
    }

    /**
     * No preset at all, as a decision rather than an absence.
     *
     * Worth its own entry: before this, a chat could not be started without picking one of
     * fourteen personalities, and someone who just wants to ask a model a question had to give
     * it a character first.
     */
    fun setNoPreset() {
        viewModelScope.launch {
            chatRepository.applyPreset(currentConversationId, ChatRepository.PRESET_NONE)
            _uiState.value = _uiState.value.copy(
                currentPreset = null,
                presetId = ChatRepository.PRESET_NONE,
                presetSelected = true
            )
            refreshMessages()
        }
    }

    /** The prompt last written in "Create your own", so the box is not empty every time. */
    fun savedCustomPrompt(): String = preferenceRepository.getCustomPrompt().orEmpty()

    fun setCustomPrompt(systemPrompt: String) {
        viewModelScope.launch {
            // Kept for the NEXT chat as well as this one. It being thrown away the moment the
            // dialog closed is the whole of the "custom prompts are not saved" report: the text
            // was in the database all along, and there was no way to see it or use it again.
            preferenceRepository.setCustomPrompt(systemPrompt)
            chatRepository.applyPreset(
                currentConversationId,
                ChatRepository.PRESET_CUSTOM,
                customText = systemPrompt
            )
            _uiState.value = _uiState.value.copy(
                currentPreset = null,
                presetId = ChatRepository.PRESET_CUSTOM,
                presetSelected = true
            )
            refreshMessages()
        }
    }

    // ── The chat's provider and model ───────────────────────────

    /** One row of the chat's provider list. Nothing is hidden; what is missing is said. */
    data class ProviderChoice(
        val provider: AiProvider,
        /** Needs a key and has none. Still offered: the send then says so (plan item 10). */
        val missingKey: Boolean,
        /** On-device, and the model file is not on the phone. */
        val notDownloaded: Boolean
    )

    /**
     * In the user's order, without the providers they hid in Settings - except the one this chat
     * is on, which stays listed (ticked) so the list never denies where the chat is.
     */
    fun providerChoices(): List<ProviderChoice> {
        val all = ProviderRegistry.getAllProviders()
        val arranged = ProviderOrder.arrange(
            all.map { it.id },
            preferenceRepository.getProviderOrder(),
            preferenceRepository.getHiddenProviders()
        )
        val current = _uiState.value.selectedProviderId
        val ids = arranged.visible + arranged.hidden.filter { it == current }
        return ids.mapNotNull { id -> all.firstOrNull { it.id == id } }.map { provider ->
            ProviderChoice(
                provider = provider,
                missingKey = provider.requiresApiKey && keyRepository.getApiKey(provider.id).isNullOrBlank(),
                notDownloaded = (provider as? OnDeviceProvider)?.isInstalled == false
            )
        }
    }

    /**
     * The models offered for [providerId] - the same list Settings offers: the fetched catalogue,
     * or the built-in one before any fetch, minus models this key has been refused. The chat's
     * current model is never hidden, so the list and the header always agree.
     */
    fun modelChoices(providerId: String): List<String> {
        val provider = ProviderRegistry.getProvider(providerId) ?: return emptyList()
        val fetched = preferenceRepository.getCachedModels(providerId)
        val offered = fetched.ifEmpty { provider.availableModels }
        val refused = preferenceRepository.getUnavailableModels(providerId)
        val current = _uiState.value.selectedModel.trim()
        val isCurrentProvider = providerId == _uiState.value.selectedProviderId
        return offered.filter { it !in refused || (isCurrentProvider && it.trim() == current) }
    }

    fun visionModelsFor(providerId: String): Set<String> = preferenceRepository.getVisionModels(providerId)
    fun verifiedModelsFor(providerId: String): Set<String> = preferenceRepository.getVerifiedModels(providerId)
    fun freeModelsFor(providerId: String): Set<String> = preferenceRepository.getFreeModels(providerId)

    /** What the header calls the model: the on-device file's own name, otherwise the model id. */
    fun modelLabel(): String {
        val state = _uiState.value
        if (state.selectedProviderId == OnDeviceProvider.ID) {
            (ProviderRegistry.getProvider(OnDeviceProvider.ID) as? OnDeviceProvider)?.let {
                return it.model.displayName
            }
        }
        return state.selectedModel.trim()
    }

    /**
     * The provider and model a switch notice names, ready to show - or null for any other row.
     * Localized here, at the point it is drawn, so a stored notice follows the app's language.
     */
    fun switchNoticeParts(content: String, isArabic: Boolean): Pair<String, String>? {
        val move = app.maskan.chat.data.repository.ModelSwitch.decode(content) ?: return null
        val provider = ProviderRegistry.getProvider(move.toProvider)
        val providerName = when {
            provider == null -> move.toProvider
            isArabic -> provider.nameAr
            else -> provider.displayName
        }
        val modelName = (provider as? OnDeviceProvider)?.model?.displayName ?: move.toModel
        // Each name in a first-strong isolate: a Latin model id inside an Arabic sentence is
        // otherwise reordered around its '/' and '-' (session 3's "NPR · .1").
        return isolate(providerName) to isolate(modelName)
    }

    private fun isolate(text: String) = "\u2068" + text + "\u2069"

    /**
     * Move this chat onto [providerId] / [model] from the next message on.
     *
     * Only THIS chat: the provider's default in Settings, and so every new chat, stays as it was
     * (Humam, plan item 10). Everything the composer derives from the provider is re-read here,
     * and the armed modes are dropped - a search or a drawing armed for the old model is not a
     * promise the new one has made.
     */
    fun switchModel(providerId: String, model: String) {
        val clean = model.trim()
        if (clean.isEmpty() || ProviderRegistry.getProvider(providerId) == null) return
        val state = _uiState.value
        if (providerId == state.selectedProviderId && clean == state.selectedModel.trim()) return
        viewModelScope.launch {
            chatRepository.switchModel(currentConversationId, providerId, clean)
            _uiState.value = _uiState.value.copy(
                selectedProviderId = providerId,
                selectedModel = clean,
                webSearchMode = false,
                imageMode = false,
                videoMode = false,
                editMode = false,
                error = null,
                recoverableModel = null,
                selectedImageModelName = keyRepository.getSelectedImageModel(providerId).orEmpty(),
                selectedVideoModelName = keyRepository.getSelectedVideoModel(providerId).orEmpty(),
                videoSize = VideoOptions.validSize(providerId, preferenceRepository.getVideoSize()),
                videoSeconds = VideoOptions.validSeconds(providerId, preferenceRepository.getVideoSeconds())
            )
            refreshMessages()
        }
    }

    // ── The chat's name ─────────────────────────────────────────

    fun renameConversation(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            chatRepository.updateConversationTitle(currentConversationId, trimmed)
            _uiState.value = _uiState.value.copy(title = trimmed)
        }
    }

    /**
     * Name the chat from its first exchange, if the user has left that switch on.
     *
     * Fires once - the repository refuses a conversation that is past its first exchange or
     * carries a name somebody chose - and never blocks anything: it runs after the reply has
     * landed, in its own coroutine, and a failure changes nothing on screen.
     */
    private fun autoTitle() {
        val conversationId = currentConversationId
        viewModelScope.launch {
            chatRepository.generateTitle(conversationId)
            if (currentConversationId != conversationId) return@launch
            val conversation = chatRepository.getConversationById(conversationId) ?: return@launch
            _uiState.value = _uiState.value.copy(title = conversation.title)
        }
    }

    // ── Leaving a chat ──────────────────────────────────────

    /**
     * Discard this conversation if nothing was ever said in it.
     *
     * Called from the screen's onDispose, so the back arrow, the system back gesture and a deep
     * link that navigates somewhere else are all covered by one hook. The conversation id is
     * read BEFORE the coroutine starts: loadConversation for the next chat can win the race
     * otherwise, and the wrong conversation gets tested for emptiness.
     */
    fun discardIfEmpty() {
        val conversationId = currentConversationId
        if (conversationId < 0) return
        viewModelScope.launch { chatRepository.discardIfEmpty(conversationId) }
    }

    // ── Message-level control ──────────────────────────────────

    /**
     * Ask the same question again and replace the answer.
     *
     * Offered on the LAST reply only, which is what keeps it a one-tap action: regenerating a
     * reply in the middle of a conversation necessarily drops everything built on top of it,
     * and a one-tap action must not be destructive. The old bubble goes first so the request
     * that goes out is identical to the one that produced it - history, folder text, photos and
     * all - rather than one that has the previous answer in it.
     */
    fun regenerateLast() {
        // Not a notice: a model switch writes one after the last answer, and that answer is
        // still the one being asked again - now by the new model, under the notice that says so.
        val last = _uiState.value.messages
            .lastOrNull { it.role != "system" && it.role != ChatRepository.ROLE_NOTICE } ?: return
        if (last.role != "assistant") return
        streamingJob = viewModelScope.launch {
            chatRepository.deleteMessage(currentConversationId, last.id, alsoAfter = false)
            refreshMessages()
            runLastTurn()
        }
    }

    /**
     * A user message with no answer under it - the last send failed, or its reply was deleted -
     * is one tap from being answered. Without this the conversation is a dead end: the composer
     * wants text before it will send, and the text is already in the chat.
     */
    fun generateFromLastUserMessage() {
        val last = _uiState.value.messages
            .lastOrNull { it.role != "system" && it.role != ChatRepository.ROLE_NOTICE } ?: return
        if (last.role != "user") return
        streamingJob = viewModelScope.launch { runLastTurn() }
    }

    /** The one streaming tail shared by regenerate, generate and the error retry. */
    private suspend fun runLastTurn() {
        _uiState.value = _uiState.value.copy(isLoading = true, isStreaming = false, error = null)
        lastRequest = {
            streamingJob = viewModelScope.launch { runLastTurn() }
        }
        chatRepository.regenerateLastReply(currentConversationId)
            .catch { error -> handleSendFailure(error) }
            .collect { event -> handleStreamEvent(event) }
    }

    /** Open the confirm for an edit; the dialog needs the text and the cost before it can ask. */
    fun beginEdit(messageId: Long) {
        viewModelScope.launch {
            val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return@launch
            val followers = chatRepository.countMessagesAfter(currentConversationId, messageId)
            _uiState.value = _uiState.value.copy(
                pendingMessageAction = PendingMessageAction(
                    messageId = messageId,
                    kind = PendingMessageAction.Kind.EDIT,
                    followers = followers,
                    text = message.content
                )
            )
        }
    }

    fun beginDelete(messageId: Long) {
        viewModelScope.launch {
            val message = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return@launch
            // Deleting a QUESTION takes the answer with it and everything built on it; deleting
            // an ANSWER takes only itself, and leaves the question waiting to be answered again.
            val followers = if (message.role == "user") {
                chatRepository.countMessagesAfter(currentConversationId, messageId)
            } else {
                0
            }
            _uiState.value = _uiState.value.copy(
                pendingMessageAction = PendingMessageAction(
                    messageId = messageId,
                    kind = PendingMessageAction.Kind.DELETE,
                    followers = followers
                )
            )
        }
    }

    fun cancelMessageAction() {
        _uiState.value = _uiState.value.copy(pendingMessageAction = null)
    }

    /** Confirmed: rewrite what was said, drop what followed from it, and ask again. */
    fun confirmEdit(newText: String) {
        val action = _uiState.value.pendingMessageAction ?: return
        val trimmed = newText.trim()
        _uiState.value = _uiState.value.copy(pendingMessageAction = null)
        if (trimmed.isEmpty()) return
        streamingJob = viewModelScope.launch {
            chatRepository.editUserMessage(currentConversationId, action.messageId, trimmed)
            refreshMessages()
            runLastTurn()
        }
    }

    fun confirmDelete() {
        val action = _uiState.value.pendingMessageAction ?: return
        _uiState.value = _uiState.value.copy(pendingMessageAction = null)
        viewModelScope.launch {
            chatRepository.deleteMessage(
                currentConversationId,
                action.messageId,
                alsoAfter = action.followers > 0
            )
            refreshMessages()
            refreshDocuments()
        }
    }

    /** Whether replies carry a speak button. The setting, read where the bubble is built. */
    fun speakButtonShown(): Boolean = preferenceRepository.isSpeakButtonShown()

    fun cancelGeneration() {
        streamingJob?.cancel()
        streamingJob = null
        // If the assistant reply was cancelled before any token arrived, the repository deletes
        // the empty row from the DB — drop the matching blank bubble from the in-memory list too.
        val messages = _uiState.value.messages
        val last = messages.lastOrNull()
        val trimmed = if (last != null && last.role == "assistant" && last.content.isBlank()) {
            messages.dropLast(1)
        } else {
            messages
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isStreaming = false,
            messages = trimmed
        )
    }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            try {
                // Decoding a 12 MP JPEG is not main-thread work. viewModelScope is Main, so
                // without this the picker returned and the UI froze for as long as the decode.
                val (bytes, mimeType) = withContext(Dispatchers.IO) {
                    ImageUtils.compressImage(getApplication(), uri)
                }
                _uiState.value = _uiState.value.copy(
                    pendingImageBytes = bytes,
                    pendingImageMimeType = mimeType
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = ErrorMapper.mapToUserMessage(getApplication(), e)
                )
            }
        }
    }

    /**
     * A photo just taken with the system camera. Same composer state as a gallery photo from
     * here on - but read at the camera size, off the main thread, and the cache file is gone
     * before this returns.
     *
     * Deleted through the resolver rather than as a File: the Uri is the only thing that crossed
     * to the camera app and back, and FileProvider.delete() removes the file behind it. Nothing
     * readable is left in the cache whether the photo is sent, replaced or dropped.
     */
    fun attachCameraPhoto(uri: Uri) {
        viewModelScope.launch {
            try {
                val (bytes, mimeType) = withContext(Dispatchers.IO) {
                    val decoded = ImageUtils.compressImage(
                        getApplication(),
                        uri,
                        maxSizeKb = ImageUtils.CAMERA_MAX_KB,
                        maxDimension = ImageUtils.CAMERA_MAX_DIMENSION
                    )
                    deletePhoto(uri)
                    decoded
                }
                if (BuildConfig.DEBUG) {
                    Log.d("MaskanCam", "attached " + bytes.size + " bytes, " + mimeType)
                }
                _uiState.value = _uiState.value.copy(
                    pendingImageBytes = bytes,
                    pendingImageMimeType = mimeType
                )
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { deletePhoto(uri) }
                if (BuildConfig.DEBUG) Log.w("MaskanCam", "capture failed: " + e)
                _uiState.value = _uiState.value.copy(
                    error = ErrorMapper.mapToUserMessage(getApplication(), e)
                )
            }
        }
    }

    private fun deletePhoto(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
    }

    /** Drop camera captures the process did not live to collect. Called on entering a chat. */
    fun sweepCameraCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { CameraCapture.sweep(getApplication()) }
            runCatching { PdfPageImages.sweep(getApplication()) }
        }
    }

    fun clearPendingImage() {
        if (_uiState.value.editMode) _uiState.value = _uiState.value.copy(editMode = false)
        _uiState.value = _uiState.value.copy(
            pendingImageBytes = null,
            pendingImageMimeType = null
        )
    }

    /**
     * A picked file, whatever kind it is.
     *
     * Three ways out. Small enough to fit a request - the 2.5 path, pasted into the message.
     * Too big for that - the cost prompt, and then a table row the questions are answered from.
     * A PDF with no text at all - the offer to send its pages as pictures. Nothing is sent to a
     * provider anywhere in here.
     */
    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val fileName = resolveFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri)

            _uiState.value = _uiState.value.copy(readingFile = true, error = null)
            val outcome = withContext(Dispatchers.IO) {
                DocumentExtract.extract(context, uri, fileName, mimeType)
            }
            _uiState.value = _uiState.value.copy(readingFile = false)

            when (outcome) {
                is DocumentExtract.Outcome.Refused -> _uiState.value = _uiState.value.copy(
                    error = context.getString(refusalStringFor(outcome.reason))
                )

                is DocumentExtract.Outcome.NoText -> {
                    // The three-state rule, not currentProviderSupportsVision(): on a provider
                    // that publishes no capability data the two-state answer is "no" even for a
                    // model that plainly sees, and the offer would be missing exactly where it
                    // is most useful. Session 3 learned this the expensive way.
                    if (photoQuestionsAvailable()) {
                        _uiState.value = _uiState.value.copy(
                            scannedOffer = ScannedOffer(uri, fileName, outcome.pages)
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            error = context.getString(R.string.document_scan_no_vision)
                        )
                    }
                }

                is DocumentExtract.Outcome.Ok -> {
                    val doc = outcome.doc
                    val tokens = TokenEstimate.of(doc.text)
                    if (tokens <= DocumentChunks.INLINE_CEILING_TOKENS) {
                        _uiState.value = _uiState.value.copy(
                            pendingFileText = doc.text,
                            pendingFileName = doc.name,
                            pendingFileKind = doc.kind,
                            pendingFileWarning = doc.warning
                        )
                    } else {
                        val chunkTokens = chatRepository.chunkTokensFor(currentConversationId)
                        val requests = DocumentChunks.chunk(doc.text, chunkTokens).size
                        _uiState.value = _uiState.value.copy(
                            documentCost = DocumentCost(doc, tokens, requests)
                        )
                    }
                }
            }
        }
    }

    private fun refusalStringFor(reason: String): Int = when (reason) {
        DocumentExtract.REFUSED_TOO_LARGE -> R.string.file_too_large
        DocumentExtract.REFUSED_LEGACY -> R.string.document_legacy_format
        DocumentExtract.REFUSED_MACRO -> R.string.document_macro_format
        DocumentExtract.REFUSED_ENCRYPTED -> R.string.document_encrypted
        DocumentExtract.REFUSED_CERT -> R.string.document_cert_encrypted
        DocumentExtract.REFUSED_EMPTY -> R.string.document_empty
        else -> R.string.file_read_error
    }

    /** The user accepted the cost. The row is written now; the reading starts now. */
    fun confirmDocumentCost() {
        val cost = _uiState.value.documentCost ?: return
        _uiState.value = _uiState.value.copy(documentCost = null)
        viewModelScope.launch {
            // The card sits after whatever the conversation ended with, so it stays where it
            // appeared instead of sliding to the bottom as the chat grows.
            val after = _uiState.value.messages.lastOrNull()?.id
            val id = chatRepository.saveDocument(currentConversationId, cost.doc, after)
            refreshDocuments()
            startReading(id)
        }
    }

    fun cancelDocumentCost() {
        _uiState.value = _uiState.value.copy(documentCost = null)
    }

    /**
     * Read the next chunks. Never called on its own - the cost prompt's Continue starts it, and
     * the card's Continue resumes it. A pass that stopped, for any reason, stays stopped until
     * the person whose requests these are asks for more.
     */
    fun startReading(documentId: Long) {
        // Already running for this document: the card's Continue is a no-op, not a second pass.
        if (_uiState.value.reading?.documentId == documentId) return
        readingJob?.cancel()

        // Set BEFORE the first request, not when the first chunk lands: the gap is one whole
        // request long, and for that gap the card offered Continue on a pass already running.
        val document = _uiState.value.documents.firstOrNull { it.id == documentId }
        _uiState.value = _uiState.value.copy(
            reading = Reading(
                documentId = documentId,
                done = document?.notesDone ?: 0,
                total = document?.chunkCount ?: 0
            )
        )

        readingJob = viewModelScope.launch {
            chatRepository.readDocument(documentId).collect { event ->
                when (event) {
                    is ChatRepository.NotesEvent.Progress -> {
                        _uiState.value = _uiState.value.copy(
                            reading = Reading(documentId, event.done, event.total)
                        )
                        refreshDocuments()
                    }
                    is ChatRepository.NotesEvent.Waiting -> {
                        val current = _uiState.value.reading
                        _uiState.value = _uiState.value.copy(
                            reading = current?.copy(waitingSeconds = event.seconds)
                                ?: Reading(documentId, 0, 0, event.seconds)
                        )
                    }
                    is ChatRepository.NotesEvent.Stopped -> {
                        _uiState.value = _uiState.value.copy(
                            reading = null,
                            error = ErrorMapper.mapToUserMessage(getApplication(), event.error)
                        )
                        refreshDocuments()
                    }
                    ChatRepository.NotesEvent.Finished -> {
                        _uiState.value = _uiState.value.copy(reading = null)
                        refreshDocuments()
                    }
                }
            }
        }
    }

    fun stopReading() {
        readingJob?.cancel()
        _uiState.value = _uiState.value.copy(reading = null)
        viewModelScope.launch { refreshDocuments() }
    }

    fun forgetDocument(documentId: Long) {
        viewModelScope.launch {
            if (_uiState.value.reading?.documentId == documentId) stopReading()
            chatRepository.deleteDocument(documentId)
            refreshDocuments()
        }
    }

    private suspend fun refreshDocuments() {
        val conversationId = currentConversationId
        if (conversationId < 0) return
        val documents = chatRepository.documentsOnce(conversationId)
        if (currentConversationId == conversationId) {
            _uiState.value = _uiState.value.copy(documents = documents)
        }
    }

    /** Render the first pages of a scan and hold them for the next message. */
    fun acceptScannedPages() {
        val offer = _uiState.value.scannedOffer ?: return
        _uiState.value = _uiState.value.copy(scannedOffer = null, readingFile = true)
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                PdfPageImages.render(getApplication(), offer.uri)
            }
            _uiState.value = if (pages.isEmpty()) {
                _uiState.value.copy(
                    readingFile = false,
                    error = getApplication<Application>().getString(R.string.file_read_error)
                )
            } else {
                _uiState.value.copy(
                    readingFile = false,
                    pendingPages = pages,
                    pendingPagesName = offer.name
                )
            }
        }
    }

    fun declineScannedPages() {
        _uiState.value = _uiState.value.copy(scannedOffer = null)
    }

    fun clearPendingPages() {
        _uiState.value = _uiState.value.copy(pendingPages = null, pendingPagesName = null)
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "file.txt"
    }

    fun clearPendingFile() {
        _uiState.value = _uiState.value.copy(
            pendingFileText = null,
            pendingFileName = null,
            pendingFileKind = null,
            pendingFileWarning = null
        )
    }

    /**
     * Vision is a property of the MODEL, not the provider. Where the provider publishes
     * capability data (OpenRouter, Venice, Ollama) or its whole lineup is known multimodal
     * (Gemini, Anthropic, OpenAI), the cached set decides - so the camera shows up for Qwen3-VL
     * on Venice and hides on a text-only model at a provider that also hosts vision ones.
     * With no data cached, fall back to the provider-level flag rather than guessing.
     */
    fun currentProviderSupportsVision(): Boolean {
        val providerId = _uiState.value.selectedProviderId
        val provider = ProviderRegistry.getProvider(providerId)
        val visionModels = preferenceRepository.getVisionModels(providerId)
        if (visionModels.isEmpty()) return provider?.supportsVision == true
        return _uiState.value.selectedModel.trim() in visionModels
    }

    /**
     * Whether to offer the three photo questions under a waiting photo.
     *
     * NOT currentProviderSupportsVision(): that answers "do we know it can see", and on a
     * provider that publishes no capability data - Together, most of the list - the answer is no
     * even for models that plainly can. The chips would then be missing exactly where the camera
     * is offered. The rule here is the one ChatRepository.modelKnownBlind uses for carrying a
     * photo forward: offer them unless the app has data saying this model takes text only.
     */
    fun photoQuestionsAvailable(): Boolean {
        val visionModels = preferenceRepository.getVisionModels(_uiState.value.selectedProviderId)
        if (visionModels.isEmpty()) return true
        return _uiState.value.selectedModel.trim() in visionModels
    }

    /**
     * Whether this provider can draw, and we know of models to draw with. Gates the composer
     * entry point, so the option never appears where tapping it could only fail.
     */
    /**
     * Whether this provider can draw AT ALL - decides if the draw button exists in the composer.
     * canGenerateImages() then decides whether it is armed. Split because hiding the button
     * until an image model was chosen made the whole feature invisible on a fresh install -
     * indistinguishable from a broken APK.
     */
    fun imageFeatureAvailable(): Boolean {
        val provider = ProviderRegistry.getProvider(_uiState.value.selectedProviderId)
        return provider?.supportsImageGeneration == true
    }

    fun canGenerateImages(): Boolean {
        val providerId = _uiState.value.selectedProviderId
        val provider = ProviderRegistry.getProvider(providerId) ?: return false
        if (!provider.supportsImageGeneration) return false
        // Require a CHOSEN model, not merely models that exist: the button is a one-tap arm, so
        // it must never lead to "pick an image model in Settings first".
        return _uiState.value.selectedImageModelName.isNotBlank()
    }

    fun setImageMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            imageMode = enabled, videoMode = false, editMode = false, webSearchMode = false
        )
    }

    /**
     * Whether the selected provider and model can search the web themselves. Tested provider by
     * provider (AiProvider.searchesWeb); where it is false the entry is greyed out with a reason,
     * never armed to answer from memory.
     */
    fun canSearchWeb(): Boolean {
        val provider = ProviderRegistry.getProvider(_uiState.value.selectedProviderId) ?: return false
        return provider.searchesWeb(_uiState.value.selectedModel.trim())
    }

    fun setWebSearchMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            webSearchMode = enabled && canSearchWeb(),
            imageMode = false, videoMode = false, editMode = false
        )
    }

    /** The server's edit model, if it lists one - the whole gate for the edit entry. */
    fun editModel(): String? {
        val providerId = _uiState.value.selectedProviderId
        val provider = ProviderRegistry.getProvider(providerId) ?: return null
        if (!provider.supportsImageGeneration) return null
        // Together edits only on FLUX.1 Kontext, so its edit model is found in the list by name
        // (Together's own words otherwise: "Unsupported use of 'image_url' parameter").
        // Other cloud providers edit with the chosen image model. Local: a named edit model.
        if (providerId == "together") {
            return app.maskan.chat.data.remote.providers.ModelFilter.editModelIn(
                preferenceRepository.getImageModels(providerId)
            )
        }
        if (provider.supportsImageEditing) {
            return keyRepository.getSelectedImageModel(providerId)?.trim()?.takeIf { it.isNotBlank() }
        }
        return app.maskan.chat.data.remote.providers.ModelFilter.editModelIn(
            preferenceRepository.getImageModels(providerId)
        )
    }

    fun setEditMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            editMode = enabled, imageMode = false, videoMode = false, webSearchMode = false
        )
    }

    fun editImage(prompt: String) {
        if (prompt.isBlank()) return
        val bytes = _uiState.value.pendingImageBytes ?: return
        val mime = _uiState.value.pendingImageMimeType ?: "image/jpeg"
        val model = editModel() ?: return
        clearPendingImage()
        runEdit(prompt, bytes, mime, model)
    }

    private fun runEdit(prompt: String, bytes: ByteArray, mime: String, model: String) {
        lastRequest = { runEdit(prompt, bytes, mime, model) }
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = false,
                error = null,
                editMode = false,
                pendingKind = "edit",
                pendingSince = System.currentTimeMillis()
            )
            chatRepository.editImage(currentConversationId, prompt, model, bytes, mime)
                .catch { error -> handleSendFailure(error) }
                .collect { event -> handleStreamEvent(event) }
        }
    }

    fun videoFeatureAvailable(): Boolean {
        val provider = ProviderRegistry.getProvider(_uiState.value.selectedProviderId)
        return provider?.supportsVideoGeneration == true
    }

    /** Armed only with a CHOSEN video model, for the same reason as [canGenerateImages]. */
    fun canGenerateVideos(): Boolean {
        val providerId = _uiState.value.selectedProviderId
        val provider = ProviderRegistry.getProvider(providerId) ?: return false
        if (!provider.supportsVideoGeneration) return false
        return _uiState.value.selectedVideoModelName.isNotBlank()
    }

    fun setVideoSize(size: String) {
        preferenceRepository.setVideoSize(size)
        _uiState.value = _uiState.value.copy(videoSize = size)
        refreshVideoQuote()
    }

    fun setVideoSeconds(seconds: Int) {
        preferenceRepository.setVideoSeconds(seconds)
        _uiState.value = _uiState.value.copy(videoSeconds = seconds)
        refreshVideoQuote()
    }

    private var quoteJob: Job? = null

    /** One small request per change of choice; the answer is the provider's, not ours. */
    private fun refreshVideoQuote() {
        quoteJob?.cancel()
        _uiState.value = _uiState.value.copy(videoQuote = null)
        if (!_uiState.value.videoMode) return
        val state = _uiState.value
        quoteJob = viewModelScope.launch {
            val quote = chatRepository.quoteVideo(
                state.selectedProviderId, selectedVideoModel(), state.videoSeconds, state.videoSize
            )
            if (_uiState.value.videoMode) _uiState.value = _uiState.value.copy(videoQuote = quote)
        }
    }

    fun setImageSize(size: String) {
        preferenceRepository.setImageSize(size)
        _uiState.value = _uiState.value.copy(imageSize = size)
    }

    /** Shape chips only where the size is honoured as typed - the user's own server. */
    fun imageSizeChoiceAvailable(): Boolean =
        ProviderRegistry.getProvider(_uiState.value.selectedProviderId)?.supportsCustomBaseUrl == true

    fun setVideoMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            videoMode = enabled, imageMode = false, editMode = false, webSearchMode = false
        )
        refreshVideoQuote()
    }

    fun selectedVideoModel(): String = _uiState.value.selectedVideoModelName

    // -- The + menu's own pickers (plan 1.4) -----------------------------
    //
    // Settings owns the same two preferences, but ChatScreen is never handed a
    // SettingsViewModel - that one is built in MainActivity for the Settings route alone. These
    // read and write the very same KeyRepository / PreferenceRepository entries, so a model
    // chosen here is the model Settings shows, and the other way round.

    fun imageModelChoices(): List<String> =
        preferenceRepository.getImageModels(_uiState.value.selectedProviderId)

    fun videoModelChoices(): List<String> =
        preferenceRepository.getVideoModels(_uiState.value.selectedProviderId)

    fun freeModels(): Set<String> =
        preferenceRepository.getFreeModels(_uiState.value.selectedProviderId)

    fun selectImageModel(model: String) {
        val clean = model.trim()
        keyRepository.saveSelectedImageModel(_uiState.value.selectedProviderId, clean)
        _uiState.value = _uiState.value.copy(selectedImageModelName = clean)
    }

    fun selectVideoModel(model: String) {
        val clean = model.trim()
        keyRepository.saveSelectedVideoModel(_uiState.value.selectedProviderId, clean)
        _uiState.value = _uiState.value.copy(selectedVideoModelName = clean)
        // The cost chips are priced per model, so a new model reprices them.
        refreshVideoQuote()
    }

    fun generateVideo(prompt: String) {
        if (prompt.isBlank()) return
        val imageData = _uiState.value.pendingImageBytes
        val imageMimeType = _uiState.value.pendingImageMimeType
        clearPendingImage()
        runVideo(prompt, imageData, imageMimeType)
    }

    private fun runVideo(prompt: String, imageData: ByteArray?, imageMimeType: String?) {
        lastRequest = { runVideo(prompt, imageData, imageMimeType) }
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = false,
                error = null,
                videoMode = false
            )
            chatRepository.generateVideo(
                currentConversationId, prompt, imageData, imageMimeType,
                _uiState.value.videoSize, _uiState.value.videoSeconds
            )
                .catch { error -> handleSendFailure(error) }
                .collect { event -> handleStreamEvent(event) }
        }
    }

    /** Decrypted bytes for a stored image, or null if the file is missing. Never throws. */
    fun readImage(path: String): ByteArray? = imageStore.read(path)

    /**
     * Have the chat model turn a rough description into a usable image prompt. The user reviews
     * the result before anything is drawn - see improveImagePrompt in the repository for why.
     */
    fun improvePrompt(rough: String) {
        if (rough.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(improvingPrompt = true, error = null)
            val result = chatRepository.improveImagePrompt(currentConversationId, rough)
            result.fold(
                onSuccess = { improved ->
                    _uiState.value = _uiState.value.copy(
                        improvingPrompt = false,
                        improvedPrompt = improved
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        improvingPrompt = false,
                        error = ErrorMapper.mapToUserMessage(getApplication(), error)
                    )
                }
            )
        }
    }

    fun clearImprovedPrompt() {
        _uiState.value = _uiState.value.copy(improvedPrompt = null)
    }

    fun generateImage(prompt: String) {
        if (prompt.isBlank()) return
        lastRequest = { generateImage(prompt) }
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = false,
                error = null,
                imageMode = false,
                pendingKind = "image",
                pendingSince = System.currentTimeMillis()
            )
            chatRepository.generateImage(currentConversationId, prompt, _uiState.value.imageSize)
                .catch { error -> handleSendFailure(error) }
                .collect { event -> handleStreamEvent(event) }
        }
    }

    fun sendMessage(content: String) {
        // Armed to draw: the same Send button, a different request path.
        if (_uiState.value.imageMode) {
            generateImage(content)
            return
        }
        if (_uiState.value.videoMode) {
            generateVideo(content)
            return
        }
        if (_uiState.value.editMode) {
            editImage(content)
            return
        }
        // "Remember this", typed. A local command, not a request: nothing goes to a provider, and
        // the memory file opens showing the line that was written. Checked after the three generate
        // modes so an armed drawing still draws, and only where there is somewhere to remember TO -
        // in a chat with no folder and shared memory off, "remember that..." is just a sentence.
        if (rememberTarget() != null) {
            val fact = ProjectMemory.factOrNull(content)
            if (fact != null) {
                rememberFact(fact)
                return
            }
        }

        // Pages of a scan: several pictures in one turn, which the ordinary send path (one
        // image per request) cannot express on its own.
        val pages = _uiState.value.pendingPages
        if (!pages.isNullOrEmpty()) {
            sendPages(content, pages, _uiState.value.pendingPagesName.orEmpty())
            return
        }

        if (content.isBlank() && _uiState.value.pendingImageBytes == null && _uiState.value.pendingFileText == null) return

        val imageData = _uiState.value.pendingImageBytes
        val imageMimeType = _uiState.value.pendingImageMimeType

        val effectiveContent = buildString {
            _uiState.value.pendingFileText?.let { fileText ->
                val name = _uiState.value.pendingFileName ?: "file.txt"
                append("[File: $name]\n\n")
                append(fileText)
                if (content.isNotBlank()) append("\n\n")
            }
            append(content)
        }

        clearPendingImage()
        clearPendingFile()

        // Read once and disarmed at once: the search is for THIS message only. Checked against
        // the provider again here, so a model switched after arming can never send a "search"
        // that quietly answers from memory.
        val webSearch = _uiState.value.webSearchMode && canSearchWeb()
        _uiState.value = _uiState.value.copy(webSearchMode = false)

        lastRequest = {
            streamingJob = viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, isStreaming = false, error = null)
                chatRepository.regenerateLastReply(currentConversationId)
                    .catch { error -> handleSendFailure(error) }
                    .collect { event -> handleStreamEvent(event) }
            }
        }
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = false,
                error = null,
                // A search can take a while before the first word; the empty bubble says what it
                // is waiting for instead of looking stuck.
                pendingKind = if (webSearch) "search" else null,
                pendingSince = if (webSearch) System.currentTimeMillis() else _uiState.value.pendingSince
            )

            chatRepository.sendMessageStreaming(
                conversationId = currentConversationId,
                userContent = effectiveContent,
                model = _uiState.value.selectedModel,
                imageData = imageData,
                imageMimeType = imageMimeType,
                webSearch = webSearch
            ).catch { error ->
                handleSendFailure(error)
            }.collect { event ->
                handleStreamEvent(event)
            }
        }
    }

    /**
     * Send the first pages of a scanned PDF, in order, as one turn.
     *
     * The earlier pages are written as their own user rows and the LAST one rides the request as
     * the current attachment, because one image per request is the shape the whole send path
     * has. The document row then remembers all of their ids, which is what makes the follow-up
     * carry the set rather than the ordinary two most recent pictures - page 1 of a letter is
     * the page that says who it is from.
     */
    private fun sendPages(content: String, pages: List<ByteArray>, name: String) {
        val context: Context = getApplication()
        val question = content.ifBlank { context.getString(R.string.document_pages_question) }
        clearPendingPages()

        lastRequest = {
            streamingJob = viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, isStreaming = false, error = null)
                chatRepository.regenerateLastReply(currentConversationId)
                    .catch { error -> handleSendFailure(error) }
                    .collect { event -> handleStreamEvent(event) }
            }
        }
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isStreaming = false, error = null)

            val pageIds = ArrayList<Long>()
            for ((index, bytes) in pages.dropLast(1).withIndex()) {
                pageIds.add(
                    chatRepository.saveMessage(
                        conversationId = currentConversationId,
                        role = "user",
                        content = context.getString(R.string.document_page_label, index + 1, name),
                        imageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                        imageMimeType = "image/jpeg"
                    )
                )
            }

            // text is empty: there is nothing to chunk and nothing to retrieve, and chunkCount
            // stays 0 so the request assembly never tries to quote a file made of pictures.
            val documentId = chatRepository.saveDocument(
                conversationId = currentConversationId,
                doc = DocumentExtract.Doc(
                    name = name,
                    kind = DocumentExtract.KIND_PDF,
                    pages = pages.size,
                    text = ""
                ),
                attachedMessageId = pageIds.firstOrNull()
            )
            refreshMessages()
            refreshDocuments()

            chatRepository.sendMessageStreaming(
                conversationId = currentConversationId,
                userContent = question,
                model = _uiState.value.selectedModel,
                imageData = pages.last(),
                imageMimeType = "image/jpeg"
            ).catch { error ->
                handleSendFailure(error)
            }.collect { event ->
                handleStreamEvent(event)
                if (event is ChatRepository.StreamEvent.UserSaved) {
                    pageIds.add(event.message.id)
                    chatRepository.setDocumentPages(documentId, pageIds)
                    refreshDocuments()
                }
            }
        }
    }

    private fun handleStreamEvent(event: ChatRepository.StreamEvent) {
        when (event) {
            is ChatRepository.StreamEvent.UserSaved -> {
                upsertMessage(event.message)
            }
            is ChatRepository.StreamEvent.Started -> {
                lastRenderMessageId = event.message.id
                upsertMessage(event.message)
                _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = true)
            }
            is ChatRepository.StreamEvent.Token -> {
                updateMessageContent(event.messageId, event.fullContent)
            }
            is ChatRepository.StreamEvent.ImageReady -> {
                val kind = _uiState.value.pendingKind
                upsertMessage(event.message)
                _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = false, pendingKind = null)
                announceRender(event.message.id, kind, success = true, detail = null)
            }
            is ChatRepository.StreamEvent.VideoQueued -> {
                // The composer is free again the moment the server has the job; the bubble
                // itself reports progress from here on.
                upsertMessage(event.message)
                _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = false)
            }
            is ChatRepository.StreamEvent.Sources -> {
                val encoded = SearchStream.encode(event.sources)
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.map {
                        if (it.id == event.messageId) it.copy(sourcesJson = encoded) else it
                    }
                )
            }
            is ChatRepository.StreamEvent.Done -> {
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    pendingKind = _uiState.value.pendingKind.takeIf { it != "search" }
                )
                autoTitle()
            }
        }
    }

    private suspend fun handleSendFailure(error: Throwable) {
        val kind = _uiState.value.pendingKind
        // Classify BEFORE building the message: both read the error body, and the classification
        // is the one that must not come up empty.
        val recoverable = findRecoverableModel(error)
        val shown = ErrorMapper.mapToUserMessage(getApplication(), error)
        if (BuildConfig.DEBUG) {
            // Plan 1.3: the only way to be sure no provider path still answers a 4xx with "No
            // internet" is to read, for every failure, what was thrown and what the user was
            // told. One line, debug builds only.
            val code = when (error) {
                is ApiHttpException -> error.code
                is retrofit2.HttpException -> error.code()
                else -> -1
            }
            Log.w("Maskan", "send failed: " + error.javaClass.simpleName + " http=" + code +
                " shown=" + shown + " raw=" + error.message)
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isStreaming = false,
            error = shown,
            recoverableModel = recoverable,
            pendingKind = null
        )
        announceRender(lastRenderMessageId, kind, success = false, detail = shown)
    }

    /**
     * A picture and an edit run IN the app, not in a worker, so nothing tells the user when one
     * lands: the 300 s cloud edit and the 171 s local flux2-edit are long enough that people put
     * the phone down. Post the same "ready" notification the video worker posts - but only when
     * no screen of ours is showing, because on screen the bubble already said it.
     */
    private fun announceRender(messageId: Long, kind: String?, success: Boolean, detail: String?) {
        if (kind != "image" && kind != "edit") return
        val maskan = getApplication<Application>() as? MaskanApplication ?: return
        if (maskan.isInForeground) return
        // localizedContext, not maskan: this string is read with no Activity in the picture.
        val title = maskan.localizedContext.getString(
            when {
                kind == "edit" && success -> R.string.edit_ready
                kind == "edit" -> R.string.edit_failed
                success -> R.string.image_ready
                else -> R.string.image_failed
            }
        )
        maskan.videoJobs.showDone(messageId, currentConversationId, title, detail)
    }

    /**
     * A conversation freezes its modelId when it is created, so a chat opened months ago keeps
     * calling a model the provider may since have retired - the model picker being clean does
     * not help it. Offer the switch only when it would actually fix the failure: the error says
     * the model is gone, this chat really is pinned, and the provider's current selection is a
     * different model.
     */
    private suspend fun findRecoverableModel(error: Throwable): String? {
        pinnedModelIsDead = false
        val conversation = chatRepository.getConversationById(currentConversationId) ?: return null
        val pinned = conversation.modelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Pass the pinned id: on a 400 that is what tells "this model is gone" apart from a
        // malformed request, since the provider names the model it refused.
        val verdict = ErrorMapper.classifyModelFailure(error, pinned)
        if (verdict == ErrorMapper.ModelRecovery.NONE) return null
        val current = keyRepository.getSelectedModel(conversation.providerId)
            ?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Same model both sides: the user's own selection is what failed, so switching is a
        // no-op and the error stands on its own (out of credit, rate limited, model retired).
        if (current == pinned) return null
        pinnedModelIsDead = verdict == ErrorMapper.ModelRecovery.DEAD
        return current
    }

    /**
     * Rewrite this conversation's frozen modelId to the provider's currently selected model and
     * re-run the last turn. The retry goes through regenerateLastReply, NOT sendMessage: the
     * user's message was already saved before the failure, so re-sending it would duplicate the
     * bubble. The retired model is also recorded as unavailable so the picker stops offering it.
     */
    fun switchModelAndRetry() {
        val newModel = _uiState.value.recoverableModel ?: return
        streamingJob = viewModelScope.launch {
            val conversation = chatRepository.getConversationById(currentConversationId)
            val oldModel = conversation?.modelId
            // Only blacklist a model the provider says is GONE. A 402 (no credit) or 429 (rate
            // limited) model is fine and will work again - hiding it from the picker would take
            // a manual refresh to undo.
            if (pinnedModelIsDead && conversation != null && !oldModel.isNullOrBlank()) {
                preferenceRepository.addUnavailableModel(conversation.providerId, oldModel)
            }
            pinnedModelIsDead = false
            chatRepository.updateConversationModel(currentConversationId, newModel)

            _uiState.value = _uiState.value.copy(
                selectedModel = newModel,
                error = null,
                recoverableModel = null,
                isLoading = true,
                isStreaming = false
            )

            chatRepository.regenerateLastReply(currentConversationId)
                .catch { error -> handleSendFailure(error) }
                .collect { event -> handleStreamEvent(event) }
        }
    }

    /**
     * Insert a message into the in-memory list, or replace it if one with the same id already
     * exists (e.g. the DB invalidation Flow happened to emit it too). Keying by id keeps the
     * open chat correct for every message — first or follow-up — without relying on Room's
     * (unreliable under SQLCipher) UPDATE/INSERT invalidation.
     */
    private fun upsertMessage(message: MessageEntity) {
        val current = _uiState.value.messages
        val index = current.indexOfFirst { it.id == message.id }
        val updated = if (index >= 0) {
            current.toMutableList().also { it[index] = message }
        } else {
            current + message
        }
        _uiState.value = _uiState.value.copy(messages = updated)
    }

    private fun updateMessageContent(messageId: Long, content: String) {
        val current = _uiState.value.messages
        val index = current.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val updated = current.toMutableList().also {
                it[index] = it[index].copy(content = content)
            }
            _uiState.value = _uiState.value.copy(messages = updated)
        }
    }

    // ── Video ───────────────────────────────────────────────────────────

    /**
     * Mirror the video workers of this conversation into the UI state. Progress comes from the
     * worker's progress data; when a worker FINISHES (clip landed, failed, cancelled) the
     * message list is re-read from the database once, because Room's invalidation Flow is not
     * relied on under SQLCipher and the change was made by another process context anyway.
     */
    private fun watchVideoJobs(conversationId: Long) {
        videoWatchJob?.cancel()
        settledVideoWork.clear()
        videoWatchJob = viewModelScope.launch {
            WorkManager.getInstance(getApplication())
                .getWorkInfosByTagFlow(VideoJobs.tagForConversation(conversationId))
                .collect { infos ->
                    val progress = HashMap<Long, VideoProgress>()
                    var settled = false
                    for (info in infos) {
                        val messageId = VideoJobs.messageIdFromTags(info.tags) ?: continue
                        when {
                            info.state == WorkInfo.State.RUNNING ->
                                progress[messageId] = VideoProgress.fromData(info.progress)
                                    ?: VideoProgress.WAITING
                            info.state.isFinished ->
                                if (settledVideoWork.add(info.id)) settled = true
                            else -> progress[messageId] = VideoProgress.WAITING
                        }
                    }
                    _uiState.value = _uiState.value.copy(videoProgress = progress)
                    // Not while a reply is streaming: the DB holds only periodic snapshots of
                    // that text and a refresh would visibly rewind it.
                    if (settled && !_uiState.value.isStreaming) refreshMessages()
                }
        }
    }

    private suspend fun refreshMessages() {
        val conversationId = currentConversationId
        if (conversationId < 0) return
        val messages = chatRepository.getMessagesOnce(conversationId)
        if (currentConversationId == conversationId) {
            _uiState.value = _uiState.value.copy(messages = messages)
        }
    }

    /**
     * A failed render (server said failed, or forgot the job after a restart) is retried with
     * the same words and the same photo: the user's own message sits right above the failed
     * row, so nothing has to be retyped. The failed row goes; a fresh pending one replaces it.
     */
    fun retryVideo(messageId: Long) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index <= 0) return
        val request = messages.subList(0, index).lastOrNull { it.role == "user" } ?: return
        val photo = request.imageBase64?.let { b64 ->
            try { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
        }
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.filterNot { it.id == messageId },
                isLoading = true,
                error = null
            )
            chatRepository.generateVideo(
                currentConversationId, request.content, photo, request.imageMimeType,
                _uiState.value.videoSize, _uiState.value.videoSeconds, saveUserMessage = false
            )
                .catch { error -> handleSendFailure(error) }
                .collect { event -> handleStreamEvent(event) }
        }
    }

    fun cancelVideo(messageId: Long) {
        viewModelScope.launch {
            chatRepository.cancelVideo(messageId)
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.filterNot { it.id == messageId }
            )
        }
    }

    fun setSelectedModel(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun exportConversation(format: ExportFormat) {
        viewModelScope.launch {
            try {
                val text = chatRepository.exportConversation(currentConversationId, format)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, null)
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<android.app.Application>().startActivity(chooser)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = getApplication<android.app.Application>().getString(R.string.export_failed)
                )
            }
        }
    }

    /**
     * Where a remembered fact would go: this conversation's folder, or the shared file when the
     * user has turned it on, or nowhere.
     */
    fun rememberTarget(): Long? {
        _uiState.value.folderId?.let { return it }
        return if (preferenceRepository.isGlobalMemoryEnabled()) {
            ProjectFilesViewModel.GLOBAL_SCOPE
        } else {
            null
        }
    }

    /**
     * Append one dated line to the memory file and tell the screen to open it.
     *
     * Read-modify-write rather than an SQL append: the file is a few hundred bytes, the user is
     * the only other writer, and re-reading means the line lands after whatever they last typed
     * in the editor rather than after whatever was cached here.
     */
    fun rememberFact(fact: String) {
        val target = rememberTarget() ?: return
        val trimmed = fact.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (target == ProjectFilesViewModel.GLOBAL_SCOPE) {
                preferenceRepository.setGlobalMemory(
                    ProjectMemory.append(preferenceRepository.getGlobalMemory(), trimmed)
                )
            } else {
                val folder = chatRepository.getFolder(target)
                chatRepository.updateFolderMemory(
                    target,
                    ProjectMemory.append(folder?.memory, trimmed)
                )
            }
            _uiState.value = _uiState.value.copy(rememberedFolderId = target)
        }
    }

    fun clearRemembered() {
        _uiState.value = _uiState.value.copy(rememberedFolderId = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, recoverableModel = null)
    }

    /**
     * The last thing the user asked for, so a refused request - a provider 4xx, a timeout, a
     * dead link - is one tap from being asked again. Held as a closure because the four request
     * kinds carry different state (a photo, a model, the shape chips) that the composer has
     * already let go of by the time the error shows.
     */
    private var lastRequest: (() -> Unit)? = null

    fun canRetry(): Boolean = lastRequest != null

    fun retryLast() {
        val request = lastRequest ?: return
        clearError()
        request()
    }

    companion object {
        private const val MAX_FILE_TEXT_BYTES = 50 * 1024
    }
}
