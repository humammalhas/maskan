package app.maskan.chat.data.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

enum class PresetCategory {
    WRITING,
    TRANSLATION,
    CODE,
    CONVERSATION,
    ARABIC_SPECIFIC
}

data class SystemPromptPreset(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val nameTh: String = "",
    val descriptionEn: String,
    val descriptionAr: String,
    val descriptionTh: String = "",
    val systemPromptEn: String,
    val systemPromptAr: String,
    val systemPromptTh: String = "",
    val category: PresetCategory,
    val icon: String
)

@Composable
fun SystemPromptPreset.localizedName(): String = when {
    isAppThai() && nameTh.isNotEmpty() -> nameTh
    isAppArabic() -> nameAr
    else -> nameEn
}

@Composable
fun SystemPromptPreset.localizedDescription(): String = when {
    isAppThai() && descriptionTh.isNotEmpty() -> descriptionTh
    isAppArabic() -> descriptionAr
    else -> descriptionEn
}

@Composable
private fun getAppLanguage(): String? {
    // Read the language from the resource CONFIGURATION - the same source stringResource
    // resolves from - not from LocaleManager. LocaleManager.applicationLocales only reflects a
    // locale the app set for itself; a per-app language chosen in the SYSTEM settings (or over
    // adb) never appears there, which left every preset name in English while the rest of the
    // screen spoke Arabic.
    return LocalConfiguration.current.locales.get(0)?.language
}

@Composable
fun isAppArabic(): Boolean = getAppLanguage() == "ar"

@Composable
fun isAppThai(): Boolean = getAppLanguage() == "th"
