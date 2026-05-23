package app.maskan.chat.data.local

import android.app.LocaleManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales?.get(0)?.language
    } else {
        AppCompatDelegate.getApplicationLocales().get(0)?.language
    }
}

@Composable
fun isAppArabic(): Boolean = getAppLanguage() == "ar"

@Composable
fun isAppThai(): Boolean = getAppLanguage() == "th"
