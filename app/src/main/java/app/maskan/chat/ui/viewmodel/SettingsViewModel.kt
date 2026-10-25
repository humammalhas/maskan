package app.maskan.chat.ui.viewmodel

import android.app.Application
import app.maskan.chat.data.remote.TranscribeBackends
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.AiProvider
import app.maskan.chat.data.remote.providers.ProviderConfigs
import app.maskan.chat.data.remote.providers.ProviderOrder
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.LocaleRepository
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.util.TokenEstimate
import app.maskan.chat.util.ErrorMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

sealed class TestConnectionState {
    data object Idle : TestConnectionState()
    data object Testing : TestConnectionState()
    data class Success(val message: String) : TestConnectionState()
    data class Error(val message: String) : TestConnectionState()
}

sealed class FetchModelsState {
    data object Idle : FetchModelsState()
    data object Loading : FetchModelsState()
    data class Success(val count: Int) : FetchModelsState()
    data class Error(val message: String) : FetchModelsState()
}

/**
 * Result of the silent check run when a model is picked. The user should never have to discover
 * a dead model by chatting with it: choosing one costs a single tiny request, and a model the
 * provider refuses is dropped from the list on the spot.
 */
/** The "what can my key do?" report: a handful of plain-language lines, or why it failed. */
sealed class KeyReportState {
    data object Idle : KeyReportState()
    data object Running : KeyReportState()
    data class Ready(val lines: List<String>) : KeyReportState()
    data class Error(val message: String) : KeyReportState()
}

sealed class ModelCheckState {
    data object Idle : ModelCheckState()
    data object Checking : ModelCheckState()
    data class Rejected(val model: String, val message: String) : ModelCheckState()
}

data class SettingsUiState(
    val selectedProvider: AiProvider = ProviderRegistry.getDefaultProvider(),
    val apiKey: String = "",
    val baseUrl: String = "",
    /** The base URL requests go to now - saved, not what is being typed. Drives the badge. */
    val savedBaseUrl: String = "",
    val selectedModel: String = "",
    val isSaved: Boolean = false,
    /** Just removed with Remove key - shown once, cleared by the next edit or provider. */
    val keyRemoved: Boolean = false,
    /** The provider list as the user arranged it: order, and what they hid (ProviderOrder). */
    val providerArrangement: ProviderOrder.Arranged = ProviderOrder.Arranged(emptyList(), emptyList()),
    val testState: TestConnectionState = TestConnectionState.Idle,
    val selectedLocale: String = "",
    val selectedDialect: Dialect = Dialect.MSA,
    val configuredProviderIds: Set<String> = emptySet(),
    val blockScreenshots: Boolean = false,
    /** The one memory file that is not tied to a folder. Off until the user turns it on. */
    val globalMemoryEnabled: Boolean = false,
    val autoTitleEnabled: Boolean = true,
    val showThinkingEnabled: Boolean = true,
    val speakButtonShown: Boolean = true,
    /** The provider that writes out audio now (plan item 3), or null when no key can. */
    val transcribeBackendId: String? = null,
    val globalMemoryTokens: Int = 0,
    val fetchedModels: List<String> = emptyList(),
    val fetchModelsState: FetchModelsState = FetchModelsState.Idle,
    val modelsFetchedAt: Long = 0L,
    val unavailableModels: Set<String> = emptySet(),
    val modelCheckState: ModelCheckState = ModelCheckState.Idle,
    /** Models this provider can DRAW with, and the one chosen. Separate from the chat model. */
    val imageModels: List<String> = emptyList(),
    val selectedImageModel: String = "",
    val videoModels: List<String> = emptyList(),
    val selectedVideoModel: String = "",
    val keyReportState: KeyReportState = KeyReportState.Idle
)

