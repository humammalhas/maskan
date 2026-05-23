package app.maskan.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Locale

/**
 * Repository for persisting the user's locale/language preference.
 * Uses EncryptedSharedPreferences for consistency with our security model.
 *
 * Supported options:
 * - "" (empty string) = follow system default
 * - "en" = English
 * - "ar" = Arabic
 */
class LocaleRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Save the user's language choice.
     * @param localeCode ISO 639-1 code ("en", "ar") or empty string for system default.
     */
    fun saveLocale(localeCode: String) {
        sharedPreferences.edit().putString(KEY_LOCALE, localeCode).apply()
    }

    /**
     * Retrieve the saved locale code.
     * Returns empty string if not set (meaning follow system default).
     */
    fun getLocale(): String {
        return sharedPreferences.getString(KEY_LOCALE, "") ?: ""
    }

    /**
     * Returns a Locale object for the saved preference.
     */
    fun getLocaleObject(): Locale? {
        val code = getLocale()
        return when (code) {
            "en" -> Locale.forLanguageTag("en")
            "ar" -> Locale.forLanguageTag("ar")
            else -> null // null means follow system default
        }
    }

    /**
     * Returns the display name of the current locale setting.
     */
    fun getLocaleDisplayName(): String {
        return getLocale().ifEmpty { "system" }
    }

    companion object {
        private const val PREFS_NAME = "maskan_locale_prefs"
        private const val KEY_LOCALE = "app_locale"
    }
}
