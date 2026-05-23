package app.maskan.chat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ConversationListUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val isLoading: Boolean = false,
    val selectedConversationId: Long? = null
)

class ConversationListViewModel(
    private val chatRepository: ChatRepository,
    private val keyRepository: KeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                chatRepository.getAllConversations(),
                chatRepository.getAllFolders()
            ) { conversations, folders ->
                _uiState.value.copy(
                    conversations = conversations,
                    folders = folders,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun createNewConversation(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val defaultProviderId = keyRepository.getDefaultProviderId() ?: "deepseek"
            val defaultModel = ProviderRegistry.getProvider(defaultProviderId)?.defaultModel
            val id = chatRepository.createConversation(
                providerId = defaultProviderId,
                modelId = defaultModel
            )
            onCreated(id)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
        }
    }

    fun createFolder(name: String, colorHex: String?) {
        viewModelScope.launch {
            chatRepository.createFolder(name, colorHex)
        }
    }

    fun renameFolder(id: Long, newName: String) {
        viewModelScope.launch {
            chatRepository.renameFolder(id, newName)
        }
    }

    fun updateFolderColor(id: Long, colorHex: String) {
        viewModelScope.launch {
            chatRepository.updateFolderColor(id, colorHex)
        }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            chatRepository.deleteFolder(id)
        }
    }

    fun moveConversationToFolder(conversationId: Long, folderId: Long?) {
        viewModelScope.launch {
            chatRepository.moveConversationToFolder(conversationId, folderId)
        }
    }

    fun hasApiKey(): Boolean {
        val defaultProviderId = keyRepository.getDefaultProviderId() ?: "deepseek"
        val provider = ProviderRegistry.getProvider(defaultProviderId)
        return if (provider?.supportsCustomBaseUrl == true) {
            keyRepository.getBaseUrl(defaultProviderId)?.isNotBlank() == true
        } else {
            keyRepository.hasApiKey(defaultProviderId)
        }
    }
}
