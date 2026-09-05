package app.maskan.chat.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.video.VideoOptions
import java.util.Locale

/**
 * The size and length choices for a video, each carrying its cost. A user who picks 15 s at
 * the sharp size without being told it is half an hour will think the app hung, so every chip
 * says what THIS combination costs - minutes on the user's own server, dollars on a cloud
 * provider - and the size chips re-cost when the length changes and vice versa.
 *
 * Two short rows rather than one long one; each scrolls sideways if the language runs long.
 */
@Composable
internal fun VideoOptionChips(
    providerId: String,
    model: String,
    size: String,
    seconds: Int,
    onSize: (String) -> Unit,
    onSeconds: (Int) -> Unit
) {
    val cloud = VideoOptions.isCloud(providerId)

    @Composable
    fun cost(forSize: String, forSeconds: Int): String =
        if (cloud) {
            VideoOptions.dollarsFor(providerId, model, forSeconds)?.let { dollars ->
                " · " + stringResource(R.string.video_cost_usd_fmt, String.format(Locale.US, "%.2f", dollars))
            } ?: ""
        } else {
            " · " + stringResource(R.string.video_cost_minutes_fmt, VideoOptions.minutesFor(forSize, forSeconds))
        }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VideoOptions.sizesFor(providerId).forEach { option ->
                FilterChip(
                    selected = option.id == size,
                    onClick = { onSize(option.id) },
                    label = {
                        Text(
                            text = stringResource(option.labelRes) + cost(option.id, seconds),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VideoOptions.lengthsFor(providerId).forEach { length ->
                FilterChip(
                    selected = length == seconds,
                    onClick = { onSeconds(length) },
                    label = {
                        Text(
                            text = stringResource(R.string.video_length_seconds_fmt, length) + cost(size, length),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }
    }
}

/** Image shape: square, wide, tall. Sent as `size`; the local server honours it on every model. */
@Composable
internal fun ImageSizeChips(size: String, onSize: (String) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        VideoOptions.IMAGE_SIZES.forEach { option ->
            FilterChip(
                selected = option.id == size,
                onClick = { onSize(option.id) },
                label = {
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
        }
    }
}
