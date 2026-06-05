package app.maskan.chat.ui.screens

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.ui.viewmodel.SettingsViewModel
import app.maskan.chat.ui.viewmodel.TestConnectionState
import androidx.compose.foundation.layout.Arrangement
import app.maskan.chat.ui.theme.maskanColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.collectAsState


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onLocaleChanged: () -> Unit = {},
    onModelChanged: (String) -> Unit = {},
    isFirstLaunch: Boolean = false
) {
    val isArabic = isAppArabic()
    val state by viewModel.uiState.collectAsState()

    val allProviders = viewModel.allProviders
    val selectedProvider = state.selectedProvider
    val apiKey = state.apiKey
    val baseUrl = state.baseUrl
    val selectedModel = state.selectedModel
    val isSaved = state.isSaved
    val testState = state.testState
    val selectedDialect = state.selectedDialect

    var isKeyVisible by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var dialectExpanded by remember { mutableStateOf(false) }

    val providerConfig = remember(selectedProvider.id) {
        viewModel.getProviderConfig()
    }

    data class LanguageOption(val code: String, val label: String)
    val languageOptions = listOf(
        LanguageOption("", stringResource(R.string.language_system_default)),
        LanguageOption("en", stringResource(R.string.language_english)),
        LanguageOption("ar", stringResource(R.string.language_arabic)),
        LanguageOption("th", stringResource(R.string.language_thai))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.maskanColors.mintGreen
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.maskanColors.skyBlue.copy(alpha = 0.4f))
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
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    allProviders.forEach { provider ->
                        val hasSavedKey = provider.id in state.configuredProviderIds
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isArabic) provider.nameAr else provider.displayName,
                                    fontWeight = if (hasSavedKey)
                                        androidx.compose.ui.text.font.FontWeight.Bold
                                    else
                                        androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            trailingIcon = if (hasSavedKey) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.provider_configured),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null,
                            onClick = {
                                viewModel.selectProvider(provider)
                                providerExpanded = false
                                isKeyVisible = false
                                onModelChanged(viewModel.uiState.value.selectedModel)
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

            Spacer(modifier = Modifier.height(8.dp))
            val badgeColor = if (selectedProvider.isLocal)
                MaterialTheme.colorScheme.tertiary
            else
                MaterialTheme.colorScheme.outline
            Text(
                text = if (selectedProvider.isLocal)
                    stringResource(R.string.provider_badge_local)
                else
                    stringResource(R.string.provider_badge_cloud),
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                modifier = Modifier
                    .background(
                        color = badgeColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
                        .background(MaterialTheme.maskanColors.skyBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "API key instructions",
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
                onValueChange = { viewModel.updateApiKey(it) },
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
                        contentDescription = "API key secured",
                        tint = MaterialTheme.maskanColors.mintGreen,
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
                onClick = { viewModel.saveApiKey() },
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
                onClick = { viewModel.testConnection() },
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
                    onValueChange = { viewModel.updateBaseUrl(it) },
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
                    onClick = { viewModel.saveBaseUrl() },
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
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    selectedProvider.availableModels.forEach { model ->
                        val isActiveModel = model.trim() == selectedModel.trim()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = model,
                                    fontWeight = if (isActiveModel)
                                        androidx.compose.ui.text.font.FontWeight.Bold
                                    else
                                        androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            trailingIcon = if (isActiveModel) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.model_active),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null,
                            onClick = {
                                viewModel.selectModel(model)
                                modelExpanded = false
                                onModelChanged(model)
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
                            viewModel.selectModel(it)
                            onModelChanged(it)
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
                            viewModel.selectModel(it)
                            onModelChanged(it)
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
                    value = languageOptions.firstOrNull { it.code == state.selectedLocale }?.label ?: stringResource(R.string.language_system_default),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                                viewModel.selectLanguage(option.code)
                                languageExpanded = false
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
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                                viewModel.selectDialect(dialect)
                                dialectExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security
            Text(
                text = stringResource(R.string.security_section),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            val activity = LocalContext.current as? Activity
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.block_screenshots_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.block_screenshots_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = state.blockScreenshots,
                    onCheckedChange = {
                        val enabled = viewModel.toggleBlockScreenshots()
                        activity?.window?.let { window ->
                            if (enabled) {
                                window.setFlags(
                                    WindowManager.LayoutParams.FLAG_SECURE,
                                    WindowManager.LayoutParams.FLAG_SECURE
                                )
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        }
                    }
                )
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

            // Privacy Details link
            TextButton(
                onClick = onNavigateToPrivacy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_privacy_link),
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
