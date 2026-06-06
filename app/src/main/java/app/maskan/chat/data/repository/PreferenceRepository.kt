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

    fun hasSeenPrivacyIntro(): Boolean =
        plainPreferences.getBoolean(KEY_PRIVACY_INTRO_SEEN, false)

    fun setPrivacyIntroSeen() {
        plainPreferences.edit().putBoolean(KEY_PRIVACY_INTRO_SEEN, true).apply()
    }

    fun isBlockScreenshots(): Boolean =
        plainPreferences.getBoolean(KEY_BLOCK_SCREENSHOTS, false)

    fun setBlockScreenshots(enabled: Boolean) {
        plainPreferences.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, enabled).apply()
    }

    // True while the user is in the first-launch onboarding's Settings step. Lets that step survive
    // an activity recreate (e.g. changing the language applies a new locale and restarts the
    // Activity), so the "Start Chatting" button isn't lost. Existing users never have this set.
    fun isOnboardingInProgress(): Boolean =
        plainPreferences.getBoolean(KEY_ONBOARDING_IN_PROGRESS, false)

    fun setOnboardingInProgress(inProgress: Boolean) {
        plainPreferences.edit().putBoolean(KEY_ONBOARDING_IN_PROGRESS, inProgress).apply()
    }

    fun hasSeenImagePrivacyNote(): Boolean =
        plainPreferences.getBoolean(KEY_IMAGE_PRIVACY_NOTE_SEEN, false)

    fun setImagePrivacyNoteSeen() {
        plainPreferences.edit().putBoolean(KEY_IMAGE_PRIVACY_NOTE_SEEN, true).apply()
    }

    fun hasSeenVoicePrivacyNote(): Boolean =
        plainPreferences.getBoolean(KEY_VOICE_PRIVACY_NOTE_SEEN, false)

    fun setVoicePrivacyNoteSeen() {
        plainPreferences.edit().putBoolean(KEY_VOICE_PRIVACY_NOTE_SEEN, true).apply()
    }

    companion object {
        // Must differ from KeyRepository.PREFS_NAME to avoid sharing the same encrypted file.
        private const val PREFS_NAME = "maskan_secure_preferences"
        private const val PLAIN_PREFS_NAME = "maskan_prefs"
        private const val KEY_DEFAULT_DIALECT = "default_dialect"
        private const val KEY_COMPLETED_SETUP = "completed_setup"
        private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        private const val KEY_PRIVACY_INTRO_SEEN = "privacy_intro_seen"
        private const val KEY_ONBOARDING_IN_PROGRESS = "onboarding_in_progress"
        private const val KEY_IMAGE_PRIVACY_NOTE_SEEN = "image_privacy_note_seen"
        private const val KEY_VOICE_PRIVACY_NOTE_SEEN = "voice_privacy_note_seen"
    }
}
