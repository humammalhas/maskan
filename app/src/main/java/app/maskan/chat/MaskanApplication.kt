/*
 * Maskan — Private AI chat
 * Copyright (C) 2025 Humam Malhas and Maskan contributors
 *
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 *
 * See LICENSE file for full terms.
 */
package app.maskan.chat

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.maskan.chat.data.local.AppDatabase
import app.maskan.chat.data.remote.AnthropicService
import app.maskan.chat.data.remote.GeminiService
import app.maskan.chat.data.remote.OpenAiCompatibleService
import app.maskan.chat.data.remote.providers.AnthropicProvider
import app.maskan.chat.data.remote.providers.GeminiProvider
import app.maskan.chat.data.remote.providers.LocalProvider
import app.maskan.chat.data.remote.providers.OpenAiCompatibleProvider
import app.maskan.chat.data.remote.providers.ProviderConfigs
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.LocaleRepository
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.ui.viewmodel.ChatViewModel
import app.maskan.chat.ui.viewmodel.ConversationListViewModel
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class MaskanApplication : Application() {

    // ── Localization ───────────────────────────────────────────────────

    val localeRepository: LocaleRepository by lazy { LocaleRepository(this) }

    // ── Network ────────────────────────────────────────────────────────

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val sharedOkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun createOpenAiService(baseUrl: String): OpenAiCompatibleService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(sharedOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiCompatibleService::class.java)
    }

    // ── Local Database ─────────────────────────────────────────────────

    private val database by lazy { AppDatabase.getInstance(this) }

    // ── Repositories ───────────────────────────────────────────────────

    val keyRepository by lazy { KeyRepository(this) }

    val preferenceRepository by lazy { PreferenceRepository(this) }

    val chatRepository by lazy {
        ChatRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            folderDao = database.folderDao(),
            keyRepository = keyRepository,
            localeRepository = localeRepository
        )
    }

    // ── ViewModels ─────────────────────────────────────────────────────

    fun provideConversationListViewModel(): ConversationListViewModel {
        return ConversationListViewModel(chatRepository, keyRepository)
    }

    fun provideChatViewModel(): ChatViewModel {
        return ChatViewModel(this, chatRepository)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        applySavedLocale()
        registerProviders()
    }

    private fun registerProviders() {
        for (config in ProviderConfigs.ALL_OPENAI_COMPATIBLE) {
            val service = createOpenAiService(config.baseUrl)
            val provider = OpenAiCompatibleProvider(
                id = config.id,
                displayName = config.displayName,
                nameAr = config.nameAr,
                defaultBaseUrl = config.baseUrl,
                supportsCustomBaseUrl = config.supportsCustomBaseUrl,
                availableModels = config.models,
                defaultModel = config.defaultModel,
                keyAcquisitionUrl = config.keyAcquisitionUrl,
                pricingInfo = config.pricingInfo,
                apiService = service
            )
            ProviderRegistry.register(provider)
        }
        registerAnthropicProvider()
        registerGeminiProvider()
        registerLocalProviders()
    }

    private fun registerAnthropicProvider() {
        val service = Retrofit.Builder()
            .baseUrl(ProviderConfigs.ANTHROPIC.baseUrl)
            .client(sharedOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnthropicService::class.java)
        val provider = AnthropicProvider(
            config = ProviderConfigs.ANTHROPIC,
            apiService = service
        )
        ProviderRegistry.register(provider)
    }

    private fun registerGeminiProvider() {
        val service = Retrofit.Builder()
            .baseUrl(ProviderConfigs.GEMINI.baseUrl)
            .client(sharedOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiService::class.java)
        val provider = GeminiProvider(
            config = ProviderConfigs.GEMINI,
            apiService = service
        )
        ProviderRegistry.register(provider)
    }

    private fun registerLocalProviders() {
        for (config in ProviderConfigs.ALL_LOCAL) {
            val provider = LocalProvider(
                config = config,
                okHttpClient = sharedOkHttpClient,
                json = json
            )
            ProviderRegistry.register(provider)
        }
    }

    private fun applySavedLocale() {
        val saved = localeRepository.getLocale()
        val languageTag = when {
            saved.isNotEmpty() -> saved
            java.util.Locale.getDefault().language == "ar" -> "ar"
            else -> ""
        }
        applyLocale(languageTag)
    }

    fun applyLocale(languageTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = if (languageTag.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (languageTag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(languageTag)
                }
            )
        }
    }
}
