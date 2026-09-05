package app.maskan.chat.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.SystemPromptPreset
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.R
import android.content.Intent
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.ExportFormat
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.util.ErrorMapper
import app.maskan.chat.util.ImageStore
import app.maskan.chat.util.ImageUtils
import app.maskan.chat.video.VideoJobs
import app.maskan.chat.video.VideoProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val selectedProviderId: String = "deepseek",
    val selectedModel: String = ProviderRegistry.getDefaultProvider().defaultModel,
    val currentPreset: SystemPromptPreset? = null,
    val presetSelected: Boolean = false,
    val pendingImageBytes: ByteArray? = null,
    val pendingImageMimeType: String? = null,
    val pendingFileText: String? = null,
    val pendingFileName: String? = null,
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
    /** True while the chat model is rewriting the user's description into an image prompt. */
    val improvingPrompt: Boolean = false,
    /**
     * The rewritten prompt, waiting for the user to accept or edit it. Held here rather than
     * pushed straight into the composer so the user always sees what changed before it is drawn.
     */
    val improvedPrompt: String? = null,
    /**
     * Live render state per pending video message, fed by the WorkManager worker's progress
     * data. Absent for a message whose worker has not reported yet (the bubble shows
     * "waiting"), and never persisted - the database holds only the job id.
     */
    val videoProgress: Map<Long, VideoProgress> = emptyMap()
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

    fun loadConversation(conversationId: Long) {
        messageCollectionJob?.cancel()
        currentConversationId = conversationId
        watchVideoJobs(conversationId)
        _uiState.value = ChatUiState(
            selectedProviderId = _uiState.value.selectedProviderId,
            selectedModel = _uiState.value.selectedModel,
            isLoading = true
        )

        messageCollectionJob = viewModelScope.launch {
            val conversation = chatRepository.getConversationById(conversationId)
            val preset = when (conversation?.systemPromptId) {
                null -> null
                "en_to_ar" -> {
                    val dialect = conversation.dialectId?.let { Dialect.fromId(it) } ?: Dialect.MSA
                    Presets.enToArPreset(dialect)
                }
                "custom" -> null
                else -> Presets.getById(conversation.systemPromptId)
            }
            val providerId = conversation?.providerId ?: "deepseek"
            val model = keyRepository.getSelectedModel(providerId)
                ?: ProviderRegistry.getProvider(providerId)?.defaultModel
                ?: ProviderRegistry.getDefaultProvider().defaultModel

            _uiState.value = _uiState.value.copy(
                selectedProviderId = providerId,
                selectedModel = model,
                currentPreset = preset,
                presetSelected = conversation?.systemPromptId != null
            )

            chatRepository.getMessagesForConversation(conversationId).collect { messages ->
                _uiState.value = _uiState.value.copy(
                    messages = messages,
                    isLoading = false
                )
            }
        }
    }

    fun setPreset(preset: SystemPromptPreset, dialect: Dialect? = null) {
        viewModelScope.launch {
            val dialectId = if (preset.id == "en_to_ar") (dialect?.id ?: Dialect.MSA.id) else null
            chatRepository.updateSystemPrompt(currentConversationId, preset.id, dialectId)
            _uiState.value = _uiState.value.copy(
                currentPreset = preset,
                presetSelected = true
            )
        }
    }

    fun setCustomPrompt(systemPrompt: String) {
        viewModelScope.launch {
            chatRepository.updateSystemPrompt(currentConversationId, "custom", null)
            chatRepository.saveMessage(currentConversationId, "system", systemPrompt)
            _uiState.value = _uiState.value.copy(
                currentPreset = null,
                presetSelected = true
            )
        }
    }

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
                val (bytes, mimeType) = ImageUtils.compressImage(getApplication(), uri)
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

    fun clearPendingImage() {
        _uiState.value = _uiState.value.copy(
            pendingImageBytes = null,
            pendingImageMimeType = null
        )
    }

    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val context: Context = getApplication()
                val fileName = resolveFileName(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "text/plain"

                val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: throw Exception("Cannot read file")

                val text = if (mimeType == "text/html") {
                    raw.replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("&nbsp;"), " ")
                        .replace(Regex("&amp;"), "&")
                        .replace(Regex("&lt;"), "<")
                        .replace(Regex("&gt;"), ">")
                        .replace(Regex("&quot;"), "\"")
                        .replace(Regex("&#39;"), "'")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                } else {
                    raw
                }

                if (text.toByteArray().size > MAX_FILE_TEXT_BYTES) {
                    _uiState.value = _uiState.value.copy(
                        error = context.getString(app.maskan.chat.R.string.file_too_large)
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    pendingFileText = text,
                    pendingFileName = fileName
                )
            } catch (e: Exception) {
                val context: Context = getApplication()
                _uiState.value = _uiState.value.copy(
                    error = context.getString(app.maskan.chat.R.string.file_read_error)
                )
            }
        }
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
            pendingFileName = null
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
        return !keyRepository.getSelectedImageModel(providerId).isNullOrBlank()
    }

    fun setImageMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(imageMode = enabled, videoMode = false)
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
        return !keyRepository.getSelectedVideoModel(providerId).isNullOrBlank()
    }

    fun setVideoMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(videoMode = enabled, imageMode = false)
    }

    fun generateVideo(prompt: String) {
        if (prompt.isBlank()) return
        val imageData = _uiState.value.pendingImageBytes
        val imageMimeType = _uiState.value.pendingImageMimeType
        clearPendingImage()
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = false,
                error = null,
                videoMode = false
            )
            chatRepository.generateVideo(currentConversationId, prompt, imageData, imageMimeType)
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
        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isStreaming = false,
                error = null,
                imageMode = false
            )
            chatRepository.generateImage(currentConversationId, prompt)
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

        streamingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isStreaming = false, error = null)

            chatRepository.sendMessageStreaming(
                conversationId = currentConversationId,
                userContent = effectiveContent,
                model = _uiState.value.selectedModel,
                imageData = imageData,
                imageMimeType = imageMimeType
            ).catch { error ->
                handleSendFailure(error)
            }.collect { event ->
                handleStreamEvent(event)
            }
        }
    }

    private fun handleStreamEvent(event: ChatRepository.StreamEvent) {
        when (event) {
            is ChatRepository.StreamEvent.UserSaved -> {
                upsertMessage(event.message)
            }
            is ChatRepository.StreamEvent.Started -> {
                upsertMessage(event.message)
                _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = true)
            }
            is ChatRepository.StreamEvent.Token -> {
                updateMessageContent(event.messageId, event.fullContent)
            }
            is ChatRepository.StreamEvent.ImageReady -> {
                upsertMessage(event.message)
                _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = false)
            }
            is ChatRepository.StreamEvent.VideoQueued -> {
                // The composer is free again the moment the server has the job; the bubble
                // itself reports progress from here on.
                upsertMessage(event.message)
                _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = false)
            }
            is ChatRepository.StreamEvent.Done -> {
                _uiState.value = _uiState.value.copy(isStreaming = false)
            }
        }
    }

    private suspend fun handleSendFailure(error: Throwable) {
        // Classify BEFORE building the message: both read the error body, and the classification
        // is the one that must not come up empty.
        val recoverable = findRecoverableModel(error)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isStreaming = false,
            error = ErrorMapper.mapToUserMessage(getApplication(), error),
            recoverableModel = recoverable
        )
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, recoverableModel = null)
    }

    companion object {
        private const val MAX_FILE_TEXT_BYTES = 50 * 1024
    }
}
