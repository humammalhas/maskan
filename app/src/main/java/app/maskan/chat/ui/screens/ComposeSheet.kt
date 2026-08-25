package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.maskan.chat.R

/**
 * A full-height sheet for writing a long message.
 *
 * The composer line is one row tall and got tighter still once the draw button joined the
 * paperclip, so anything longer than a sentence is written through a keyhole. Same answer the
 * model search needed: give it its own surface above the keyboard, with imePadding() so the
 * keyboard never covers the text being typed.
 *
 * The dialog owns a draft copy and hands it back on Done, so backing out cannot lose or
 * half-apply what was typed.
 */
@Composable
fun ComposeSheet(
    initialText: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember {
        mutableStateOf(
            TextFieldValue(text = initialText, selection = androidx.compose.ui.text.TextRange(initialText.length))
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.compose_title),
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.message_placeholder)) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onDismiss() }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                    TextButton(onClick = { onDone(draft.text) }) {
                        Text(stringResource(R.string.compose_done))
                    }
                }
            }
        }
    }
}