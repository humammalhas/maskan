package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.maskan.chat.R

@Composable
internal fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, colorHex: String?) -> Unit,
    // Creates the folder and opens its Instructions. Null where the caller has nowhere to go
    // (the move-to-folder sheet), and then the dialog is exactly what it was before 2.7.
    onCreateWithInstructions: ((name: String, colorHex: String?) -> Unit)? = null
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(FOLDER_PASTELS.random()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_folder)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.folder_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                PastelColorRow(selected = selectedColor, onSelect = { selectedColor = it })
                if (onCreateWithInstructions != null) {
                    // Instructions and memory are what make a folder a project, and before this
                    // line the only way to them was a long press nothing mentioned.
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.new_folder_project_line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Under the line it acts on, not beside Create: three labels do not fit one
                    // button row even in English, and AlertDialog wraps them with Create alone
                    // on top. Here the button row stays exactly what it was.
                    TextButton(
                        onClick = { onCreateWithInstructions(name.trim(), colorToHex(selectedColor)) },
                        enabled = name.isNotBlank()
                    ) { Text(stringResource(R.string.new_folder_add_instructions)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), colorToHex(selectedColor)) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.create_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        }
    )
}
