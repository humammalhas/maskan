package app.maskan.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.maskan.chat.R
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.remote.providers.ProviderOrder
import app.maskan.chat.data.remote.providers.ProviderRegistry

/**
 * Put the providers you use at the top and hide the rest (2.7, plan item 1 as Humam re-cut it).
 *
 * Buttons you can see - arrows and Hide - rather than a long press or a drag: a gesture nobody is
 * told about is how "delete a key" came to need an email, and arrows need no library, mirror in
 * Arabic and are reachable with a screen reader. Hidden providers fold into one counted group at
 * the bottom, each with Show.
 */
@Composable
fun ManageProvidersDialog(
    arrangement: ProviderOrder.Arranged,
    /** The provider in use: new chats open on it, so it cannot be hidden. */
    inUseProviderId: String,
    onMove: (String, Int) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isArabic = isAppArabic()
    fun nameOf(id: String): String {
        val provider = ProviderRegistry.getProvider(id) ?: return id
        return if (isArabic) provider.nameAr else provider.displayName
    }
    var hiddenOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Wider than an AlertDialog's default: at that width two arrows and Hide left a long name
        // ("Custom (OpenAI-compatible)") breaking in the middle of a word.
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.manage_providers_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.manage_providers_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val visible = arrangement.visible
                visible.forEachIndexed { index, id ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = nameOf(id),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onMove(id, -1) }, enabled = index > 0) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.provider_move_up)
                            )
                        }
                        IconButton(onClick = { onMove(id, 1) }, enabled = index < visible.lastIndex) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.provider_move_down)
                            )
                        }
                        if (id == inUseProviderId) {
                            Text(
                                text = stringResource(R.string.provider_in_use),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        } else {
                            TextButton(onClick = { onSetHidden(id, true) }) {
                                Text(stringResource(R.string.provider_hide))
                            }
                        }
                    }
                }

                if (arrangement.hidden.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { hiddenOpen = !hiddenOpen }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.providers_hidden_fmt, arrangement.hidden.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (hiddenOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    if (hiddenOpen) {
                        arrangement.hidden.forEach { id ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = nameOf(id),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onSetHidden(id, false) }) {
                                    Text(stringResource(R.string.provider_unhide))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.compose_done))
            }
        }
    )
}
