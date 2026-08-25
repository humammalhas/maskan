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
        return if (id != null) Dialect.fromId(id) else Dialect.MSA
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

    /** Whether the user has been told once that generated images vanish on uninstall. */
    fun hasSeenGeneratedImageNote(): Boolean =
        plainPreferences.getBoolean(KEY_GENERATED_IMAGE_NOTE, false)

    fun setGeneratedImageNoteSeen() {
        plainPreferences.edit().putBoolean(KEY_GENERATED_IMAGE_NOTE, true).apply()
    }

    fun hasSeenVoicePrivacyNote(): Boolean =
        plainPreferences.getBoolean(KEY_VOICE_PRIVACY_NOTE_SEEN, false)

    fun setVoicePrivacyNoteSeen() {
        plainPreferences.edit().putBoolean(KEY_VOICE_PRIVACY_NOTE_SEEN, true).apply()
    }

    // Model lists fetched from a provider's /models endpoint. Cached in the PLAIN prefs on
    // purpose: model ids are public catalogue data, not secrets, and keeping them out of the
    // encrypted file avoids bloating it. The timestamp drives the staleness check that triggers
    // an automatic refresh.
    fun getCachedModels(providerId: String): List<String> =
        plainPreferences.getString(KEY_MODELS_PREFIX + providerId, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun saveCachedModels(
        providerId: String,
        models: List<String>,
        visionModels: Set<String> = emptySet(),
        freeModels: Set<String> = emptySet(),
        imageModels: List<String> = emptyList()
    ) {
        plainPreferences.edit()
            .putString(KEY_MODELS_PREFIX + providerId, models.joinToString("\n"))
            .putStringSet(KEY_VISION_MODELS_PREFIX + providerId, visionModels)
            .putStringSet(KEY_FREE_MODELS_PREFIX + providerId, freeModels)
            .putString(KEY_IMAGE_MODELS_PREFIX + providerId, imageModels.joinToString("\n"))
            .putLong(KEY_MODELS_FETCHED_AT_PREFIX + providerId, System.currentTimeMillis())
            .apply()
    }

    /**
     * Models this provider can DRAW with. Stored as an ordered list (not a Set) so the picker
     * shows them in the same sorted order every time. Empty means either "this provider has
     * none" or "never fetched" - the caller cannot tell those apart and does not need to: with
     * no known image models, the feature simply is not offered.
     */
    fun getImageModels(providerId: String): List<String> =
        plainPreferences.getString(KEY_IMAGE_MODELS_PREFIX + providerId, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Models this provider says accept image input. Empty means the provider publishes no
     * capability data (or was never fetched), NOT that none of them can see images - callers
     * fall back to the provider-level flag instead of hiding the camera on everything.
     */
    fun getVisionModels(providerId: String): Set<String> =
        plainPreferences.getStringSet(KEY_VISION_MODELS_PREFIX + providerId, emptySet())
            ?.toSet() ?: emptySet()

    /** Epoch millis of the last successful fetch, or 0 if this provider was never fetched. */
    fun getModelsFetchedAt(providerId: String): Long =
        plainPreferences.getLong(KEY_MODELS_FETCHED_AT_PREFIX + providerId, 0L)

    // Models the provider itself rejected (403 "not on your plan" / 404 "no such model"). A
    // catalogue lists what the provider hosts, not what THIS key may call - Together is the worst
    // offender but every provider gates something behind a tier. Rejected ids are hidden from the
    // picker so the user does not keep choosing dead models; a manual refresh wipes the slate.
    fun getUnavailableModels(providerId: String): Set<String> =
        plainPreferences.getStringSet(KEY_UNAVAILABLE_MODELS_PREFIX + providerId, emptySet())
            ?.toSet() ?: emptySet()

    fun addUnavailableModel(providerId: String, model: String) {
        val updated = getUnavailableModels(providerId) + model
        plainPreferences.edit()
            .putStringSet(KEY_UNAVAILABLE_MODELS_PREFIX + providerId, updated)
            .apply()
    }

    // Models that have actually answered with this key. Lets the picker say "tested" instead of
    // leaving the user to find out by chatting.
    fun getVerifiedModels(providerId: String): Set<String> =
        plainPreferences.getStringSet(KEY_VERIFIED_MODELS_PREFIX + providerId, emptySet())
            ?.toSet() ?: emptySet()

    /** Models the provider prices at zero - they answer even with no credit on the account. */
    fun getFreeModels(providerId: String): Set<String> =
        plainPreferences.getStringSet(KEY_FREE_MODELS_PREFIX + providerId, emptySet())
            ?.toSet() ?: emptySet()

    fun addVerifiedModel(providerId: String, model: String) {
        plainPreferences.edit()
            .putStringSet(KEY_VERIFIED_MODELS_PREFIX + providerId, getVerifiedModels(providerId) + model)
            .apply()
    }

    fun clearUnavailableModels(providerId: String) {
        plainPreferences.edit()
            .remove(KEY_UNAVAILABLE_MODELS_PREFIX + providerId)
            .apply()
    }

    companion object {
        private const val KEY_IMAGE_MODELS_PREFIX = "image_models_"
        private const val KEY_GENERATED_IMAGE_NOTE = "generated_image_note_seen"

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
        private const val KEY_MODELS_PREFIX = "models_"
        private const val KEY_MODELS_FETCHED_AT_PREFIX = "models_fetched_at_"
        private const val KEY_UNAVAILABLE_MODELS_PREFIX = "models_unavailable_"
        private const val KEY_VISION_MODELS_PREFIX = "models_vision_"
        private const val KEY_VERIFIED_MODELS_PREFIX = "models_verified_"
        private const val KEY_FREE_MODELS_PREFIX = "models_free_"
    }
}
