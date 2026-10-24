package app.maskan.chat.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.maskan.chat.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderHeader(
    name: String,
    color: Color?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    /**
     * What this folder's instructions and memory cost on every message sent from inside it, or 0
     * for a folder with no project files (and for the unfiled group, which has none to have).
     *
     * On the row rather than only in the editor because this is the number that explains why the
     * chats in one folder feel slower and cost more than the chats in another.
     */
    projectTokens: Int = 0,
    /**
     * How many chats this folder holds - passed ONLY when another folder shares its name.
     * Same rule as a duplicate chat title: the app does not forbid two folders called "Petra",
     * it refuses to draw them identically.
     */
    chatCount: Int? = null,
    /** Starts a chat already inside this folder. Null for the unfiled group: the + button does that. */
    onNewChat: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Everything but the + is one weighted group, so a long folder name wraps inside it
        // instead of pushing the button off the row.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(if (expanded) R.string.folder_collapse else R.string.folder_expand),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (color != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (projectTokens > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                TokenCount(tokens = projectTokens)
            }
            if (chatCount != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ChatCount(count = chatCount)
            }
        }
        if (onNewChat != null) {
            // A known single glyph, so its box may be fixed (CLAUDE.md, the Arabic rule 5).
            IconButton(onClick = onNewChat, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_chat_in_folder),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
