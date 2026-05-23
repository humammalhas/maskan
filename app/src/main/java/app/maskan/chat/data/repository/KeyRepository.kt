package app.maskan.chat.data.repository

import android.content.Context
import android.content.SharedPreferences

class KeyRepository(context: Context) {

    private val sharedPreferences: SharedPreferences =
        createEncryptedPrefsOrFallback(context, PREFS_NAME)

    private var migrationDone = false

    private fun migrateOldKeyIfNeeded() {
        if (migrationDone) return
        migrationDone = true
        val oldKey = sharedPreferences.getString(KEY_API_KEY_LEGACY, null)
        if (oldKey != null && sharedPreferences.getString(providerKeyName("deepseek"), null) == null) {
            sharedPreferences.edit()
                .putString(providerKeyName("deepseek"), oldKey)
                .remove(KEY_API_KEY_LEGACY)
                .apply()
        }
    }

    fun saveApiKey(key: String) {
        saveApiKey("deepseek", key)
    }

    fun getApiKey(): String? {
        return getApiKey("deepseek")
    }

    fun saveApiKey(providerId: String, key: String) {
        migrateOldKeyIfNeeded()
        sharedPreferences.edit().putString(providerKeyName(providerId), key).apply()
    }

    fun getApiKey(providerId: String): String? {
        migrateOldKeyIfNeeded()
        return sharedPreferences.getString(providerKeyName(providerId), null)
    }

    fun deleteApiKey(providerId: String) {
        sharedPreferences.edit().remove(providerKeyName(providerId)).apply()
    }

    fun clearApiKey() {
        deleteApiKey("deepseek")
    }

    fun hasApiKey(): Boolean = getApiKey() != null

    fun hasApiKey(providerId: String): Boolean = getApiKey(providerId) != null

    fun getAllStoredProviderIds(): List<String> {
        migrateOldKeyIfNeeded()
        return sharedPreferences.all.keys
            .filter { it.startsWith(PROVIDER_KEY_PREFIX) && it.endsWith(PROVIDER_KEY_SUFFIX) }
            .map { it.removePrefix(PROVIDER_KEY_PREFIX).removeSuffix(PROVIDER_KEY_SUFFIX) }
    }

    fun getSelectedModel(providerId: String): String? {
        return sharedPreferences.getString(providerModelName(providerId), null)
    }

    fun saveSelectedModel(providerId: String, model: String) {
        sharedPreferences.edit().putString(providerModelName(providerId), model).apply()
    }

    fun saveBaseUrl(providerId: String, url: String) {
        sharedPreferences.edit().putString(providerBaseUrlName(providerId), url).apply()
    }

    fun getBaseUrl(providerId: String): String? {
        return sharedPreferences.getString(providerBaseUrlName(providerId), null)
    }

    fun getDefaultProviderId(): String? {
        return sharedPreferences.getString(KEY_DEFAULT_PROVIDER, null)
    }

    fun setDefaultProviderId(providerId: String) {
        sharedPreferences.edit().putString(KEY_DEFAULT_PROVIDER, providerId).apply()
    }

    companion object {
        private const val PREFS_NAME = "maskan_secure_prefs"
        private const val KEY_API_KEY_LEGACY = "deepseek_api_key"
        private const val PROVIDER_KEY_PREFIX = "provider_"
        private const val PROVIDER_KEY_SUFFIX = "_api_key"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"

        private fun providerKeyName(providerId: String) =
            "${PROVIDER_KEY_PREFIX}${providerId}${PROVIDER_KEY_SUFFIX}"

        private fun providerModelName(providerId: String) =
            "${PROVIDER_KEY_PREFIX}${providerId}_model"

        private fun providerBaseUrlName(providerId: String) =
            "${PROVIDER_KEY_PREFIX}${providerId}_base_url"
    }
}
