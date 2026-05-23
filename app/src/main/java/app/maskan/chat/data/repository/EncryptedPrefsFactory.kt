package app.maskan.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "EncryptedPrefs"

fun createEncryptedPrefsOrFallback(context: Context, prefsName: String): SharedPreferences {
    return try {
        buildEncryptedPrefs(context, prefsName)
    } catch (_: Exception) {
        Log.w(TAG, "Encrypted prefs corrupted, attempting recovery")
        try {
            context.deleteSharedPreferences(prefsName)
            buildEncryptedPrefs(context, prefsName)
        } catch (_: Exception) {
            Log.e(TAG, "Recovery failed, using in-memory fallback")
            InMemorySharedPreferences()
        }
    }
}

private fun buildEncryptedPrefs(context: Context, prefsName: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        prefsName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

private class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = InMemoryEditor(data)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class InMemoryEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var doClear = false

        override fun putString(key: String?, value: String?) = apply { key?.let { pending[it] = value } }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { key?.let { pending[it] = values } }
        override fun putInt(key: String?, value: Int) = apply { key?.let { pending[it] = value } }
        override fun putLong(key: String?, value: Long) = apply { key?.let { pending[it] = value } }
        override fun putFloat(key: String?, value: Float) = apply { key?.let { pending[it] = value } }
        override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { pending[it] = value } }
        override fun remove(key: String?) = apply { key?.let { removals.add(it) } }
        override fun clear() = apply { doClear = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }

        private fun flush() {
            if (doClear) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}
