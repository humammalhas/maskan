package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import app.maskan.chat.ondevice.DownloadState
import app.maskan.chat.ondevice.Formats
import app.maskan.chat.ondevice.ModelCatalog
import app.maskan.chat.ondevice.ModelDownloadWorker
import kotlinx.coroutines.launch

/**
 * The download card: the whole of the on-device provider's setup, and the first of the three
 * places the model is named.
 *
 * It is the onboarding for someone with no API key, so it has to be honest in both directions
 * at once - this is free, private and works offline, AND it is a small model that is not as good
 * as the one you would get with a key. Both sentences are on the card, and the second one is not
 * in smaller type than the first.
 *
 * Three things here are deliberate and easy to get wrong:
 *
 *  - **Delete states the megabytes it returns**, on a button next to the download rather than
 *    hidden in a menu. Someone who downloads 1.6 GB and cannot find how to remove it is the
 *    opposite of what this app is for. The number is measured off the file, not the catalogue.
 *  - **A phone with too little memory is refused with a sentence**, not a greyed-out button. A
 *    disabled control with no explanation is the app telling someone they did something wrong.
 *  - **Wi-Fi by default with an explicit switch for mobile data.** 1.6 GB on a mobile bundle is
 *    real money.
 */
@Composable
fun OnDeviceCard(onAddKeyInstead: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MaskanApplication
    val model = ModelCatalog.DEFAULT
    val downloads = app.modelDownloads
    val store = downloads.store

    val state by remember(model.id) { downloads.state(model) }
        .collectAsState(initial = DownloadState.Idle)

    val scope = rememberCoroutineScope()
    var allowMetered by remember { mutableStateOf(false) }
    var deletedBytes by remember { mutableStateOf<Long?>(null) }

    val hasRam = remember { store.hasEnoughRam(model) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ondevice_card_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            // The model, the maker and the licence, together, every time it is named.
            Text(
                text = stringResource(
                    R.string.ondevice_model_by,
                    model.displayName,
                    model.maker,
                    model.licence
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            // Disclosure 1 of 3. Same size as everything else on the card, on purpose.
            Text(
                text = stringResource(R.string.ondevice_disclosure_card),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = if (state is DownloadState.Installed) {
                    stringResource(R.string.ondevice_status_ready, model.displayName)
                } else {
                    stringResource(
                        R.string.ondevice_status_missing,
                        Formats.bytes(context, model.bytes)
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (!hasRam) {
                // Refused, with the reason and both numbers, and a way forward that is not
                // "buy a new phone".
                Text(
                    text = stringResource(
                        R.string.ondevice_ram_refused,
                        Formats.bytes(context, store.deviceRamBytes()),
                        // The class, as printed on a phone's box - what the gate enforces.
                        context.getString(R.string.unit_gb, model.minRamGb.toString())
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAddKeyInstead) {
                    Text(stringResource(R.string.ondevice_nudge_add_key))
                }
                return@Column
            }

            when (val s = state) {
                is DownloadState.Installed -> {
                    OutlinedButton(
                        onClick = {
                            // Suspending, because it waits for the engine to let the file go
                            // before unlinking it - see ModelDownloadManager.delete.
                            scope.launch { deletedBytes = downloads.delete(model) }
                        }
                    ) {
                        Text(
                            stringResource(
                                R.string.ondevice_delete_button,
                                Formats.bytes(context, store.fileFor(model).length())
                            )
                        )
                    }
                }

                is DownloadState.Running -> {
                    Text(
                        text = if (s.verifying) {
                            stringResource(R.string.ondevice_verifying)
                        } else {
                            stringResource(
                                R.string.ondevice_downloading_progress,
                                Formats.bytes(context, s.done),
                                Formats.bytes(context, s.total)
                            )
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.verifying) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = {
                                if (s.total > 0) (s.done.toFloat() / s.total) else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { downloads.cancel(model) }) {
                        Text(stringResource(R.string.ondevice_cancel))
                    }
                }

                is DownloadState.Queued -> {
                    Text(
                        text = if (s.retrying) {
                            stringResource(R.string.ondevice_retrying)
                        } else {
                            stringResource(R.string.ondevice_queued)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { downloads.cancel(model) }) {
                        Text(stringResource(R.string.ondevice_cancel))
                    }
                }

                else -> {
                    if (s is DownloadState.Failed) {
                        Text(
                            text = when (s.reason) {
                                ModelDownloadWorker.REASON_CHECKSUM ->
                                    stringResource(R.string.ondevice_checksum_failed)
                                ModelDownloadWorker.REASON_NO_SPACE ->
                                    stringResource(
                                        R.string.ondevice_no_space,
                                        Formats.bytes(context, model.bytes)
                                    )
                                ModelDownloadWorker.REASON_HTTP ->
                                    stringResource(
                                        R.string.ondevice_server_refused,
                                        s.httpCode.toString()
                                    )
                                else -> stringResource(R.string.error_unknown)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = allowMetered,
                            onCheckedChange = { allowMetered = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (allowMetered) {
                                stringResource(R.string.ondevice_allow_mobile)
                            } else {
                                stringResource(R.string.ondevice_wifi_only)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { downloads.start(model, allowMetered) }) {
                        Text(
                            stringResource(
                                R.string.ondevice_download_button,
                                Formats.bytes(context, model.bytes)
                            )
                        )
                    }
                }
            }

            deletedBytes?.let { freed ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.ondevice_deleted,
                        Formats.bytes(context, freed)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
