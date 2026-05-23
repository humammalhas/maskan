package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
internal fun InlineCreateFolder(
    onCancel: () -> Unit,
    onCreate: (name: String, colorHex: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(FOLDER_PASTELS.random()) }

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.folder_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        PastelColorRow(selected = selectedColor, onSelect = { selectedColor = it })
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_button)) }
            TextButton(
                onClick = { onCreate(name.trim(), colorToHex(selectedColor)) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.create_button)) }
        }
    }
}
