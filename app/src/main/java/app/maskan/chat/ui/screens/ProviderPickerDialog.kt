package app.maskan.chat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.ui.viewmodel.ChatViewModel

/**
 * Step one of the chat header's Change: which provider answers this chat from now on.
 *
 * Every provider is listed, including the ones that cannot answer yet (Humam, plan item 10): a
 * provider with no key or an on-device model not yet downloaded is tagged as such, and choosing
 * it is allowed - the send then says plainly what is missing. Hiding them would leave the user
 * wondering where a provider went, which is the discoverability failure this item exists to fix.
 */
@Composable
fun ProviderPickerDialog(
    choices: List<ChatViewModel.ProviderChoice>,
    currentProviderId: String,
    onSelect: (ChatViewModel.ProviderChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val isArabic = isAppArabic()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    val provider = choice.provider
                    val isCurrent = provider.id == currentProviderId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(choice) }
                            .padding(vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isArabic) provider.nameAr else provider.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                            val tag = when {
                                choice.missingKey -> stringResource(R.string.provider_tag_no_key)
                                choice.notDownloaded -> stringResource(R.string.provider_tag_not_downloaded)
                                else -> null
                            }
                            if (tag != null) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.model_active),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}
