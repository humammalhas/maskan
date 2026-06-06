package app.maskan.chat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maskan.chat.R
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.SystemPromptPreset
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.local.localizedDescription
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.ui.theme.maskanColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPicker(
    defaultDialect: Dialect,
    onPresetSelected: (SystemPromptPreset, Dialect?) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = Presets.all(defaultDialect)

    var showDialectSheet by remember { mutableStateOf(false) }

    if (showDialectSheet) {
        DialectBottomSheet(
            currentDialect = defaultDialect,
            onDialectSelected = { dialect ->
                showDialectSheet = false
                onPresetSelected(Presets.enToArPreset(dialect), dialect)
            },
            onDismiss = { showDialectSheet = false }
        )
    }

    // A height-filling column instead of a scrolling grid: the heading sits on top and the cards
    // are laid out in equal-weight rows of two, so all presets fit on a single screen with no scroll.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.preset_picker_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp)
        )

        presets.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        onClick = {
                            if (preset.id == "en_to_ar") {
                                showDialectSheet = true
                            } else {
                                onPresetSelected(preset, null)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                // Keep a single trailing card left-aligned if the count is ever odd.
                if (rowPresets.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: SystemPromptPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name = preset.localizedName()
    val description = preset.localizedDescription()
    val cardColor = presetColor(preset.id)

    Card(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Top-anchored so the icon + title sit together near the top (matching EN/TH) and the
            // description has a clear, unclipped spot directly beneath the title.
            verticalArrangement = Arrangement.Top
        ) {
            // The emoji/flag line-box renders ~2x the glyph height regardless of includeFontPadding,
            // which was eating the whole card and laying the description out at height 0. Pinning the
            // icon inside a fixed 30dp box caps that footprint so title + description always fit.
            Box(
                modifier = Modifier.height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = preset.icon,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 22.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    )
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = name,
                // Unbolded and a touch smaller per request; one line so it never wraps.
                // includeFontPadding=false + trimmed lineHeight removes the tall script's extra
                // vertical padding so Arabic sits tight to the icon instead of dropping a blank line.
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                // One line. includeFontPadding=false + Trim.Both strips the extra leading that
                // Arabic glyph metrics add above/below the line, which was pushing this line off the
                // bottom of the short one-page card (it rendered, but got clipped to nothing).
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialectBottomSheet(
    currentDialect: Dialect,
    onDialectSelected: (Dialect) -> Unit,
    onDismiss: () -> Unit
) {
    val isArabic = isAppArabic()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.dialect_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Dialect.entries.forEach { dialect ->
                val selected = dialect == currentDialect
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable(role = Role.Button) { onDialectSelected(dialect) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected)
                            MaterialTheme.maskanColors.skyBlue
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = if (selected)
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    else
                        null
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (isArabic) dialect.nameAr else dialect.nameEn,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = dialect.nativeName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// Per-card pastel colors, mirroring the design mockup. Pulled from the theme-aware
// MaskanColors palette so they adapt to dark mode.
@Composable
private fun presetColor(id: String): Color = when (id) {
    "general" -> MaterialTheme.maskanColors.periwinkle
    "arabic_coach" -> MaterialTheme.maskanColors.paleYellow
    "en_to_ar" -> MaterialTheme.maskanColors.mintGreen
    "ar_to_en" -> MaterialTheme.maskanColors.aqua
    "en_to_th" -> MaterialTheme.maskanColors.softLavender
    "th_to_en" -> MaterialTheme.maskanColors.warmSand
    "classical_arabic" -> MaterialTheme.maskanColors.palePink
    "code_reviewer" -> MaterialTheme.maskanColors.mintGreen
    "email_drafter" -> MaterialTheme.maskanColors.paleYellow
    "summarizer" -> MaterialTheme.maskanColors.skyBlue
    "brainstorm" -> MaterialTheme.maskanColors.warmPeach
    "tutor" -> MaterialTheme.maskanColors.softLavender
    "concise_expert" -> MaterialTheme.maskanColors.aqua
    "custom" -> MaterialTheme.maskanColors.softCoral
    else -> MaterialTheme.maskanColors.skyBlue
}
