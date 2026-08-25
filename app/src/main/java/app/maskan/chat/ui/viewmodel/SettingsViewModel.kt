package app.maskan.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.AiProvider
import app.maskan.chat.data.remote.providers.ProviderConfigs
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.LocaleRepository
import app.maskan.chat.data.repository.PreferenceRepository
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
sealed class ModelCheckState {
    data object Idle : ModelCheckState()
    data object Checking : ModelCheckState()
    data class Rejected(val model: String, val message: String) : ModelCheckState()
}

data class SettingsUiState(
    val selectedProvider: AiProvider = ProviderRegistry.getDefaultProvider(),
    val apiKey: String = "",
    val baseUrl: String = "",
    val selectedModel: String = "",
    val isSaved: Boolean = false,
    val testState: TestConnectionState = TestConnectionState.Idle,
    val selectedLocale: String = "",
    val selectedDialect: Dialect = Dialect.MSA,
    val configuredProviderIds: Set<String> = emptySet(),
    val blockScreenshots: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val fetchModelsState: FetchModelsState = FetchModelsState.Idle,
    val modelsFetchedAt: Long = 0L,
    val unavailableModels: Set<String> = emptySet(),
    val modelCheckState: ModelCheckState = ModelCheckState.Idle,
    /** Models this provider can DRAW with, and the one chosen. Separate from the chat model. */
    val imageModels: List<String> = emptyList(),
    val selectedImageModel: String = ""
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
            selectedModel = model,
            selectedLocale = localeRepository.getLocale(),
            selectedDialect = preferenceRepository.getDefaultDialect(),
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet(),
            blockScreenshots = preferenceRepository.isBlockScreenshots(),
            fetchedModels = preferenceRepository.getCachedModels(provider.id),
            modelsFetchedAt = preferenceRepository.getModelsFetchedAt(provider.id),
            unavailableModels = preferenceRepository.getUnavailableModels(provider.id),
            imageModels = preferenceRepository.getImageModels(provider.id),
            selectedImageModel = keyRepository.getSelectedImageModel(provider.id) ?: ""
        )

        maybeAutoRefreshModels()
    }

    fun selectProvider(provider: AiProvider) {
        val model = keyRepository.getSelectedModel(provider.id) ?: provider.defaultModel
        _uiState.value = _uiState.value.copy(
            selectedProvider = provider,
            apiKey = keyRepository.getApiKey(provider.id) ?: "",
            baseUrl = keyRepository.getBaseUrl(provider.id) ?: provider.defaultBaseUrl,
            selectedModel = model,
            isSaved = false,
            testState = TestConnectionState.Idle,
            fetchedModels = preferenceRepository.getCachedModels(provider.id),
            modelsFetchedAt = preferenceRepository.getModelsFetchedAt(provider.id),
            unavailableModels = preferenceRepository.getUnavailableModels(provider.id),
            modelCheckState = ModelCheckState.Idle,
            fetchModelsState = FetchModelsState.Idle,
            imageModels = preferenceRepository.getImageModels(provider.id),
            selectedImageModel = keyRepository.getSelectedImageModel(provider.id) ?: ""
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
                        preferenceRepository.saveCachedModels(providerId, models, fetched.visionIds, fetched.freeIds, fetched.imageIds)
                        _uiState.value.copy(
                            fetchedModels = models,
                            selectedModel = newSelected,
                            modelsFetchedAt = preferenceRepository.getModelsFetchedAt(providerId),
                            imageModels = fetched.imageIds,
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
            configuredProviderIds = keyRepository.getAllStoredProviderIds().toSet()
        )
        // A newly saved key is the first chance to ask this provider what it actually serves.
        maybeAutoRefreshModels()
    }

    fun saveBaseUrl() {
        val state = _uiState.value
        keyRepository.saveBaseUrl(state.selectedProvider.id, state.baseUrl)
        _uiState.value = state.copy(isSaved = true)
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
                        // The provider refused this model: remember it, drop it from the picker
                        // and put the working model back.
                        preferenceRepository.addUnavailableModel(providerId, model)
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
                        preferenceRepository.addUnavailableModel(providerId, model)
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
