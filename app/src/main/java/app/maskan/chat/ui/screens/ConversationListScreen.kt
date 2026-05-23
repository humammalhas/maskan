package app.maskan.chat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.ui.theme.MintGreen
import app.maskan.chat.ui.theme.PalePink
import app.maskan.chat.ui.theme.SkyBlue
import app.maskan.chat.ui.theme.SoftCoral
import app.maskan.chat.ui.theme.SoftLavender
import app.maskan.chat.ui.theme.WarmPeach
import app.maskan.chat.ui.theme.WarmSand
import app.maskan.chat.ui.viewmodel.ConversationListViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FOLDER_PASTELS = listOf(
    WarmPeach, MintGreen, SoftLavender, PalePink, SoftCoral, WarmSand, SkyBlue
)

private fun colorFromHex(hex: String?): Color {
    if (hex == null) return FOLDER_PASTELS.first()
    return try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { FOLDER_PASTELS.first() }
}

private fun colorToHex(color: Color): String {
    val alpha = (color.alpha * 255).toInt()
    val red = (color.red * 255).toInt()
    val green = (color.green * 255).toInt()
    val blue = (color.blue * 255).toInt()
    return String.format("#%02X%02X%02X%02X", alpha, red, green, blue)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onNavigateToChat: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToRecolor by remember { mutableStateOf<FolderEntity?>(null) }
    var conversationToMove by remember { mutableStateOf<ConversationEntity?>(null) }
    val expandedFolders = remember { mutableStateMapOf<Long?, Boolean>() }

    val folders = uiState.folders
    val conversations = uiState.conversations
    val conversationsByFolder = conversations.groupBy { it.folderId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversation_list_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WarmPeach),
                actions = {
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(R.string.new_folder)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            val isEmpty = conversations.isEmpty() && folders.isEmpty()
            val infiniteTransition = rememberInfiniteTransition(label = "fab_bounce")
            val fabOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = if (isEmpty) -8f else 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "fab_bounce_offset"
            )
            FloatingActionButton(
                onClick = { viewModel.createNewConversation { id -> onNavigateToChat(id) } },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(y = fabOffset.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_conversation_desc))
            }
        }
    ) { paddingValues ->
        if (conversations.isEmpty() && folders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChatBubble,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.empty_conversations),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.empty_conversations_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.empty_conversations_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Unfiled conversations
                val unfiled = conversationsByFolder[null] ?: emptyList()
                val unfiledExpanded = expandedFolders.getOrElse(null) { true }

                item(key = "header_unfiled") {
                    FolderHeader(
                        name = stringResource(R.string.unfiled),
                        color = null,
                        expanded = unfiledExpanded,
                        onToggle = { expandedFolders[null] = !unfiledExpanded },
                        onLongClick = {}
                    )
                }

                if (unfiledExpanded) {
                    if (unfiled.isEmpty()) {
                        item(key = "empty_unfiled") {
                            EmptyFolderHint()
                        }
                    } else {
                        items(items = unfiled, key = { it.id }) { conversation ->
                            ConversationCard(
                                conversation = conversation,
                                onClick = { onNavigateToChat(conversation.id) },
                                onDelete = { viewModel.deleteConversation(conversation.id) },
                                onMoveToFolder = { conversationToMove = conversation }
                            )
                        }
                    }
                }

                // Each folder
                folders.forEach { folder ->
                    val folderConvs = conversationsByFolder[folder.id] ?: emptyList()
                    val folderExpanded = expandedFolders.getOrElse(folder.id) { true }

                    item(key = "header_${folder.id}") {
                        var showMenu by remember { mutableStateOf(false) }

                        Box {
                            FolderHeader(
                                name = folder.name,
                                color = colorFromHex(folder.colorHex),
                                expanded = folderExpanded,
                                onToggle = { expandedFolders[folder.id] = !folderExpanded },
                                onLongClick = { showMenu = true }
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename_folder)) },
                                    onClick = { showMenu = false; folderToRename = folder }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.change_color)) },
                                    onClick = { showMenu = false; folderToRecolor = folder }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_folder)) },
                                    onClick = { showMenu = false; folderToDelete = folder }
                                )
                            }
                        }
                    }

                    if (folderExpanded) {
                        if (folderConvs.isEmpty()) {
                            item(key = "empty_${folder.id}") {
                                EmptyFolderHint()
                            }
                        } else {
                            items(items = folderConvs, key = { it.id }) { conversation ->
                                ConversationCard(
                                    conversation = conversation,
                                    onClick = { onNavigateToChat(conversation.id) },
                                    onDelete = { viewModel.deleteConversation(conversation.id) },
                                    onMoveToFolder = { conversationToMove = conversation }
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // ── Dialogs & Sheets ──────────────────────────────────────────────

    if (showNewFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreate = { name, colorHex ->
                viewModel.createFolder(name, colorHex)
                showNewFolderDialog = false
            }
        )
    }

    folderToRename?.let { folder ->
        RenameFolderDialog(
            currentName = folder.name,
            onDismiss = { folderToRename = null },
            onRename = { newName ->
                viewModel.renameFolder(folder.id, newName)
                folderToRename = null
            }
        )
    }

    folderToDelete?.let { folder ->
        val count = conversations.count { it.folderId == folder.id }
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.delete_folder_title)) },
            text = {
                Text(stringResource(R.string.delete_folder_confirm_detail, folder.name, count))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    folderToDelete = null
                }) {
                    Text(
                        stringResource(R.string.delete_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    folderToRecolor?.let { folder ->
        ColorPickerDialog(
            currentHex = folder.colorHex,
            onDismiss = { folderToRecolor = null },
            onPick = { hex ->
                viewModel.updateFolderColor(folder.id, hex)
                folderToRecolor = null
            }
        )
    }

    conversationToMove?.let { conversation ->
        MoveToFolderSheet(
            folders = folders,
            currentFolderId = conversation.folderId,
            onDismiss = { conversationToMove = null },
            onSelectFolder = { folderId ->
                viewModel.moveConversationToFolder(conversation.id, folderId)
                conversationToMove = null
            },
            onCreateFolder = { name, colorHex ->
                viewModel.createFolder(name, colorHex)
            }
        )
    }
}

// ── Folder Header ─────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(
    name: String,
    color: Color?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown
            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
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
    }
}

@Composable
private fun EmptyFolderHint() {
    Text(
        text = stringResource(R.string.empty_folder),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp)
    )
}

// ── Conversation Card ─────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatDate(conversation.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        conversation.systemPromptId?.let { presetId ->
                            if (presetId != "custom") {
                                val preset = Presets.getById(presetId)
                                preset?.let {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = SkyBlue,
                                        tonalElevation = 0.dp
                                    ) {
                                        Text(
                                            text = it.localizedName(),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_button),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_to_folder)) },
                onClick = { showContextMenu = false; onMoveToFolder() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_button)) },
                onClick = { showContextMenu = false; showDeleteDialog = true }
            )
        }
    }
}

// ── Create Folder Dialog ──────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateFolderDialog(
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

// ── Rename Folder Dialog ──────────────────────────────────────────────

@Composable
private fun RenameFolderDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.save_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        }
    )
}

// ── Color Picker Dialog ───────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
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

// ── Pastel Color Row ──────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PastelColorRow(selected: Color, onSelect: (Color) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FOLDER_PASTELS.forEach { color ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (color == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .clickable { onSelect(color) }
            )
        }
    }
}

// ── Move to Folder Bottom Sheet ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoveToFolderSheet(
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
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
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

@Composable
private fun FolderOptionRow(
    name: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (color != null) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineCreateFolder(
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

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
