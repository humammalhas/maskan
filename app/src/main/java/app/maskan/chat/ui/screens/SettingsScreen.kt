package app.maskan.chat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import androidx.compose.ui.platform.LocalContext
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.ProviderConfigs
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.LocaleRepository
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.util.ErrorMapper
import androidx.compose.foundation.layout.Arrangement
import app.maskan.chat.ui.theme.MintGreen
import app.maskan.chat.ui.theme.SkyBlue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import kotlinx.coroutines.launch

private sealed class TestConnectionState {
    data object Idle : TestConnectionState()
    data object Testing : TestConnectionState()
    data class Success(val message: String) : TestConnectionState()
    data class Error(val message: String) : TestConnectionState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyRepository: KeyRepository,
    localeRepository: LocaleRepository,
    preferenceRepository: PreferenceRepository,
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
    onLocaleChanged: () -> Unit = {},
    currentModel: String = "deepseek-chat",
    onModelChanged: (String) -> Unit = {},
    isFirstLaunch: Boolean = false
) {
    val isArabic = isAppArabic()
    val app = LocalContext.current.applicationContext as MaskanApplication

    val allProviders = remember { ProviderRegistry.getAllProviders() }
    var selectedProvider by remember {
        val storedId = keyRepository.getDefaultProviderId()
        val storedProvider = storedId?.let { ProviderRegistry.getProvider(it) }
        mutableStateOf(storedProvider ?: ProviderRegistry.getDefaultProvider())
    }

    var apiKey by remember { mutableStateOf(keyRepository.getApiKey(selectedProvider.id) ?: "") }
    var baseUrl by remember { mutableStateOf(keyRepository.getBaseUrl(selectedProvider.id) ?: selectedProvider.defaultBaseUrl) }
    var isKeyVisible by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var selectedModel by remember {
        val model = keyRepository.getSelectedModel(selectedProvider.id) ?: selectedProvider.defaultModel
        mutableStateOf(model)
    }
    var languageExpanded by remember { mutableStateOf(false) }
    var dialectExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var testState by remember { mutableStateOf<TestConnectionState>(TestConnectionState.Idle) }
    val context = LocalContext.current

    val providerConfig = remember(selectedProvider.id) {
        ProviderConfigs.ALL.firstOrNull { it.id == selectedProvider.id }
    }

    val savedLocale = remember { localeRepository.getLocale() }
    val selectedLanguage = remember { mutableStateOf(savedLocale) }
    var selectedDialect by remember { mutableStateOf(preferenceRepository.getDefaultDialect()) }

    data class LanguageOption(val code: String, val label: String)
    val languageOptions = listOf(
        LanguageOption("", stringResource(R.string.language_system_default)),
        LanguageOption("en", stringResource(R.string.language_english)),
        LanguageOption("ar", stringResource(R.string.language_arabic))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MintGreen
                ),
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(
                            if (isFirstLaunch) stringResource(R.string.start_chatting)
                            else stringResource(R.string.back_button)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (isFirstLaunch) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SkyBlue.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setup_steps_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.setup_step_1),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.setup_step_2),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Provider Section
            Text(
                text = stringResource(R.string.provider_section),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                OutlinedTextField(
                    value = if (isArabic) selectedProvider.nameAr else selectedProvider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.provider_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    allProviders.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(if (isArabic) provider.nameAr else provider.displayName) },
                            onClick = {
                                selectedProvider = provider
                                providerExpanded = false
                                apiKey = keyRepository.getApiKey(provider.id) ?: ""
                                baseUrl = keyRepository.getBaseUrl(provider.id) ?: provider.defaultBaseUrl
                                isSaved = false
                                isKeyVisible = false
                                testState = TestConnectionState.Idle
                                selectedModel = keyRepository.getSelectedModel(provider.id) ?: provider.defaultModel
                                onModelChanged(selectedModel)
                                keyRepository.setDefaultProviderId(provider.id)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isArabic)
                    providerConfig?.pricingInfoAr ?: selectedProvider.pricingInfo
                else
                    selectedProvider.pricingInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // API Key Section
            Text(
                text = stringResource(R.string.api_key_section),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (apiKey.isBlank() && !selectedProvider.supportsCustomBaseUrl) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SkyBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.api_key_hint_banner_provider,
                            if (isArabic) selectedProvider.nameAr else selectedProvider.displayName,
                            selectedProvider.keyAcquisitionUrl
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    isSaved = false
                    testState = TestConnectionState.Idle
                },
                label = { Text("${selectedProvider.displayName} API Key") },
                placeholder = { Text(stringResource(R.string.api_key_placeholder)) },
                visualTransformation = if (isKeyVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MintGreen,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    TextButton(onClick = { isKeyVisible = !isKeyVisible }) {
                        Text(if (isKeyVisible) stringResource(R.string.hide_key) else stringResource(R.string.show_key))
                    }
                }
            )
            if (selectedProvider.supportsCustomBaseUrl) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.api_key_optional_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_encrypted_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    keyRepository.saveApiKey(selectedProvider.id, apiKey)
                    isSaved = true
                },
                enabled = apiKey.isNotBlank() || selectedProvider.supportsCustomBaseUrl
            ) {
                Text(stringResource(R.string.save_key_button))
            }
            if (isSaved) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.key_saved_confirmation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    testState = TestConnectionState.Testing
                    scope.launch {
                        val result = chatRepository.testConnection(selectedProvider.id)
                        testState = result.fold(
                            onSuccess = {
                                TestConnectionState.Success(
                                    context.getString(R.string.test_connection_success)
                                )
                            },
                            onFailure = {
                                TestConnectionState.Error(
                                    ErrorMapper.mapToUserMessage(context, it)
                                )
                            }
                        )
                    }
                },
                enabled = isSaved && testState !is TestConnectionState.Testing
            ) {
                if (testState is TestConnectionState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.test_connection_button))
            }

            when (val state = testState) {
                is TestConnectionState.Success -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
                is TestConnectionState.Error -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }

            // Server URL section (local/custom providers only)
            if (selectedProvider.supportsCustomBaseUrl) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.server_url_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.server_url_label)) },
                    placeholder = { Text(selectedProvider.defaultBaseUrl.ifBlank { "http://192.168.1.50:11434" }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.server_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        keyRepository.saveBaseUrl(selectedProvider.id, baseUrl)
                        isSaved = true
                    },
                    enabled = baseUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.save_url_button))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Model Selection
            Text(
                text = stringResource(R.string.model_section),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = !modelExpanded }
            ) {
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.model_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    selectedProvider.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                selectedModel = model
                                modelExpanded = false
                                onModelChanged(model)
                                keyRepository.saveSelectedModel(selectedProvider.id, model)
                            }
                        )
                    }
                }
            }

            // OpenRouter custom model field
            if (selectedProvider.id == "openrouter") {
                Spacer(modifier = Modifier.height(8.dp))
                var customModel by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        if (it.isNotBlank()) {
                            selectedModel = it
                            onModelChanged(it)
                            keyRepository.saveSelectedModel(selectedProvider.id, it)
                        }
                    },
                    label = { Text(stringResource(R.string.custom_model_id_label)) },
                    placeholder = { Text("e.g. anthropic/claude-3.5-sonnet") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.custom_model_id_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Custom model field for local providers
            if (selectedProvider.supportsCustomBaseUrl && selectedProvider.id != "openrouter") {
                Spacer(modifier = Modifier.height(8.dp))
                var customModel by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        if (it.isNotBlank()) {
                            selectedModel = it
                            onModelChanged(it)
                            keyRepository.saveSelectedModel(selectedProvider.id, it)
                        }
                    },
                    label = { Text(stringResource(R.string.model_name_label)) },
                    placeholder = {
                        Text(
                            when (selectedProvider.id) {
                                "ollama" -> "e.g. llama3.2:3b"
                                "lmstudio" -> "e.g. lmstudio-community/Meta-Llama-3.1-8B"
                                else -> "e.g. my-custom-model"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.local_model_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Language Selection
            Text(
                text = stringResource(R.string.language_section),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = !languageExpanded }
            ) {
                OutlinedTextField(
                    value = languageOptions.firstOrNull { it.code == selectedLanguage.value }?.label ?: stringResource(R.string.language_system_default),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    languageOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                selectedLanguage.value = option.code
                                languageExpanded = false
                                localeRepository.saveLocale(option.code)
                                app.applyLocale(option.code)
                                onLocaleChanged()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Default Translation Dialect
            Text(
                text = stringResource(R.string.default_dialect_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = dialectExpanded,
                onExpandedChange = { dialectExpanded = !dialectExpanded }
            ) {
                OutlinedTextField(
                    value = if (isArabic) selectedDialect.nameAr else selectedDialect.nameEn,
                    onValueChange = {},
                    readOnly = true,
                    label = {
                        Text(stringResource(R.string.dialect_label))
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dialectExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = dialectExpanded,
                    onDismissRequest = { dialectExpanded = false }
                ) {
                    Dialect.entries.forEach { dialect ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isArabic) "${dialect.nameAr} (${dialect.nativeName})"
                                    else "${dialect.nameEn} (${dialect.nativeName})"
                                )
                            },
                            onClick = {
                                selectedDialect = dialect
                                dialectExpanded = false
                                preferenceRepository.setDefaultDialect(dialect)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Instructions — dynamic per provider
            Text(
                text = stringResource(
                    R.string.instructions_title_provider,
                    if (isArabic) selectedProvider.nameAr else selectedProvider.displayName
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isArabic)
                    providerConfig?.instructionsAr ?: ""
                else
                    providerConfig?.instructionsEn ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // About & Privacy link
            TextButton(
                onClick = onNavigateToAbout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_about_link),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
