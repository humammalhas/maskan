package app.maskan.chat.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import app.maskan.chat.util.ErrorMapper
import app.maskan.chat.util.ImageUtils
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
    val pendingFileName: String? = null
)

class ChatViewModel(
    application: Application,
    private val chatRepository: ChatRepository,
    private val keyRepository: KeyRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentConversationId: Long = -1
    private var messageCollectionJob: kotlinx.coroutines.Job? = null
    private var streamingJob: Job? = null

    fun loadConversation(conversationId: Long) {
        messageCollectionJob?.cancel()
        currentConversationId = conversationId
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
                    val dialect = conversation.dialectId?.let { Dialect.fromId(it) } ?: Dialect.LEVANTINE
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
            val dialectId = if (preset.id == "en_to_ar") (dialect?.id ?: Dialect.LEVANTINE.id) else null
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

    fun currentProviderSupportsVision(): Boolean {
        val provider = ProviderRegistry.getProvider(_uiState.value.selectedProviderId)
        return provider?.supportsVision == true
    }

    fun sendMessage(content: String) {
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
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isStreaming = false,
                    error = ErrorMapper.mapToUserMessage(getApplication(), error)
                )
            }.collect { event ->
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
                    is ChatRepository.StreamEvent.Done -> {
                        _uiState.value = _uiState.value.copy(isStreaming = false)
                    }
                }
            }
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
        _uiState.value = _uiState.value.copy(error = null)
    }

    companion object {
        private const val MAX_FILE_TEXT_BYTES = 50 * 1024
    }
}
