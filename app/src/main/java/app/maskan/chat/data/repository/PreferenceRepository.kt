package app.maskan.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.maskan.chat.data.model.Dialect

class PreferenceRepository(context: Context) {

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

    fun getDefaultDialect(): Dialect {
        val id = sharedPreferences.getString(KEY_DEFAULT_DIALECT, null)
        return if (id != null) Dialect.fromId(id) else Dialect.LEVANTINE
    }

    fun setDefaultDialect(dialect: Dialect) {
        sharedPreferences.edit().putString(KEY_DEFAULT_DIALECT, dialect.id).apply()
    }

    companion object {
        private const val PREFS_NAME = "maskan_secure_prefs"
        private const val KEY_DEFAULT_DIALECT = "default_dialect"
    }
}
