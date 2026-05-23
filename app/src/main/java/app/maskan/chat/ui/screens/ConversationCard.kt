package app.maskan.chat.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.ui.theme.maskanColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationCard(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatDate(conversation.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        conversation.systemPromptId?.let { presetId ->
                            if (presetId != "custom") {
                                val preset = Presets.getById(presetId)
                                preset?.let {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.maskanColors.skyBlue,
                                        tonalElevation = 0.dp
                                    ) {
                                        Text(
                                            text = it.localizedName(),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_button),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_to_folder)) },
                onClick = { showContextMenu = false; onMoveToFolder() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_button)) },
                onClick = { showContextMenu = false; showDeleteDialog = true }
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
