package app.maskan.chat.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.maskan.chat.R

@Composable
internal fun EmptyFolderHint() {
    Text(
        text = stringResource(R.string.empty_folder),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp)
    )
}
