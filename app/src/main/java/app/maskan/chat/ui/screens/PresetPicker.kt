package app.maskan.chat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maskan.chat.R
import app.maskan.chat.data.local.PresetCategory
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                text = stringResource(R.string.preset_picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 4.dp)
            )
        }

        items(items = presets, key = { it.id }) { preset ->
            PresetCard(
                preset = preset,
                onClick = {
                    if (preset.id == "en_to_ar") {
                        showDialectSheet = true
                    } else {
                        onPresetSelected(preset, null)
                    }
                }
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: SystemPromptPreset,
    onClick: () -> Unit
) {
    val name = preset.localizedName()
    val description = preset.localizedDescription()
    val cardColor = categoryColor(preset.category)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${preset.icon}  $name",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
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
                        .clickable { onDialectSelected(dialect) },
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

@Composable
private fun categoryColor(category: PresetCategory) = when (category) {
    PresetCategory.ARABIC_SPECIFIC -> MaterialTheme.maskanColors.warmSand
    PresetCategory.TRANSLATION -> MaterialTheme.maskanColors.skyBlue
    PresetCategory.CODE -> MaterialTheme.maskanColors.mintGreen
    PresetCategory.WRITING -> MaterialTheme.maskanColors.palePink
    PresetCategory.CONVERSATION -> MaterialTheme.maskanColors.softLavender
}
