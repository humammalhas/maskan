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

/**
 * The size and length choices for a video, each carrying its cost. A user who picks 15 s at
 * the sharp size without being told it is half an hour will think the app hung, so every chip
 * says how long THIS combination takes - the size chips re-cost when the length changes and
 * the length chips re-cost when the size changes.
 *
 * Two short rows rather than one long one; each scrolls sideways if the language runs long.
 */
@Composable
internal fun VideoOptionChips(
    size: String,
    seconds: Int,
    onSize: (String) -> Unit,
    onSeconds: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VideoOptions.SIZES.forEach { option ->
                val minutes = VideoOptions.minutesFor(option.id, seconds)
                FilterChip(
                    selected = option.id == size,
                    onClick = { onSize(option.id) },
                    label = {
                        Text(
                            text = stringResource(option.labelRes) + " · " +
                                stringResource(R.string.video_cost_minutes_fmt, minutes),
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
            VideoOptions.LENGTHS.forEach { length ->
                val minutes = VideoOptions.minutesFor(size, length)
                FilterChip(
                    selected = length == seconds,
                    onClick = { onSeconds(length) },
                    label = {
                        Text(
                            text = stringResource(R.string.video_length_seconds_fmt, length) + " · " +
                                stringResource(R.string.video_cost_minutes_fmt, minutes),
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
