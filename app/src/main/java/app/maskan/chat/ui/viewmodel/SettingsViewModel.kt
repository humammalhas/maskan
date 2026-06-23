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

data class SettingsUiState(
    val selectedProvider: AiProvider = ProviderRegistry.getDefaultProvider(),
    val apiKey: String = "",
    val baseUrl: String = "",
    val selectedModel: String = "",
    val isSaved: Boolean = false,
    val testState: TestConnectionState = TestConnectionState.Idle,
    val selectedLocale: String = "",
    val selectedDialect: Dialect = Dialect.LEVANTINE,
    val configuredProviderIds: Set<String> = emptySet(),
    val blockScreenshots: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val fetchModelsState: FetchModelsState = FetchModelsState.Idle
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
            blockScreenshots = preferenceRepository.isBlockScreenshots()
        )
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
            fetchedModels = emptyList(),
            fetchModelsState = FetchModelsState.Idle
        )
        keyRepository.setDefaultProviderId(provider.id)
    }

    fun fetchModels() {
        val providerId = _uiState.value.selectedProvider.id
        // Persist the current URL first so the fetch hits the server the user just typed.
        keyRepository.saveBaseUrl(providerId, _uiState.value.baseUrl)
        _uiState.value = _uiState.value.copy(fetchModelsState = FetchModelsState.Loading)
        viewModelScope.launch {
            val result = chatRepository.fetchModels(providerId)
            val context = getApplication<Application>()
            _uiState.value = result.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) {
                        _uiState.value.copy(
                            fetchModelsState = FetchModelsState.Error(
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
                        _uiState.value.copy(
                            fetchedModels = models,
                            selectedModel = newSelected,
                            fetchModelsState = FetchModelsState.Success(models.size)
                        )
                    }
                },
                onFailure = {
                    _uiState.value.copy(
                        fetchModelsState = FetchModelsState.Error(
                            ErrorMapper.mapToUserMessage(context, it)
                        )
                    )
                }
            )
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
    }

    fun saveBaseUrl() {
        val state = _uiState.value
        keyRepository.saveBaseUrl(state.selectedProvider.id, state.baseUrl)
        _uiState.value = state.copy(isSaved = true)
    }

    fun selectModel(model: String) {
        val state = _uiState.value
        keyRepository.saveSelectedModel(state.selectedProvider.id, model)
        _uiState.value = state.copy(selectedModel = model)
    }

    fun testConnection() {
        _uiState.value = _uiState.value.copy(testState = TestConnectionState.Testing)
        viewModelScope.launch {
            val result = chatRepository.testConnection(_uiState.value.selectedProvider.id)
            val context = getApplication<Application>()
            _uiState.value = _uiState.value.copy(
                testState = result.fold(
                    onSuccess = {
                        TestConnectionState.Success(
                            context.getString(app.maskan.chat.R.string.test_connection_success)
                        )
                    },
                    onFailure = {
                        TestConnectionState.Error(
                            ErrorMapper.mapToUserMessage(context, it)
                        )
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

    fun getProviderConfig() = ProviderConfigs.ALL.firstOrNull { it.id == _uiState.value.selectedProvider.id }
}
