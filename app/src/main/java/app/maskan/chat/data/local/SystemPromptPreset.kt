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
    val descriptionEn: String,
    val descriptionAr: String,
    val systemPromptEn: String,
    val systemPromptAr: String,
    val category: PresetCategory,
    val icon: String
)

@Composable
fun SystemPromptPreset.localizedName(): String =
    if (isAppArabic()) nameAr else nameEn

@Composable
fun SystemPromptPreset.localizedDescription(): String =
    if (isAppArabic()) descriptionAr else descriptionEn

@Composable
fun isAppArabic(): Boolean {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales?.get(0)?.language == "ar"
    } else {
        AppCompatDelegate.getApplicationLocales().get(0)?.language == "ar"
    }
}
