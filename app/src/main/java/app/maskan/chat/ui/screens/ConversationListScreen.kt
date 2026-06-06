package app.maskan.chat.ui.screens

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.maskan.chat.R
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.ui.theme.MintGreen
import app.maskan.chat.ui.theme.PalePink
import app.maskan.chat.ui.theme.SkyBlue
import app.maskan.chat.ui.theme.SoftCoral
import app.maskan.chat.ui.theme.SoftLavender
import app.maskan.chat.ui.theme.WarmPeach
import app.maskan.chat.ui.theme.WarmSand
import app.maskan.chat.ui.theme.maskanColors
import app.maskan.chat.ui.viewmodel.ConversationListViewModel

internal val FOLDER_PASTELS = listOf(
    WarmPeach, MintGreen, SoftLavender, PalePink, SoftCoral, WarmSand, SkyBlue
)

private val PASTEL_COLOR_NAME_RES = mapOf(
    WarmPeach to R.string.color_peach,
    MintGreen to R.string.color_mint,
    SoftLavender to R.string.color_lavender,
    PalePink to R.string.color_pink,
    SoftCoral to R.string.color_coral,
    WarmSand to R.string.color_sand,
    SkyBlue to R.string.color_sky
)

internal fun colorFromHex(hex: String?): Color {
    if (hex == null) return FOLDER_PASTELS.first()
    return try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { FOLDER_PASTELS.first() }
}

internal fun colorToHex(color: Color): String {
    val alpha = (color.alpha * 255).toInt()
    val red = (color.red * 255).toInt()
    val green = (color.green * 255).toInt()
    val blue = (color.blue * 255).toInt()
    return String.format("#%02X%02X%02X%02X", alpha, red, green, blue)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PastelColorRow(selected: Color, onSelect: (Color) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FOLDER_PASTELS.forEach { color ->
            val colorName = stringResource(PASTEL_COLOR_NAME_RES[color] ?: R.string.color_generic)
            val swatchDesc = stringResource(R.string.color_swatch_desc, colorName)
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (color == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .clickable { onSelect(color) }
                    .semantics { contentDescription = swatchDesc }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onNavigateToChat: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    var showNewFolderDialog by rememberSaveable { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var folderToRecolor by remember { mutableStateOf<FolderEntity?>(null) }
    var conversationToMove by remember { mutableStateOf<ConversationEntity?>(null) }
    val expandedFolders = remember { mutableStateMapOf<Long?, Boolean>() }

    val folders = uiState.folders
    val conversations = uiState.conversations
    val conversationsByFolder = conversations.groupBy { it.folderId }
    val folderToDelete = folderToDeleteId?.let { id -> folders.find { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    } else {
                        Text(stringResource(R.string.conversation_list_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.maskanColors.warmPeach),
                actions = {
                    if (isSearchActive) {
                        IconButton(onClick = { viewModel.clearSearch() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel_button)
                            )
                        }
                    } else {
                        IconButton(onClick = { viewModel.activateSearch() }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_placeholder)
                            )
                        }
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
        if (isSearchActive && searchQuery.isNotBlank()) {
            if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.search_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = searchResults, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onClick = { onNavigateToChat(conversation.id) },
                            onDelete = { viewModel.deleteConversation(conversation.id) },
                            onMoveToFolder = { conversationToMove = conversation }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        } else if (conversations.isEmpty() && folders.isEmpty()) {
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
                                    onClick = { showMenu = false; folderToDeleteId = folder.id }
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
            onDismissRequest = { folderToDeleteId = null },
            title = { Text(stringResource(R.string.delete_folder_title)) },
            text = {
                Text(stringResource(R.string.delete_folder_confirm_detail, folder.name, count))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    folderToDeleteId = null
                }) {
                    Text(
                        stringResource(R.string.delete_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDeleteId = null }) {
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
