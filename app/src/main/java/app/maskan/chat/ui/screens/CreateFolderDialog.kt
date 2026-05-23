package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
    onCreate: (name: String, colorHex: String?) -> Unit
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
