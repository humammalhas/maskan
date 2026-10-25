package app.maskan.chat.data.repository

import android.content.Context
import android.content.SharedPreferences
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.providers.ProviderConfigs

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

    /** "Your question is sent to X, which searches the web for it" - shown until dismissed. */
    fun hasSeenWebSearchPrivacyNote(): Boolean =
        plainPreferences.getBoolean(KEY_WEB_SEARCH_PRIVACY_NOTE_SEEN, false)

    fun setWebSearchPrivacyNoteSeen() {
        plainPreferences.edit().putBoolean(KEY_WEB_SEARCH_PRIVACY_NOTE_SEEN, true).apply()
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

    /**
     * Set when the start-up clean-up removed a retired on-device model (2.7: Qwen2.5), so the
     * on-device card says the model was upgraded until the new one is installed.
     */
    fun isOnDeviceUpgraded(): Boolean = plainPreferences.getBoolean(KEY_ONDEVICE_UPGRADED, false)

    fun setOnDeviceUpgraded(upgraded: Boolean) {
        plainPreferences.edit().putBoolean(KEY_ONDEVICE_UPGRADED, upgraded).apply()
    }

    // ── Global memory ──────────────────────────────────────────────────
    //
    // One memory file that belongs to no folder, for the handful of facts that are true in every
    // project ("I live in Amman", "answer me in Arabic"). Off until the user turns it on, and in
    // the ENCRYPTED preferences: it is what the person has told the app about themselves, which
    // is the last thing that should sit in a plaintext file next to a model cache.

    fun isGlobalMemoryEnabled(): Boolean =
        plainPreferences.getBoolean(KEY_GLOBAL_MEMORY_ON, false)

    fun setGlobalMemoryEnabled(enabled: Boolean) {
        plainPreferences.edit().putBoolean(KEY_GLOBAL_MEMORY_ON, enabled).apply()
    }

    /** Null, never an empty string, so "has the user written anything" is one check everywhere. */
    fun getGlobalMemory(): String? =
        sharedPreferences.getString(KEY_GLOBAL_MEMORY, null)?.takeIf { it.isNotBlank() }

    fun setGlobalMemory(text: String?) {
        val trimmed = text?.takeIf { it.isNotBlank() }
        sharedPreferences.edit().apply {
            if (trimmed == null) remove(KEY_GLOBAL_MEMORY) else putString(KEY_GLOBAL_MEMORY, trimmed)
        }.apply()
    }

    // ── The custom system prompt ─────────────────────────────────
    //
    // ONE prompt, the last one written in "Create your own", so the next chat starts from it
    // instead of an empty box. A named library of custom presets is a screen of its own and is
    // not this release. In the ENCRYPTED preferences for the same reason the memory file is:
    // it is the user's own words about how they want to be answered.

    /** Null, never an empty string. */
    fun getCustomPrompt(): String? =
        sharedPreferences.getString(KEY_CUSTOM_PROMPT, null)?.takeIf { it.isNotBlank() }

    fun setCustomPrompt(text: String?) {
        val trimmed = text?.takeIf { it.isNotBlank() }
        sharedPreferences.edit().apply {
            if (trimmed == null) remove(KEY_CUSTOM_PROMPT) else putString(KEY_CUSTOM_PROMPT, trimmed)
        }.apply()
    }

    // ── Chat behaviour switches ────────────────────────────────────

    /**
     * Whether a finished first exchange may cost ONE extra request to name the chat. On by
     * default; off means exactly what 2.5.0 did - the first 50 characters of the first message.
     */
    fun isAutoTitleEnabled(): Boolean =
        plainPreferences.getBoolean(KEY_AUTO_TITLE, true)

    fun setAutoTitleEnabled(enabled: Boolean) {
        plainPreferences.edit().putBoolean(KEY_AUTO_TITLE, enabled).apply()
    }

    /**
     * Whether a model's reasoning is shown (plan item 2). On by default. Off hides it everywhere,
     * and on the providers measured to allow it (DeepSeek, Together, Venice, Custom/Ollama) also
     * asks the model not to think - see the providers' thinkingOff.
     */
    fun isShowThinkingEnabled(): Boolean =
        plainPreferences.getBoolean(KEY_SHOW_THINKING, true)

    fun setShowThinkingEnabled(enabled: Boolean) {
        plainPreferences.edit().putBoolean(KEY_SHOW_THINKING, enabled).apply()
    }

    /**
     * Which provider writes out an attached audio file (2.7, plan item 3), by provider id - one of
     * TranscribeBackends.ALL. Null until the user picks one: then the first provider in their own
     * order that has a key is used (ChatRepository.transcribeBackend).
     */
    fun getTranscribeBackend(): String? = plainPreferences.getString(KEY_TRANSCRIBE_BACKEND, null)

    fun setTranscribeBackend(providerId: String?) {
        plainPreferences.edit().putString(KEY_TRANSCRIBE_BACKEND, providerId).apply()
    }

    /**
     * Whether a reply carries a speak button in its footer. Default true, i.e. unchanged from
     * 2.5.0. Only the BUTTON: "Read this to me" under a photo is the user asking for speech by
     * name and is not governed by this.
     */
    fun isSpeakButtonShown(): Boolean =
        plainPreferences.getBoolean(KEY_SPEAK_BUTTON, true)

    fun setSpeakButtonShown(shown: Boolean) {
        plainPreferences.edit().putBoolean(KEY_SPEAK_BUTTON, shown).apply()
    }

    /**
     * The user's order and hidden set for the provider list (see ProviderOrder). Plain prefs:
     * provider ids are not secrets, and the backup carries this file whole.
     */
    fun getProviderOrder(): List<String> =
        plainPreferences.getString(KEY_PROVIDER_ORDER, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setProviderOrder(ids: List<String>) {
        plainPreferences.edit().putString(KEY_PROVIDER_ORDER, ids.joinToString("\n")).apply()
    }

    fun getHiddenProviders(): Set<String> =
        plainPreferences.getStringSet(KEY_HIDDEN_PROVIDERS, emptySet()) ?: emptySet()

    fun setHiddenProviders(ids: Set<String>) {
        plainPreferences.edit().putStringSet(KEY_HIDDEN_PROVIDERS, ids).apply()
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
        imageModels: List<String> = emptyList(),
        videoModels: List<String> = emptyList(),
        transcribeModels: List<String> = emptyList()
    ) {
        plainPreferences.edit()
            .putString(KEY_MODELS_PREFIX + providerId, models.joinToString("\n"))
            .putStringSet(KEY_VISION_MODELS_PREFIX + providerId, visionModels)
            .putStringSet(KEY_FREE_MODELS_PREFIX + providerId, freeModels)
            .putString(KEY_IMAGE_MODELS_PREFIX + providerId, imageModels.joinToString("\n"))
            .putString(KEY_VIDEO_MODELS_PREFIX + providerId, videoModels.joinToString("\n"))
            .putString(KEY_TRANSCRIBE_MODELS_PREFIX + providerId, transcribeModels.joinToString("\n"))
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

    // The composer's last shape and length choices. Plain, not encrypted: nothing secret in
    // "9:16". Remembered so a person who always wants tall clips sets it once.
    fun getVideoSize(): String? = plainPreferences.getString(KEY_VIDEO_SIZE, null)
    fun setVideoSize(size: String) = plainPreferences.edit().putString(KEY_VIDEO_SIZE, size).apply()
    fun getVideoSeconds(): Int = plainPreferences.getInt(KEY_VIDEO_SECONDS, 0)
    fun setVideoSeconds(seconds: Int) = plainPreferences.edit().putInt(KEY_VIDEO_SECONDS, seconds).apply()
    fun getImageSize(): String? = plainPreferences.getString(KEY_IMAGE_SIZE, null)
    fun setImageSize(size: String) = plainPreferences.edit().putString(KEY_IMAGE_SIZE, size).apply()

    /**
     * Models a local server says it TRANSCRIBES with (its /health, 2.7 plan item 3). Empty means
     * "does not transcribe" or "not fetched since the server learned to" - Refresh model list.
     */
    fun getTranscribeModels(providerId: String): List<String> =
        plainPreferences.getString(KEY_TRANSCRIBE_MODELS_PREFIX + providerId, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** Models this provider can make VIDEO with. Same shape and caveats as [getImageModels]. */
    fun getVideoModels(providerId: String): List<String> =
        plainPreferences.getString(KEY_VIDEO_MODELS_PREFIX + providerId, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Models this provider says accept image input, plus the ones its config knows about
     * ([ProviderConfig.visionModels]). Empty means the provider publishes no capability data
     * (or was never fetched), NOT that none of them can see images - callers fall back to the
     * provider-level flag instead of hiding the camera on everything.
     */
    fun getVisionModels(providerId: String): Set<String> {
        val cached = plainPreferences.getStringSet(KEY_VISION_MODELS_PREFIX + providerId, emptySet())
            ?.toSet() ?: emptySet()
        val known = ProviderConfigs.ALL.firstOrNull { it.id == providerId }?.visionModels ?: emptySet()
        return cached + known
    }

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

    fun addUnavailableModel(providerId: String, model: String, reason: String? = null) {
        val updated = getUnavailableModels(providerId) + model
        val editor = plainPreferences.edit()
            .putStringSet(KEY_UNAVAILABLE_MODELS_PREFIX + providerId, updated)
        if (!reason.isNullOrBlank()) {
            val reasons = getUnavailableReasons(providerId) + (model to reason)
            editor.putString(
                KEY_UNAVAILABLE_REASONS_PREFIX + providerId,
                reasons.entries.joinToString("\n") { "${it.key}\t${it.value.replace("\n", " ").replace("\t", " ")}" }
            )
        }
        editor.apply()
    }

    /**
     * The provider's own words for WHY each rejected model was refused ("requires a dedicated
     * endpoint", "accept the terms"). Shown under the greyed rows in the picker: "not available"
     * alone sends the user guessing, the reason tells them whether anything can be done.
     */
    fun getUnavailableReasons(providerId: String): Map<String, String> =
        plainPreferences.getString(KEY_UNAVAILABLE_REASONS_PREFIX + providerId, null)
            ?.split("\n")
            ?.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            ?.toMap()
            ?: emptyMap()

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
            .remove(KEY_UNAVAILABLE_REASONS_PREFIX + providerId)
            .apply()
    }

    companion object {
        private const val KEY_IMAGE_MODELS_PREFIX = "image_models_"
        private const val KEY_VIDEO_MODELS_PREFIX = "video_models_"
        private const val KEY_TRANSCRIBE_MODELS_PREFIX = "transcribe_models_"
        private const val KEY_VIDEO_SIZE = "video_size"
        private const val KEY_VIDEO_SECONDS = "video_seconds"
        private const val KEY_IMAGE_SIZE = "image_size"
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
        private const val KEY_WEB_SEARCH_PRIVACY_NOTE_SEEN = "web_search_privacy_note_seen"
        private const val KEY_VOICE_PRIVACY_NOTE_SEEN = "voice_privacy_note_seen"
        private const val KEY_MODELS_PREFIX = "models_"
        private const val KEY_MODELS_FETCHED_AT_PREFIX = "models_fetched_at_"
        private const val KEY_UNAVAILABLE_MODELS_PREFIX = "models_unavailable_"
        private const val KEY_UNAVAILABLE_REASONS_PREFIX = "models_unavailable_why_"
        private const val KEY_VISION_MODELS_PREFIX = "models_vision_"
        private const val KEY_VERIFIED_MODELS_PREFIX = "models_verified_"
        private const val KEY_FREE_MODELS_PREFIX = "models_free_"

        // The switch is a plain preference (it decides what a screen draws); the text it switches
        // on is encrypted with the rest of the user's own words.
        private const val KEY_GLOBAL_MEMORY_ON = "global_memory_enabled"
        private const val KEY_GLOBAL_MEMORY = "global_memory"
        private const val KEY_CUSTOM_PROMPT = "custom_system_prompt"
        private const val KEY_AUTO_TITLE = "auto_title_enabled"
        private const val KEY_SHOW_THINKING = "show_thinking_enabled"
        private const val KEY_SPEAK_BUTTON = "speak_button_shown"
        private const val KEY_TRANSCRIBE_BACKEND = "transcribe_backend"
        private const val KEY_PROVIDER_ORDER = "provider_order"
        private const val KEY_HIDDEN_PROVIDERS = "hidden_providers"
        private const val KEY_ONDEVICE_UPGRADED = "ondevice_upgraded"
    }
}
