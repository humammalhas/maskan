package app.maskan.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.maskan.chat.R

/**
 * Full-height model picker.
 *
 * A search field inside the scrolling Settings page cannot work: once the keyboard opens it
 * covers the whole Model section, so the user types and sees nothing change. A dialog keeps the
 * query at the top and the results in a list above the keyboard, which is also the only sane way
 * to browse the 200-400 model catalogues the gateways return.
 */
@Composable
fun ModelPickerDialog(
    models: List<String>,
    visionModels: Set<String>,
    verifiedModels: Set<String>,
    freeModels: Set<String>,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Allow committing a typed id that is not in the list. See the comment at its use. */
    allowCustom: Boolean = false,
    /** Offer an explicit None row that clears the selection. */
    allowNone: Boolean = false,
    /** Models that DRAW. Tagged so the list says what a model is for, not just that it exists. */
    imageModels: Set<String> = emptySet(),
    /**
     * Models this key has been refused (403/404), listed greyed at the BOTTOM with the
     * provider's own reason. Hidden entirely they left the user wondering where a model went;
     * at the top they would bury the ones that work.
     */
    unavailableModels: List<String> = emptyList(),
    unavailableReasons: Map<String, String> = emptyMap()
) {
    var query by remember { mutableStateOf("") }
    // Proven-working models first, then free ones - the two questions users actually bring to
    // this list - then everything else alphabetically.
    val ordered = remember(models, verifiedModels, freeModels) {
        models.sortedWith(
            compareBy({ it !in verifiedModels }, { it !in freeModels }, { it.lowercase() })
        )
    }
    val filtered = remember(query, ordered) {
        if (query.isBlank()) ordered
        else ordered.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val filteredUnavailable = remember(query, unavailableModels) {
        if (query.isBlank()) unavailableModels
        else unavailableModels.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.model_picker_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.model_search_label)) },
                    placeholder = { Text(stringResource(R.string.model_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.model_picker_count_fmt, filtered.size, models.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (visionModels.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.model_vision_legend),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (filtered.isEmpty() && filteredUnavailable.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.model_search_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                    // "None" belongs in the list, not as a separate button beside it: choosing
                    // no image model is a choice like any other, and it is what hides the draw
                    // button for someone who only wants to chat.
                    if (allowNone && query.isBlank()) {
                        item {
                            TextButton(onClick = { onSelect("") }) {
                                Text(stringResource(R.string.image_model_none_option))
                            }
                        }
                    }
                    val typed = query.trim()
                    // A provider catalogue is not a promise of completeness: Together serves
                    // black-forest-labs/FLUX.1-schnell while omitting it from /v1/models.
                    // Without this the picker would stand between the user and a model that
                    // works. Off for chat models, where the list IS reliable.
                    if (allowCustom && typed.isNotBlank() && filtered.none { it == typed }) {
                        item {
                            TextButton(onClick = { onSelect(typed) }) {
                                Text(stringResource(R.string.model_use_typed_fmt, typed))
                            }
                        }
                    }
                    items(filtered) { model ->
                        val isActive = model.trim() == selectedModel.trim()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(model) }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                )
                                // Say what is known about this model instead of leaving the user
                                // to discover it by chatting: whether it has answered with this
                                // key, and whether it takes images. Emoji rather than icons keeps
                                // the extended icon library out of the APK.
                                val tags = buildList {
                                    if (model in verifiedModels) {
                                        add(stringResource(R.string.model_tag_tested))
                                    }
                                    if (model in visionModels) {
                                        add(stringResource(R.string.model_tag_images))
                                    }
                                    if (model in imageModels) {
                                        add(stringResource(R.string.model_tag_generates_images))
                                    }
                                    if (model in freeModels) {
                                        add(stringResource(R.string.model_tag_free))
                                    }
                                }
                                if (tags.isNotEmpty()) {
                                    Text(
                                        text = tags.joinToString("  ·  "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.model_active),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    if (filteredUnavailable.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "\uD83D\uDD12  " + stringResource(R.string.model_group_unavailable),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(filteredUnavailable) { model ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                unavailableReasons[model]?.let { reason ->
                                    Text(
                                        text = reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        }
    }
}
