package app.maskan.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.maskan.chat.R

/**
 * Noto Sans Arabic font family, loaded from bundled TTF files.
 * Used when the device locale is Arabic.
 */
private val ArabicFontFamily = FontFamily(
    Font(R.font.notosans_arabic_regular, FontWeight.Normal),
    Font(R.font.notosans_arabic_medium, FontWeight.Medium),
    Font(R.font.notosans_arabic_bold, FontWeight.Bold)
)

/**
 * Typography using Noto Sans Arabic for Arabic locales.
 * Adjusts line height for Arabic script readability.
 */
private val ArabicTypography = Typography(
    displayLarge = TextStyle(fontFamily = ArabicFontFamily, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = ArabicFontFamily, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = ArabicFontFamily, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = ArabicFontFamily, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = ArabicFontFamily, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = ArabicFontFamily, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = ArabicFontFamily, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = ArabicFontFamily, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = ArabicFontFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = ArabicFontFamily, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = ArabicFontFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = ArabicFontFamily, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = ArabicFontFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = ArabicFontFamily, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = ArabicFontFamily, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)

/**
 * Light color scheme using warm pastel tones.
 * Dark theme is intentionally excluded for MVP — can be added later.
 */
private val LightColorScheme = lightColorScheme(
    primary = SoftCoral,
    onPrimary = DarkText,
    primaryContainer = WarmPeach,
    onPrimaryContainer = DarkText,
    secondary = SoftLavender,
    onSecondary = DarkText,
    secondaryContainer = MintGreen,
    onSecondaryContainer = DarkText,
    tertiary = SkyBlue,
    onTertiary = DarkText,
    background = White,
    onBackground = DarkText,
    surface = White,
    onSurface = DarkText,
    surfaceVariant = LightGray,
    onSurfaceVariant = MediumGray,
    outline = MediumGray
)

/**
 * Maskan theme. Uses Noto Sans Arabic typography when the current locale is Arabic,
 * otherwise falls back to Material 3 default typography (which uses system Roboto/ default).
 *
 * @param isArabic set to true to use the Arabic font family.
 */
@Composable
fun MaskanTheme(
    isArabic: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = if (isArabic) ArabicTypography else Typography(),
        content = content
    )
}