class SettingsViewModel(
    application: Application,
    private val keyRepository: KeyRepository,
    private val localeRepository: LocaleRepository,
    private val preferenceRepository: PreferenceRepository,
    private val chatRepository: ChatRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val allProviders: List<AiProvider> = ProviderRegistry.getAllProviders()

    init {
        val storedId = keyRepository.getDefaultProviderId()
        val provider = storedId?.let { ProviderRegistry.getProvider(it) }
            ?: ProviderRegistry.getDefaultProvider()
        val model = keyRepository.getSelectedModel(provider.id) ?: provider.defaultModel

        _uiState.value = SettingsUiState(
            selectedProvider = provider,
            apiKey = keyRepository.getApiKey(provider.id) ?: "",
            baseUrl = keyRepository.getBaseUrl(provider.id) ?: provider.defaultBaseUrl,
            savedBaseUrl = keyRepository.getBaseUrl(provider.id) ?: provider.defaultBaseUrl,
            selectedModel = model,
            selectedLocale = localeRepository.getLocale(),
            selectedDialect = preferenceRepository.getDefaultDialect(),
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet(),
            providerArrangement = arrangement(),
            blockScreenshots = preferenceRepository.isBlockScreenshots(),
            globalMemoryEnabled = preferenceRepository.isGlobalMemoryEnabled(),
            globalMemoryTokens = TokenEstimate.of(preferenceRepository.getGlobalMemory()),
            autoTitleEnabled = preferenceRepository.isAutoTitleEnabled(),
            showThinkingEnabled = preferenceRepository.isShowThinkingEnabled(),
            speakButtonShown = preferenceRepository.isSpeakButtonShown(),
            transcribeBackendId = chatRepository.transcribeBackend()?.providerId,
            fetchedModels = preferenceRepository.getCachedModels(provider.id),
            modelsFetchedAt = preferenceRepository.getModelsFetchedAt(provider.id),
            unavailableModels = preferenceRepository.getUnavailableModels(provider.id),
            imageModels = preferenceRepository.getImageModels(provider.id),
            selectedImageModel = keyRepository.getSelectedImageModel(provider.id) ?: "",
            videoModels = preferenceRepository.getVideoModels(provider.id),
            selectedVideoModel = keyRepository.getSelectedVideoModel(provider.id) ?: ""
        )

        maybeAutoRefreshModels()
    }

    private fun arrangement(): ProviderOrder.Arranged = ProviderOrder.arrange(
        allProviders.map { it.id },
        preferenceRepository.getProviderOrder(),
        preferenceRepository.getHiddenProviders()
    )

    /** Move [providerId] one place up (-1) or down (+1) among the shown providers. */
    fun moveProvider(providerId: String, delta: Int) {
        preferenceRepository.setProviderOrder(
            ProviderOrder.move(_uiState.value.providerArrangement, providerId, delta)
        )
        _uiState.value = _uiState.value.copy(providerArrangement = arrangement())
    }

    /**
     * Hide or show [providerId]. The provider in use cannot be hidden: new chats open on it, and
     * a list that hid where they open would be the list lying.
     */
    fun setProviderHidden(providerId: String, hidden: Boolean) {
        if (hidden && providerId == _uiState.value.selectedProvider.id) return
        val current = preferenceRepository.getHiddenProviders()
        preferenceRepository.setHiddenProviders(if (hidden) current + providerId else current - providerId)
        _uiState.value = _uiState.value.copy(providerArrangement = arrangement())
    }

    fun selectProvider(provider: AiProvider) {
        val model = keyRepository.getSelectedModel(provider.id) ?: provider.defaultModel
        _uiState.value = _uiState.value.copy(
            selectedProvider = provider,
            apiKey = keyRepository.getApiKey(provider.id) ?: "",
            baseUrl = keyRepository.getBaseUrl(provider.id) ?: provider.defaultBaseUrl,
            savedBaseUrl = keyRepository.getBaseUrl(provider.id) ?: provider.defaultBaseUrl,
            selectedModel = model,
            isSaved = false,
            keyRemoved = false,
            testState = TestConnectionState.Idle,
            fetchedModels = preferenceRepository.getCachedModels(provider.id),
            modelsFetchedAt = preferenceRepository.getModelsFetchedAt(provider.id),
            unavailableModels = preferenceRepository.getUnavailableModels(provider.id),
            modelCheckState = ModelCheckState.Idle,
            fetchModelsState = FetchModelsState.Idle,
            imageModels = preferenceRepository.getImageModels(provider.id),
            selectedImageModel = keyRepository.getSelectedImageModel(provider.id) ?: "",
            videoModels = preferenceRepository.getVideoModels(provider.id),
            selectedVideoModel = keyRepository.getSelectedVideoModel(provider.id) ?: "",
            keyReportState = KeyReportState.Idle
        )
        keyRepository.setDefaultProviderId(provider.id)
        maybeAutoRefreshModels()
    }

    fun fetchModels(auto: Boolean = false) {
        val providerId = _uiState.value.selectedProvider.id
        // A manual refresh is the user saying "try again from scratch": forget what was rejected
        // before, since plans change and providers add access.
        if (!auto) {
            preferenceRepository.clearUnavailableModels(providerId)
            _uiState.value = _uiState.value.copy(unavailableModels = emptySet())
        }
        // Persist the current URL first so the fetch hits the server the user just typed.
        // Cloud providers have a fixed endpoint, so never overwrite it from UI state.
        if (_uiState.value.selectedProvider.supportsCustomBaseUrl) {
            keyRepository.saveBaseUrl(providerId, _uiState.value.baseUrl)
        }
        _uiState.value = _uiState.value.copy(fetchModelsState = FetchModelsState.Loading)
        viewModelScope.launch {
            val result = chatRepository.fetchModels(providerId)
            val context = getApplication<Application>()
            _uiState.value = result.fold(
                onSuccess = { fetched ->
                    val models = fetched.ids
                    if (models.isEmpty()) {
                        _uiState.value.copy(
                            fetchModelsState = if (auto) FetchModelsState.Idle else FetchModelsState.Error(
                                context.getString(app.maskan.chat.R.string.models_load_empty)
                            )
                        )
                    } else {
                        // Auto-select the fetched model if the current selection isn't among them.
                        val newSelected = if (_uiState.value.selectedModel in models) {
                            _uiState.value.selectedModel
                        } else {
                            models.first().also { keyRepository.saveSelectedModel(providerId, it) }
                        }
                        // Cache it so the next Settings visit (or an offline one) still has a
                        // current list without hitting the network.
                        preferenceRepository.saveCachedModels(
                            providerId, models, fetched.visionIds, fetched.freeIds, fetched.imageIds, fetched.videoIds,
                            fetched.transcribeIds
                        )
                        _uiState.value.copy(
                            fetchedModels = models,
                            selectedModel = newSelected,
                            modelsFetchedAt = preferenceRepository.getModelsFetchedAt(providerId),
                            imageModels = fetched.imageIds,
                            videoModels = fetched.videoIds,
                            fetchModelsState = FetchModelsState.Success(models.size)
                        )
                    }
                },
                onFailure = {
                    // An automatic refresh must never shout at the user: fall back silently to
                    // the cached list (or the bundled one). Only a manual tap surfaces errors.
                    _uiState.value.copy(
                        fetchModelsState = if (auto) FetchModelsState.Idle else FetchModelsState.Error(
                            ErrorMapper.mapToUserMessage(context, it)
                        )
                    )
                }
            )
        }
    }

    /**
     * Refresh the model list in the background when it is missing or older than the TTL, so a
     * provider retiring a model id cannot leave the user stuck on a dead one. Silent on failure:
     * the cached list, then the bundled fallback, still work.
     */
    private fun maybeAutoRefreshModels() {
        val state = _uiState.value
        val provider = state.selectedProvider
        val hasCredentials = if (provider.supportsCustomBaseUrl) {
            state.baseUrl.isNotBlank()
        } else {
            state.apiKey.isNotBlank()
        }
        if (!hasCredentials) return
        if (state.fetchModelsState is FetchModelsState.Loading) return
        val age = System.currentTimeMillis() - state.modelsFetchedAt
        if (state.fetchedModels.isEmpty() || age !in 0..MODEL_CACHE_TTL_MS) {
            fetchModels(auto = true)
        }
    }

    fun updateApiKey(key: String) {
        _uiState.value = _uiState.value.copy(
            apiKey = key,
            isSaved = false,
            keyRemoved = false,
            testState = TestConnectionState.Idle
        )
    }

    fun updateBaseUrl(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url)
    }

    fun saveApiKey() {
        val state = _uiState.value
        keyRepository.saveApiKey(state.selectedProvider.id, state.apiKey)
        _uiState.value = state.copy(
            isSaved = true,
            keyRemoved = false,
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet()
        )
        // A newly saved key is the first chance to ask this provider what it actually serves.
        maybeAutoRefreshModels()
    }

    /**
     * Delete this provider's key from the phone (2.7, plan item 10). Before this the only way was
     * to clear the field - and Save is disabled on an empty field, so it could not be done at
     * all without being told a trick. The model lists and choices stay: they are not secrets,
     * and a key pasted back in later finds the provider as it was.
     */
    fun removeApiKey() {
        val state = _uiState.value
        keyRepository.deleteApiKey(state.selectedProvider.id)
        _uiState.value = state.copy(
            apiKey = "",
            isSaved = false,
            keyRemoved = true,
            testState = TestConnectionState.Idle,
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet()
        )
    }

    fun saveBaseUrl() {
        val state = _uiState.value
        keyRepository.saveBaseUrl(state.selectedProvider.id, state.baseUrl)
        _uiState.value = state.copy(isSaved = true, savedBaseUrl = state.baseUrl)
    }

    fun selectModel(model: String) {
        val state = _uiState.value
        val previousModel = state.selectedModel
        val providerId = state.selectedProvider.id
        keyRepository.saveSelectedModel(providerId, model)
        _uiState.value = state.copy(
            selectedModel = model,
            modelCheckState = ModelCheckState.Checking,
            testState = TestConnectionState.Idle
        )

        // Verify the pick immediately. A catalogue lists what the provider HOSTS; only a real
        // request proves this key may call it. One tiny call here saves the user from a dead
        // conversation later.
        viewModelScope.launch {
            val result = chatRepository.testConnection(providerId)
            val context = getApplication<Application>()
            result.fold(
                onSuccess = {
                    preferenceRepository.addVerifiedModel(providerId, model)
                    _uiState.value = _uiState.value.copy(modelCheckState = ModelCheckState.Idle)
                },
                onFailure = { throwable ->
                    val code = (throwable as? HttpException)?.code()
                    if (code == 403 || code == 404 || code == 400) {
                        // The provider refused this model: remember it (with the provider's own
                        // reason), drop it from the picker and put the working model back.
                        preferenceRepository.addUnavailableModel(
                            providerId, model, ErrorMapper.mapToUserMessage(context, throwable)
                        )
                        val restored = previousModel.takeIf { it.isNotBlank() && it != model }
                            ?: state.selectedProvider.defaultModel
                        keyRepository.saveSelectedModel(providerId, restored)
                        _uiState.value = _uiState.value.copy(
                            selectedModel = restored,
                            unavailableModels = preferenceRepository.getUnavailableModels(providerId),
                            modelCheckState = ModelCheckState.Rejected(
                                model = model,
                                message = ErrorMapper.mapToUserMessage(context, throwable)
                            )
                        )
                    } else {
                        // Network trouble or a provider hiccup says nothing about the model.
                        _uiState.value = _uiState.value.copy(modelCheckState = ModelCheckState.Idle)
                    }
                }
            )
        }
    }

    fun testConnection() {
        // Test what is in the field. Requiring a separate Save tap first was pure friction, and
        // saving an unchanged key costs nothing.
        val current = _uiState.value
        keyRepository.saveApiKey(current.selectedProvider.id, current.apiKey)
        _uiState.value = current.copy(
            testState = TestConnectionState.Testing,
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet()
        )
        viewModelScope.launch {
            val providerId = _uiState.value.selectedProvider.id
            val model = _uiState.value.selectedModel
            val result = chatRepository.testConnection(providerId)
            val context = getApplication<Application>()

            // Compute both updates BEFORE touching _uiState: writing to it inside the fold would
            // be clobbered, because the copy() receiver is read before its arguments run.
            var rejected: Set<String>? = null
            val newTestState = result.fold(
                onSuccess = {
                    TestConnectionState.Success(
                        context.getString(app.maskan.chat.R.string.test_connection_success)
                    )
                },
                onFailure = { throwable ->
                    // 403/404 means the provider refused THIS model (not on your plan, or no such
                    // id) rather than the key - remember it so the picker stops offering a model
                    // that cannot work. Cleared by a manual refresh.
                    val code = (throwable as? HttpException)?.code()
                    if ((code == 403 || code == 404) && model.isNotBlank()) {
                        preferenceRepository.addUnavailableModel(
                            providerId, model, ErrorMapper.mapToUserMessage(context, throwable)
                        )
                        rejected = preferenceRepository.getUnavailableModels(providerId)
                    }
                    TestConnectionState.Error(
                        ErrorMapper.mapToUserMessage(context, throwable)
                    )
                }
            )

            _uiState.value = _uiState.value.copy(
                testState = newTestState,
                unavailableModels = rejected ?: _uiState.value.unavailableModels
            )
        }
    }

    /**
     * "What can my key do?" - Test Connection grown up. Answers in whole sentences: whether
     * chat works (and the provider's own reason when it does not), how many models the key
     * sees, which are free where that is actually published, whether this provider draws, and
     * the live balance where one exists. Built only on requests just made, never on guesses.
     */
    fun runKeyReport() {
        val current = _uiState.value
        // Test what is in the field, exactly like testConnection.
        keyRepository.saveApiKey(current.selectedProvider.id, current.apiKey)
        if (current.selectedProvider.supportsCustomBaseUrl) {
            keyRepository.saveBaseUrl(current.selectedProvider.id, current.baseUrl)
        }
        _uiState.value = current.copy(
            keyReportState = KeyReportState.Running,
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet()
        )
        viewModelScope.launch {
            val context = getApplication<Application>()
            val provider = _uiState.value.selectedProvider
            val providerName = if (localeRepository.getLocale() == "ar") provider.nameAr else provider.displayName
            val result = chatRepository.keyCapabilityReport(provider.id)
            _uiState.value = _uiState.value.copy(
                keyReportState = result.fold(
                    onSuccess = { report ->
                        val lines = buildList {
                            if (report.chatFailure == null) {
                                add(context.getString(app.maskan.chat.R.string.key_report_chat_ok_fmt, report.modelCount))
                            } else {
                                add(context.getString(
                                    app.maskan.chat.R.string.key_report_chat_fail_fmt,
                                    ErrorMapper.mapToUserMessage(context, report.chatFailure)
                                ))
                            }
                            if (report.isLocal) {
                                add(context.getString(app.maskan.chat.R.string.key_report_local_free))
                            } else if (report.freeCount > 0) {
                                add(context.getString(app.maskan.chat.R.string.key_report_free_fmt, report.freeCount))
                            } else {
                                add(context.getString(app.maskan.chat.R.string.key_report_free_unknown))
                            }
                            if (!report.imageSupported) {
                                add(context.getString(app.maskan.chat.R.string.key_report_images_unsupported_fmt, providerName))
                            } else if (report.imageModelCount > 0) {
                                add(context.getString(app.maskan.chat.R.string.key_report_images_ok_fmt, report.imageModelCount))
                            } else {
                                add(context.getString(app.maskan.chat.R.string.image_models_none_found))
                            }
                            report.balance?.let {
                                add(context.getString(app.maskan.chat.R.string.key_report_balance_fmt, it))
                            }
                        }
                        KeyReportState.Ready(lines)
                    },
                    onFailure = {
                        KeyReportState.Error(context.getString(
                            app.maskan.chat.R.string.key_report_error_fmt,
                            ErrorMapper.mapToUserMessage(context, it)
                        ))
                    }
                )
            )
        }
    }

    fun selectLanguage(code: String) {
        localeRepository.saveLocale(code)
        val app = getApplication<MaskanApplication>()
        app.applyLocale(code)
        _uiState.value = _uiState.value.copy(selectedLocale = code)
    }

    fun selectDialect(dialect: Dialect) {
        preferenceRepository.setDefaultDialect(dialect)
        _uiState.value = _uiState.value.copy(selectedDialect = dialect)
    }

    fun toggleBlockScreenshots(): Boolean {
        val newValue = !_uiState.value.blockScreenshots
        preferenceRepository.setBlockScreenshots(newValue)
        _uiState.value = _uiState.value.copy(blockScreenshots = newValue)
        return newValue
    }

    fun toggleAutoTitle(): Boolean {
        val newValue = !_uiState.value.autoTitleEnabled
        preferenceRepository.setAutoTitleEnabled(newValue)
        _uiState.value = _uiState.value.copy(autoTitleEnabled = newValue)
        return newValue
    }

    fun toggleShowThinking() {
        val newValue = !_uiState.value.showThinkingEnabled
        preferenceRepository.setShowThinkingEnabled(newValue)
        _uiState.value = _uiState.value.copy(showThinkingEnabled = newValue)
    }

    /**
     * Every transcription backend in the user's provider order, and whether a key is saved for it.
     * A plain call, re-read whenever the state changes - which a saved or removed key does.
     */
    fun transcribeChoices(): List<Pair<TranscribeBackends.Backend, Boolean>> = chatRepository.transcribeChoices()

    /** The backend shown as chosen: the saved choice while it has a key, else the default. */
    fun currentTranscribeBackend(): TranscribeBackends.Backend? = chatRepository.transcribeBackend()

    /**
     * The backend [providerId] uses for its own chats, when it has one it can use: a cloud key, or
     * a local server whose /health advertised transcription (after Refresh model list).
     */
    fun ownTranscribeBackend(providerId: String): TranscribeBackends.Backend? =
        chatRepository.transcribeBackend(providerId)?.takeIf { it.providerId == providerId }

    fun selectTranscribeBackend(providerId: String) {
        preferenceRepository.setTranscribeBackend(providerId)
        _uiState.value = _uiState.value.copy(transcribeBackendId = chatRepository.transcribeBackend()?.providerId)
    }

    fun toggleSpeakButton(): Boolean {
        val newValue = !_uiState.value.speakButtonShown
        preferenceRepository.setSpeakButtonShown(newValue)
        _uiState.value = _uiState.value.copy(speakButtonShown = newValue)
        return newValue
    }

    fun toggleGlobalMemory(): Boolean {
        val newValue = !_uiState.value.globalMemoryEnabled
        preferenceRepository.setGlobalMemoryEnabled(newValue)
        _uiState.value = _uiState.value.copy(globalMemoryEnabled = newValue)
        return newValue
    }

    /**
     * Re-read the shared memory count. The editor is a separate destination writing to the
     * preferences, which have no flow, so coming back from it is the moment to look again.
     */
    fun refreshGlobalMemory() {
        _uiState.value = _uiState.value.copy(
            globalMemoryEnabled = preferenceRepository.isGlobalMemoryEnabled(),
            globalMemoryTokens = TokenEstimate.of(preferenceRepository.getGlobalMemory())
        )
    }

    /** Models the provider prices at zero - they work even with an empty account balance. */
    /**
     * Choose the model this provider draws with.
     *
     * Deliberately NOT verified the way a chat model is: verification spends one real request,
     * and for an image model that is a paid picture on every tap. The user finds out it works by
     * drawing something they actually wanted.
     */
    fun selectImageModel(model: String) {
        val providerId = _uiState.value.selectedProvider.id
        keyRepository.saveSelectedImageModel(providerId, model)
        _uiState.value = _uiState.value.copy(selectedImageModel = model)
    }

    /**
     * Put the provider back to chat-only. Clearing the image model is what hides the draw button
     * in the composer, so this is the way out for someone who just wants to talk.
     */
    fun clearImageModel() {
        val providerId = _uiState.value.selectedProvider.id
        keyRepository.saveSelectedImageModel(providerId, "")
        _uiState.value = _uiState.value.copy(selectedImageModel = "")
    }

    /** Same contract as [selectImageModel]: not verified, because a check would render a clip. */
    fun selectVideoModel(model: String) {
        val providerId = _uiState.value.selectedProvider.id
        keyRepository.saveSelectedVideoModel(providerId, model)
        _uiState.value = _uiState.value.copy(selectedVideoModel = model)
    }

    fun clearVideoModel() {
        val providerId = _uiState.value.selectedProvider.id
        keyRepository.saveSelectedVideoModel(providerId, "")
        _uiState.value = _uiState.value.copy(selectedVideoModel = "")
    }

    /** Rejected models and the provider's words for why - drives the greyed picker section. */
    fun unavailableReasons(): Map<String, String> =
        preferenceRepository.getUnavailableReasons(_uiState.value.selectedProvider.id)

    fun freeModels(): Set<String> =
        preferenceRepository.getFreeModels(_uiState.value.selectedProvider.id)

    /** Models already proven to answer with this key - drives the "tested" tag in the picker. */
    fun verifiedModels(): Set<String> =
        preferenceRepository.getVerifiedModels(_uiState.value.selectedProvider.id)

    /** Models this provider says accept image input - drives the camera badge in the picker. */
    fun visionModels(): Set<String> =
        preferenceRepository.getVisionModels(_uiState.value.selectedProvider.id)

    fun getProviderConfig() = ProviderConfigs.ALL.firstOrNull { it.id == _uiState.value.selectedProvider.id }

    companion object {
        // Model catalogues move on the order of weeks, so a 7-day TTL keeps the list current
        // without a network call every time Settings opens.
        private const val MODEL_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
