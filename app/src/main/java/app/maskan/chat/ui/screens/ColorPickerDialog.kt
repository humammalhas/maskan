package app.maskan.chat.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.maskan.chat.R

@Composable
internal fun ColorPickerDialog(
    currentHex: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var selectedColor by remember { mutableStateOf(colorFromHex(currentHex)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_color)) },
        text = {
            PastelColorRow(selected = selectedColor, onSelect = { selectedColor = it })
        },
        confirmButton = {
            TextButton(onClick = { onPick(colorToHex(selectedColor)) }) {
                Text(stringResource(R.string.save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        }
    )
}
