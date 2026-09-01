package app.maskan.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.maskan.chat.R

private val ArabicFontFamily = FontFamily(
    Font(R.font.notosans_arabic_regular, FontWeight.Normal),
    Font(R.font.notosans_arabic_medium, FontWeight.Medium),
    Font(R.font.notosans_arabic_bold, FontWeight.Bold)
)

/**
 * Arabic faces (the Noto Sans Arabic family here) carry far taller ascent/descent metrics than
 * Latin ones, and Compose's legacy includeFontPadding turns those metrics into extra padding
 * around every line. That is why the same bodySmall occupied visibly more vertical space in
 * Arabic than in English - clipping the second header line in the 64dp TopAppBar and adding
 * phantom line spacing on cards. Turning the padding off and centring lines inside their
 * lineHeight fixes the CAUSE app-wide, instead of nudging symptoms per-screen with offsets.
 */
private val ArabicPlatformStyle = PlatformTextStyle(includeFontPadding = false)
private val ArabicLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun arabicStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified
) = TextStyle(
    fontFamily = ArabicFontFamily,
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    platformStyle = ArabicPlatformStyle,
    lineHeightStyle = ArabicLineHeightStyle
)

private val ArabicTypography = Typography(
    displayLarge = arabicStyle(fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = arabicStyle(fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = arabicStyle(fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = arabicStyle(fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = arabicStyle(fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = arabicStyle(fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = arabicStyle(fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = arabicStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = arabicStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = arabicStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = arabicStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = arabicStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = arabicStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = arabicStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = arabicStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)

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

private val DarkColorScheme = darkColorScheme(
    primary = SoftCoralDark,
    onPrimary = LightText,
    primaryContainer = WarmPeachDark,
    onPrimaryContainer = LightText,
    secondary = SoftLavenderDark,
    onSecondary = LightText,
    secondaryContainer = MintGreenDark,
    onSecondaryContainer = LightText,
    tertiary = SkyBlueDark,
    onTertiary = LightText,
    background = Color(0xFF1A1A1E),
    onBackground = LightText,
    surface = Color(0xFF222226),
    onSurface = LightText,
    surfaceVariant = Color(0xFF2E2E34),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF888888)
)

data class MaskanColors(
    val warmPeach: Color,
    val mintGreen: Color,
    val softLavender: Color,
    val palePink: Color,
    val softCoral: Color,
    val warmSand: Color,
    val skyBlue: Color,
    val paleYellow: Color,
    val aqua: Color,
    val periwinkle: Color,
    val userBubble: Color,
    val assistantBubble: Color,
    val success: Color,
)

val LightMaskanColors = MaskanColors(
    warmPeach = WarmPeach,
    mintGreen = MintGreen,
    softLavender = SoftLavender,
    palePink = PalePink,
    softCoral = SoftCoral,
    warmSand = WarmSand,
    skyBlue = SkyBlue,
    paleYellow = PaleYellow,
    aqua = Aqua,
    periwinkle = Periwinkle,
    userBubble = UserBubble,
    assistantBubble = AssistantBubble,
    success = SuccessGreen,
)

val DarkMaskanColors = MaskanColors(
    warmPeach = WarmPeachDark,
    mintGreen = MintGreenDark,
    softLavender = SoftLavenderDark,
    palePink = PalePinkDark,
    softCoral = SoftCoralDark,
    warmSand = WarmSandDark,
    skyBlue = SkyBlueDark,
    paleYellow = PaleYellowDark,
    aqua = AquaDark,
    periwinkle = PeriwinkleDark,
    userBubble = UserBubbleDark,
    assistantBubble = AssistantBubbleDark,
    success = SuccessGreenDark,
)

val LocalMaskanColors = staticCompositionLocalOf { LightMaskanColors }

val MaterialTheme.maskanColors: MaskanColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMaskanColors.current

@Composable
fun MaskanTheme(
    isArabic: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) DarkColorScheme else LightColorScheme
    val maskanColors = if (dark) DarkMaskanColors else LightMaskanColors

    CompositionLocalProvider(LocalMaskanColors provides maskanColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (isArabic) ArabicTypography else Typography(),
            content = content
        )
    }
}
