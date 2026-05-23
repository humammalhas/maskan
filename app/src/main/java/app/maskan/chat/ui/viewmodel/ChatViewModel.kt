package app.maskan.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.SystemPromptPreset
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.util.ErrorMapper
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
    val presetSelected: Boolean = false
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

    fun loadConversation(conversationId: Long) {
        // Always cancel previous collection and reset state to avoid stale data
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

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isStreaming = false, error = null)

            chatRepository.sendMessageStreaming(
                conversationId = currentConversationId,
                userContent = content,
                model = _uiState.value.selectedModel
            ).catch { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isStreaming = false,
                    error = ErrorMapper.mapToUserMessage(getApplication(), error)
                )
            }.collect { event ->
                when (event) {
                    is ChatRepository.StreamEvent.Started -> {
                        _uiState.value = _uiState.value.copy(isLoading = false, isStreaming = true)
                    }
                    is ChatRepository.StreamEvent.Token -> {
                        // Room Flow auto-updates messages via getMessagesForConversation
                    }
                    is ChatRepository.StreamEvent.Done -> {
                        _uiState.value = _uiState.value.copy(isStreaming = false)
                    }
                }
            }
        }
    }

    fun setSelectedModel(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
