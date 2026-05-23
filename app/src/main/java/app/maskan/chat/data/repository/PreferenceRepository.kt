package app.maskan.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import app.maskan.chat.data.model.Dialect

class PreferenceRepository(context: Context) {

    private val sharedPreferences: SharedPreferences =
        createEncryptedPrefsOrFallback(context, PREFS_NAME)

    private val plainPreferences: SharedPreferences =
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

    fun getDefaultDialect(): Dialect {
        val id = sharedPreferences.getString(KEY_DEFAULT_DIALECT, null)
        return if (id != null) Dialect.fromId(id) else Dialect.LEVANTINE
    }

    fun setDefaultDialect(dialect: Dialect) {
        sharedPreferences.edit().putString(KEY_DEFAULT_DIALECT, dialect.id).apply()
    }

    fun hasCompletedSetup(): Boolean =
        plainPreferences.getBoolean(KEY_COMPLETED_SETUP, false)

    fun setCompletedSetup() {
        plainPreferences.edit().putBoolean(KEY_COMPLETED_SETUP, true).apply()
    }

    companion object {
        // Must differ from KeyRepository.PREFS_NAME to avoid sharing the same encrypted file.
        private const val PREFS_NAME = "maskan_secure_preferences"
        private const val PLAIN_PREFS_NAME = "maskan_prefs"
        private const val KEY_DEFAULT_DIALECT = "default_dialect"
        private const val KEY_COMPLETED_SETUP = "completed_setup"
    }
}
