package app.maskan.chat.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.maskan.chat.data.model.Dialect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
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

/**
 * The three questions a photo is almost always about, under a photo waiting in the composer.
 *
 * Every chip sends immediately - there is nothing to confirm, and a person holding a phone over
 * a menu is not going to compose a sentence first. "Read this to me" differs only in what
 * happens to the ANSWER: it is spoken when it arrives.
 */
@Composable
internal fun PhotoQuestionChips(
    onAsk: (String) -> Unit,
    onReadAloud: (String) -> Unit
) {
    val translatePrompt = stringResource(R.string.camera_prompt_translate)
    val readPrompt = stringResource(R.string.camera_prompt_read_aloud)
    val whatPrompt = stringResource(R.string.camera_prompt_what_is_this)
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AssistChip(
            onClick = { onAsk(translatePrompt) },
            label = {
                Text(
                    text = stringResource(R.string.camera_chip_translate),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        )
        AssistChip(
            onClick = { onReadAloud(readPrompt) },
            label = {
                Text(
                    text = stringResource(R.string.camera_chip_read_aloud),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        )
        AssistChip(
            onClick = { onAsk(whatPrompt) },
            label = {
                Text(
                    text = stringResource(R.string.photo_question_hint),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        )
    }
}

/**
 * What to do with an attached voice note (plan item 3), the way PhotoQuestionChips offers what to do
 * with a photo. "Transcribe only" saves the transcript and asks no model anything; "Transcribe and
 * answer" sends it for a reply, told the voice may be somebody else's. Send with nothing typed is
 * "Transcribe only"; with a question it is the question, answered. Phase B adds the rewrites here.
 */
@Composable
internal fun AudioActionChips(
    enabled: Boolean,
    onTranscribeOnly: () -> Unit,
    onTranscribeAndAnswer: () -> Unit,
    /** Gemini and OpenAI chats only: the model hears the file itself (layer C). */
    canListen: Boolean = false,
    onListenAndAnswer: () -> Unit = {},
    /** "Rewrite in…" (layer B): every provider, because it is text rewriting text. */
    onRewrite: (Dialect) -> Unit = {}
) {
    var rewriteMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AssistChip(
            onClick = onTranscribeOnly,
            enabled = enabled,
            label = {
                Text(
                    text = stringResource(R.string.audio_chip_transcribe_only),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        )
        AssistChip(
            onClick = onTranscribeAndAnswer,
            enabled = enabled,
            label = {
                Text(
                    text = stringResource(R.string.audio_chip_transcribe_answer),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        )
        if (canListen) {
            AssistChip(
                onClick = onListenAndAnswer,
                enabled = enabled,
                label = {
                    Text(
                        text = stringResource(R.string.audio_chip_listen_answer),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
        }
        Box {
            AssistChip(
                onClick = { rewriteMenu = true },
                enabled = enabled,
                label = {
                    Text(
                        text = stringResource(R.string.audio_chip_rewrite),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
            DropdownMenu(expanded = rewriteMenu, onDismissRequest = { rewriteMenu = false }) {
                Dialect.entries.forEach { dialect ->
                    DropdownMenuItem(
                        text = { Text(stringResource(dialectMenuName(dialect))) },
                        onClick = {
                            rewriteMenu = false
                            onRewrite(dialect)
                        }
                    )
                }
            }
        }
    }
}

/** A dialect as the "Rewrite in…" menu lists it. */
internal fun dialectMenuName(dialect: Dialect): Int = when (dialect) {
    Dialect.MSA -> R.string.rewrite_menu_msa
    Dialect.LEVANTINE -> R.string.rewrite_menu_levantine
    Dialect.EGYPTIAN -> R.string.rewrite_menu_egyptian
    Dialect.GULF -> R.string.rewrite_menu_gulf
    Dialect.MAGHREBI -> R.string.rewrite_menu_maghrebi
    Dialect.ALGERIAN -> R.string.rewrite_menu_algerian
}

/** A dialect as the visible rewrite request names it ("Rewrite this voice note in %1$s…"). */
internal fun dialectPromptName(dialect: Dialect): Int = when (dialect) {
    Dialect.MSA -> R.string.rewrite_name_msa
    Dialect.LEVANTINE -> R.string.rewrite_name_levantine
    Dialect.EGYPTIAN -> R.string.rewrite_name_egyptian
    Dialect.GULF -> R.string.rewrite_name_gulf
    Dialect.MAGHREBI -> R.string.rewrite_name_maghrebi
    Dialect.ALGERIAN -> R.string.rewrite_name_algerian
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
