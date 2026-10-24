package app.maskan.chat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationListUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    /**
     * Whether the database has been read at least once.
     *
     * Without it, "not read yet" and "empty" are the same state and the screen tells a user
     * with two years of conversations that they have none. Nothing is drawn until this is true.
     */
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val selectedConversationId: Long? = null,
    /** First line of the first message in each chat, for rows that share a title. */
    val firstLines: Map<Long, String> = emptyMap()
)

class ConversationListViewModel(
    private val chatRepository: ChatRepository,
    private val keyRepository: KeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val searchResults: StateFlow<List<ConversationEntity>> = _searchResults.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    init {
        sweepEmptyConversations()
        loadData()
        observeSearch()
    }

    /**
     * Throw away the "New Chat" rows nobody ever used, once per process.
     *
     * Going forward the chat screen discards an empty chat as the user leaves it, so this is
     * for what is already in the database - an install upgrading from 2.5 can be carrying a
     * dozen of them. Deliberately the narrow rule: no messages at all, no document, and still
     * the default title. See the DAO query.
     */
    private fun sweepEmptyConversations() {
        viewModelScope.launch {
            chatRepository.sweepEmptyConversations()
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        _searchResults.value = emptyList()
                    } else {
                        val results = chatRepository.searchConversations(query)
                        _searchResults.value = results
                    }
                }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _isSearchActive.value = false
    }

    fun activateSearch() {
        _isSearchActive.value = true
    }

    /**
     * The list, from Room's flows and nothing else.
     *
     * Room's invalidation does reach these flows under SQLCipher: measured in 2.7 session 9,
     * every write the app makes re-emitted within ~15 ms. What used to put a deleted chat back on
     * screen was a second writer - a one-shot re-read that started before the delete and landed
     * after the flow's correct list. So this is the ONLY writer of the list, and it does not
     * suspend between reading a value and storing it. Nothing is drawn until all three flows have
     * emitted once: see hasLoaded.
     */
    private fun loadData() {
        viewModelScope.launch {
            combine(
                chatRepository.getAllConversations(),
                chatRepository.getAllFolders(),
                chatRepository.getFirstUserLines()
            ) { conversations, folders, firstLines ->
                Triple(conversations, folders, firstLines)
            }.collect { (conversations, folders, firstLines) ->
                _uiState.update {
                    it.copy(
                        conversations = conversations,
                        folders = folders,
                        firstLines = firstLines,
                        isLoading = false,
                        hasLoaded = true
                    )
                }
            }
        }
    }

    /** [folderId]: born in that folder, so its instructions apply from the first message. */
    fun createNewConversation(folderId: Long? = null, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            // Only reached before the user has ever picked a provider; see
            // ProviderRegistry.getDefaultProvider for why this one.
            val defaultProviderId = keyRepository.getDefaultProviderId()
                ?: ProviderRegistry.getDefaultProvider().id
            // Use the model the user actually selected/typed for this provider; fall back to the
            // provider's default only if none was saved. (Previously this always used the provider
            // default — e.g. "llama3.2" for Ollama — so new chats ignored the user's chosen model
            // and failed with "model not found" on servers that don't have that exact model.)
            val defaultModel = keyRepository.getSelectedModel(defaultProviderId)
                ?: ProviderRegistry.getProvider(defaultProviderId)?.defaultModel
            val id = chatRepository.createConversation(
                providerId = defaultProviderId,
                modelId = defaultModel,
                folderId = folderId
            )
            onCreated(id)
        }
    }

    fun renameConversation(id: Long, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            chatRepository.updateConversationTitle(id, trimmed)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
        }
    }

    /** [onCreated] gets the new folder's id, for "Add instructions" to open its files. */
    fun createFolder(name: String, colorHex: String?, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val id = chatRepository.createFolder(name, colorHex)
            onCreated?.invoke(id)
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
