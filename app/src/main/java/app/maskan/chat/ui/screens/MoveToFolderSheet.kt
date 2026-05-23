package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.FolderEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveToFolderSheet(
    folders: List<FolderEntity>,
    currentFolderId: Long?,
    onDismiss: () -> Unit,
    onSelectFolder: (Long?) -> Unit,
    onCreateFolder: (name: String, colorHex: String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showInlineCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.move_to_folder),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Unfiled option
            FolderOptionRow(
                name = stringResource(R.string.unfiled),
                color = null,
                selected = currentFolderId == null,
                onClick = { onSelectFolder(null) }
            )

            folders.forEach { folder ->
                FolderOptionRow(
                    name = folder.name,
                    color = colorFromHex(folder.colorHex),
                    selected = currentFolderId == folder.id,
                    onClick = { onSelectFolder(folder.id) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!showInlineCreate) {
                TextButton(onClick = { showInlineCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.create_new_folder), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.create_new_folder))
                }
            } else {
                InlineCreateFolder(
                    onCancel = { showInlineCreate = false },
                    onCreate = { name, hex ->
                        onCreateFolder(name, hex)
                        showInlineCreate = false
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
