package app.maskan.chat.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.maskan.chat.video.VideoProgress
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.ui.theme.maskanColors
import app.maskan.chat.data.repository.ExportFormat
import app.maskan.chat.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    conversationId: Long,
    preferenceRepository: PreferenceRepository,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showCustomPromptDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val app = context.applicationContext as MaskanApplication
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        // Readiness depends ONLY on the init status — never on dereferencing the engine inside
        // the callback (that callback can fire before the engine variable is assigned, which is
        // what kept ttsReady false before). The listener is attached synchronously after
        // construction; language selection happens lazily at speak time, when tts.value is
        // guaranteed to be set.
        val engine = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                speakingMessageId = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speakingMessageId = null
            }
        })
        tts.value = engine
        onDispose {
            engine.stop()
            engine.shutdown()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.attachImage(uri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachFile(uri)
        }
    }

    // Android 13+: the render notification needs this; asked the first time a video is armed.
    // Declining costs only the notification - the render itself is unaffected.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    var showComposeSheet by remember { mutableStateOf(false) }

    // Held between arming the Save picker and the picker returning: the SAF contract hands back
    // only a destination, so the bytes have to wait somewhere.
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        // Not the stock CreateDocument: that contract fixes the mime at creation time, and a
        // local server can hand back WebP - even an ANIMATED WebP - where cloud providers send
        // PNG. Saving that with a .png name and image/png mime makes every gallery treat the
        // animation as a still, which is exactly the bug this replaces.
        SaveImageDocumentContract
    ) { uri ->
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri != null && bytes != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            } catch (_: Exception) {
                false
            }
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.image_saved else R.string.image_save_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    val visibleMessages = uiState.messages.filter { it.role != "system" }

    // The list is reverseLayout = true, so the newest message is index 0 and the list is anchored
    // to the bottom by default (this is what keeps the latest reply visible even as the keyboard
    // opens — see Google's Jetchat sample). We only need to pin to item 0 when a new message turn
    // arrives or while a reply streams in; reverseLayout holds the bottom the rest of the time.
    val lastVisible = visibleMessages.lastOrNull()
    LaunchedEffect(visibleMessages.size, lastVisible?.id, lastVisible?.content?.length, uiState.isLoading) {
        if (visibleMessages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // The rewritten prompt opens in the big writing surface, already editable: the user reads
    // what the model made of their words and changes anything before it is drawn.
    uiState.improvedPrompt?.let { improved ->
        ComposeSheet(
            initialText = improved,
            onDone = { edited ->
                inputText = edited
                viewModel.clearImprovedPrompt()
            },
            onDismiss = { viewModel.clearImprovedPrompt() }
        )
    }

    if (showComposeSheet) {
        ComposeSheet(
            initialText = inputText,
            onDone = { written ->
                inputText = written
                showComposeSheet = false
            },
            onDismiss = { showComposeSheet = false }
        )
    }

    if (showExportDialog) {
        ExportFormatDialog(
            onSelect = { format ->
                showExportDialog = false
                viewModel.exportConversation(format)
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showCustomPromptDialog) {
        CustomPromptDialog(
            onConfirm = { prompt ->
                showCustomPromptDialog = false
                viewModel.setCustomPrompt(prompt)
            },
            onDismiss = { showCustomPromptDialog = false }
        )
    }

    Scaffold(
        topBar = {
            // The preset name lives in a thin strip UNDER the bar, not inside the title slot.
            // Material's small TopAppBar sizes its title slot for ONE line; two lines only ever
            // fit by luck, and the Arabic face's tall mark-space metrics ran out of luck - the
            // subtitle rendered half-clipped. A separate strip in the same colour reads as one
            // header and gives every script the room it actually needs.
            Column {
                TopAppBar(
                    // Slimmer than the default 64dp: with the preset strip below, the pair reads
                    // as one compact header instead of a bar floating above a caption.
                    expandedHeight = 52.dp,
                    title = {
                        Text(
                            text = stringResource(R.string.chat_screen_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.maskanColors.softLavender
                    ),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back_button)
                            )
                        }
                    },
                    actions = {
                        if (visibleMessages.isNotEmpty()) {
                            IconButton(onClick = { showExportDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.export_conversation)
                                )
                            }
                        }
                    }
                )
                uiState.currentPreset?.let { preset ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.maskanColors.softLavender)
                            // 16dp bar inset + 48dp nav icon, so the name sits under the title.
                            // Start-relative, so it mirrors correctly in RTL.
                            .padding(start = 60.dp, end = 16.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = preset.localizedName(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        snackbarHost = {
            uiState.error?.let { error ->
                // The recovery action names a model id, which can be long
                // ("google/gemma-4-31b-it:free"), so it goes on its OWN line - crammed into the
                // trailing slot it squeezes the message down to a couple of words. Dismiss is a
                // TextButton in the dismissAction slot, not an IconButton holding text: an icon
                // button is a fixed 48dp box and clipped the word.
                val recoverable = uiState.recoverableModel
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    actionOnNewLine = recoverable != null,
                    action = if (recoverable != null) {
                        {
                            TextButton(onClick = { viewModel.switchModelAndRetry() }) {
                                Text(stringResource(R.string.switch_model_and_retry, recoverable))
                            }
                        }
                    } else null,
                    dismissAction = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.dismiss_button))
                        }
                    }
                ) {
                    Text(error)
                }
            }
        },
        bottomBar = {
            if (uiState.presetSelected) {
                Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                    uiState.pendingImageBytes?.let { bytes ->
                        ImagePreview(
                            imageBytes = bytes,
                            onRemove = { viewModel.clearPendingImage() }
                        )
                        if (!preferenceRepository.hasSeenImagePrivacyNote()) {
                            val providerName = ProviderRegistry.getProvider(uiState.selectedProviderId)?.let { provider ->
                                if (app.localeRepository.getLocale() == "ar") provider.nameAr else provider.displayName
                            } ?: uiState.selectedProviderId
                            PrivacyInfoNote(
                                text = stringResource(R.string.privacy_note_image, providerName),
                                onDismissForever = { preferenceRepository.setImagePrivacyNoteSeen() }
                            )
                        }
                    }
                    uiState.pendingFileName?.let { fileName ->
                        FileAttachmentChip(
                            fileName = fileName,
                            onRemove = { viewModel.clearPendingFile() }
                        )
                    }
                    if (uiState.editMode) {
                        FileAttachmentChip(
                            fileName = stringResource(R.string.edit_mode_chip),
                            onRemove = { viewModel.setEditMode(false) }
                        )
                    }
                    if (uiState.videoMode) {
                        FileAttachmentChip(
                            fileName = stringResource(
                                if (uiState.pendingImageBytes != null) R.string.video_mode_chip_photo
                                else R.string.video_mode_chip
                            ),
                            onRemove = { viewModel.setVideoMode(false) }
                        )
                        VideoOptionChips(
                            providerId = uiState.selectedProviderId,
                            model = viewModel.selectedVideoModel(),
                            size = uiState.videoSize,
                            seconds = uiState.videoSeconds,
                            onSize = { viewModel.setVideoSize(it) },
                            onSeconds = { viewModel.setVideoSeconds(it) }
                        )
                        uiState.videoQuote?.let { quote ->
                            Text(
                                text = stringResource(
                                    R.string.video_quote_fmt,
                                    String.format(java.util.Locale.US, "%.2f", quote)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (uiState.imageMode) {
                        FileAttachmentChip(
                            fileName = stringResource(R.string.image_mode_chip),
                            onRemove = { viewModel.setImageMode(false) }
                        )
                        if (viewModel.imageSizeChoiceAvailable()) {
                            ImageSizeChips(size = uiState.imageSize, onSize = { viewModel.setImageSize(it) })
                        }
                        // Image models are trained mostly on English and reward concrete visual
                        // detail. Rather than translate silently, the chat model drafts a prompt
                        // and the user edits it before anything is drawn.
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel.improvePrompt(inputText) },
                                enabled = inputText.isNotBlank() && !uiState.improvingPrompt
                            ) {
                                Text(
                                    text = stringResource(R.string.improve_prompt),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            if (uiState.improvingPrompt) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        // Said once: app-private files go away with the app, so a picture worth
                        // keeping has to be saved out. That is how Android works, not a choice
                        // this app made, and the user should hear it before the first image.
                        if (!preferenceRepository.hasSeenGeneratedImageNote()) {
                            PrivacyInfoNote(
                                text = stringResource(R.string.image_storage_note),
                                onDismissForever = { preferenceRepository.setGeneratedImageNoteSeen() }
                            )
                        }
                    }
                    MessageInputBar(
                        text = inputText,
                        onTextChange = { inputText = it },
                        onSend = {
                            if (inputText.isNotBlank() || uiState.pendingImageBytes != null || uiState.pendingFileText != null) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        onStop = { viewModel.cancelGeneration() },
                        isLoading = uiState.isLoading || uiState.isStreaming,
                        onAttachFile = { filePickerLauncher.launch(arrayOf("text/plain", "text/html")) },
                        onAttachPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        supportsVision = viewModel.currentProviderSupportsVision(),
                        hasAttachment = uiState.pendingImageBytes != null || uiState.pendingFileText != null,
                        imageFeatureAvailable = viewModel.imageFeatureAvailable(),
                        canGenerateImages = viewModel.canGenerateImages(),
                        imageMode = uiState.imageMode,
                        onToggleImageMode = { viewModel.setImageMode(!uiState.imageMode) },
                        editFeatureAvailable = viewModel.editModel() != null,
                        canEditPhoto = uiState.pendingImageBytes != null,
                        editMode = uiState.editMode,
                        onToggleEditMode = { viewModel.setEditMode(!uiState.editMode) },
                        videoFeatureAvailable = viewModel.videoFeatureAvailable(),
                        canGenerateVideos = viewModel.canGenerateVideos(),
                        videoMode = uiState.videoMode,
                        onToggleVideoMode = {
                            val arming = !uiState.videoMode
                            if (arming && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setVideoMode(arming)
                        },
                        onExpandCompose = { showComposeSheet = true },
                        preferenceRepository = preferenceRepository
                    )
                }
            }
        }
    ) { paddingValues ->
        if (uiState.isLoading && visibleMessages.isEmpty() && !uiState.presetSelected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else if (!uiState.presetSelected) {
            PresetPicker(
                defaultDialect = preferenceRepository.getDefaultDialect(),
                onPresetSelected = { preset, dialect ->
                    when (preset.id) {
                        "custom" -> showCustomPromptDialog = true
                        else -> viewModel.setPreset(preset, dialect)
                    }
                },
                modifier = Modifier.padding(paddingValues)
            )
        } else if (visibleMessages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.empty_chat),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                state = listState,
                // reverseLayout = true anchors the list to the bottom: index 0 (the newest message)
                // renders at the bottom and stays pinned there as new messages arrive and as the
                // keyboard opens. The data is fed newest-first via asReversed() to match.
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = visibleMessages.asReversed(),
                    key = { it.id }
                ) { message ->
                    val isLastMessage = message == visibleMessages.lastOrNull()
                    val isActivelyStreaming = uiState.isStreaming && isLastMessage && message.role == "assistant"
                    // Decrypt once per message, not once per recomposition.
                    val generatedImage = message.imagePath?.let { path ->
                        remember(path) { viewModel.readImage(path) }
                    }
                    MessageBubble(
                        message = message,
                        isUser = message.role == "user",
                        isStreaming = isActivelyStreaming,
                        isSpeaking = speakingMessageId == message.id,
                        generatedImage = generatedImage,
                        videoProgress = uiState.videoProgress[message.id],
                        onCancelVideo = { viewModel.cancelVideo(message.id) },
                        onRetryVideo = { viewModel.retryVideo(message.id) },
                        pendingKind = if (isLastMessage && message.role == "assistant" &&
                            message.content.isBlank() && message.imagePath == null &&
                            message.imageMimeType == null && (uiState.isLoading || uiState.isStreaming)
                        ) uiState.pendingKind else null,
                        pendingSince = uiState.pendingSince,
                        onSaveImage = {
                            generatedImage?.let { bytes ->
                                pendingSaveBytes = bytes
                                val mime = message.imageMimeType ?: "image/png"
                                saveImageLauncher.launch(
                                    "maskan-${message.id}.${extensionFor(mime)}" to mime
                                )
                            }
                        },
                        onShareImage = {
                            generatedImage?.let { bytes ->
                                shareImageBytes(context, bytes, message.imageMimeType ?: "image/png")
                            }
                        },
                        onSpeakToggle = {
                            val engine = tts.value
                            if (!ttsReady || engine == null) {
                                Toast.makeText(context, context.getString(R.string.voice_narration_unavailable), Toast.LENGTH_SHORT).show()
                            } else if (speakingMessageId == message.id) {
                                engine.stop()
                                speakingMessageId = null
                            } else {
                                val appLocale = app.localeRepository.getLocale()
                                val locale = when (appLocale) {
                                    "ar" -> Locale("ar", "SA")
                                    "th" -> Locale("th", "TH")
                                    "en" -> Locale("en", "US")
                                    else -> Locale.getDefault()
                                }
                                var langResult = engine.setLanguage(locale)
                                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                    // Fall back to en-US only for English/system content — reading
                                    // Arabic with an English voice is gibberish.
                                    if (appLocale == "en" || appLocale.isEmpty()) {
                                        langResult = engine.setLanguage(Locale.US)
                                    }
                                }
                                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                    Toast.makeText(context, context.getString(R.string.tts_language_unavailable), Toast.LENGTH_SHORT).show()
                                } else {
                                    engine.stop()
                                    speakingMessageId = message.id
                                    engine.speak(message.content, TextToSpeech.QUEUE_FLUSH, null, message.id.toString())
                                }
                            }
                        }
                    )
                }
            }

            // Draggable scrollbar (approximate, index-based) on the trailing edge
            ChatScrollbar(
                listState = listState,
                itemCount = visibleMessages.size,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 1.dp)
            )

            // "Take me to the bottom" button — only visible when scrolled up off the latest message.
            // With reverseLayout the bottom is index 0, so "at the bottom" means firstVisibleItemIndex
            // == 0 with a small offset (Jetchat's exact condition). Tapping it jumps to item 0, which
            // is always the newest message — no offset math needed.
            val showScrollToBottom by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset > 200
                }
            }
            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Surface(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ChatScrollbar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    // Approximate, index-based scrollbar: chat bubbles vary in height, so the thumb position
    // and size estimate where you are rather than being pixel-exact. Good enough as a position
    // indicator + fast-scroll handle, and needs no dependency.
    if (itemCount <= 1) return
    if (!listState.canScrollForward && !listState.canScrollBackward) return

    val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val maxFirst = (itemCount - visibleCount).coerceAtLeast(1)
    // The list is reverseLayout = true, so firstVisibleItemIndex == 0 is the BOTTOM (newest). Invert
    // the fraction so the thumb sits at the bottom of the track when you're on the latest message
    // and climbs as you scroll back through history.
    val rawFraction = (listState.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
    val scrollFraction = 1f - rawFraction
    val thumbFraction = (visibleCount.toFloat() / itemCount).coerceIn(0.1f, 0.9f)

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbHeightDp = maxHeight * thumbFraction
        val maxOffsetPx = trackPx * (1f - thumbFraction)
        val offsetPx = maxOffsetPx * scrollFraction
        Box(
            modifier = Modifier
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .width(5.dp)
                .height(thumbHeightDp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dy ->
                        // Translate thumb drag into list scroll: dragging the thumb across the
                        // track should move the content by the proportional amount.
                        if (thumbFraction > 0f) listState.dispatchRawDelta(dy / thumbFraction)
                    }
                )
        )
    }
}

@Composable
private fun ImagePreview(
    imageBytes: ByteArray,
    onRemove: () -> Unit
) {
    val bitmap = remember(imageBytes) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    if (bitmap != null) {
        Box(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.attach_image),
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.remove_image),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FileAttachmentChip(
    fileName: String,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.maskanColors.skyBlue.copy(alpha = 0.3f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            IconButton(
                onClick = onRemove
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.remove_file),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CustomPromptDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.custom_prompt_title))
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = {
                    Text(stringResource(R.string.custom_prompt_placeholder))
                },
                maxLines = 8
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    isUser: Boolean,
    isStreaming: Boolean = false,
    isSpeaking: Boolean = false,
    onSpeakToggle: () -> Unit = {},
    // Decrypted by the caller: a generated image lives as an encrypted file, and decrypting it
    // inside the bubble would redo the work on every recomposition.
    generatedImage: ByteArray? = null,
    videoProgress: VideoProgress? = null,
    onCancelVideo: () -> Unit = {},
    onRetryVideo: () -> Unit = {},
    /** "image" / "edit" while this bubble is the silent placeholder of a blocking request. */
    pendingKind: String? = null,
    pendingSince: Long = 0L,
    onSaveImage: () -> Unit = {},
    onShareImage: () -> Unit = {}
) {
    val backgroundColor = if (isUser) MaterialTheme.maskanColors.userBubble else MaterialTheme.maskanColors.assistantBubble

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(containerColor = backgroundColor)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // A video row is the same row as an image, told apart by its mime. Its
                    // bytes are an MP4, so BitmapFactory has nothing to say about them.
                    val isVideo = message.imageMimeType?.startsWith("video/") == true
                    if (isVideo) {
                        val clipPath = message.imagePath
                        if (clipPath != null && generatedImage != null) {
                            var showPlayer by remember { mutableStateOf(false) }
                            VideoThumbnail(
                                cacheKey = clipPath,
                                bytes = generatedImage,
                                onPlay = { showPlayer = true },
                                modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp)
                            )
                            if (showPlayer) {
                                VideoPlayerDialog(bytes = generatedImage, onDismiss = { showPlayer = false })
                            }
                        } else {
                            VideoStatus(
                                message = message,
                                progress = videoProgress,
                                onCancel = onCancelVideo,
                                onRetry = onRetryVideo
                            )
                        }
                    }
                    if (pendingKind != null) {
                        // A drawing or an edit is a single blocking request of a few minutes with
                        // nothing to stream. Say what is happening and for how long, so the empty
                        // bubble is never mistaken for a hang.
                        PendingRequestLine(kind = pendingKind, since = pendingSince)
                    }
                    if (!isVideo) generatedImage?.let { bytes ->
                        // Animated WebP/GIF play through ImageDecoder; anything else, or an
                        // older device, falls through to the one-frame bitmap below.
                        val animated = AnimatedImage(
                            bytes = bytes,
                            mimeType = message.imageMimeType ?: "image/png",
                            modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp)
                        )
                        val bitmap = remember(bytes) {
                            try {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (_: Exception) { null }
                        }
                        if (!animated) bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.generated_image),
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                    message.imageBase64?.let { base64 ->
                        val bitmap = remember(base64) {
                            try {
                                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (_: Exception) { null }
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .widthIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                    if (message.content.isNotBlank() && !isVideo) {
                        SelectionContainer {
                            if (!isUser && !isStreaming) {
                                MarkdownText(text = message.content)
                            } else {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                if (generatedImage != null) {
                    // App-private files vanish when the app is uninstalled, so a picture the user
                    // wants to keep has to leave the app deliberately. Save writes a plain PNG
                    // wherever they choose via the Storage Access Framework - no storage
                    // permission on any API level.
                    TextButton(onClick = onSaveImage) {
                        Text(
                            text = stringResource(R.string.save_image),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = onShareImage) {
                        Text(
                            text = stringResource(R.string.share_image),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (!isUser && message.content.isNotBlank()) {
                    IconButton(
                        onClick = onSpeakToggle
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Filled.Close else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (isSpeaking) R.string.stop_narration else R.string.play_narration
                            ),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isLoading: Boolean,
    onAttachFile: () -> Unit = {},
    onAttachPhoto: () -> Unit = {},
    supportsVision: Boolean = false,
    hasAttachment: Boolean = false,
    imageFeatureAvailable: Boolean = false,
    canGenerateImages: Boolean = false,
    imageMode: Boolean = false,
    onToggleImageMode: () -> Unit = {},
    editFeatureAvailable: Boolean = false,
    canEditPhoto: Boolean = false,
    editMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    videoFeatureAvailable: Boolean = false,
    canGenerateVideos: Boolean = false,
    videoMode: Boolean = false,
    onToggleVideoMode: () -> Unit = {},
    onExpandCompose: () -> Unit = {},
    preferenceRepository: PreferenceRepository? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as MaskanApplication
    var showVoiceNote by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrEmpty()) {
                val appended = if (text.isEmpty()) spoken else "$text $spoken"
                onTextChange(appended)
            }
        }
    }

    Column {
        if (showVoiceNote) {
            PrivacyInfoNote(
                text = stringResource(R.string.privacy_note_voice),
                onDismissForever = {
                    preferenceRepository?.setVoicePrivacyNoteSeen()
                    showVoiceNote = false
                }
            )
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isLoading) {
            // ONE + button for everything the next message can carry or become: a text file, a
            // photo, a drawing, a video. Two separate buttons (paperclip + palette) squeezed the
            // field and a third would not have fitted at all. Entries the provider cannot serve
            // are absent, not greyed; a generate entry without a chosen model is dimmed and says
            // where to choose one instead of dead-ending.
            var menuOpen by remember { mutableStateOf(false) }
            val chooseImageModelFirst = stringResource(R.string.error_no_image_model)
            val chooseVideoModelFirst = stringResource(R.string.error_no_video_model)
            val attachPhotoFirst = stringResource(R.string.error_no_photo_to_edit)
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.attach_type_title),
                        tint = if (imageMode || videoMode || editMode) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_choose_file)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_attach_clip),
                                contentDescription = null
                            )
                        },
                        onClick = { menuOpen = false; onAttachFile() }
                    )
                    if (supportsVision) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_choose_image)) },
                            leadingIcon = { Text("\uD83D\uDDBC\uFE0F", fontSize = 18.sp) },
                            onClick = { menuOpen = false; onAttachPhoto() }
                        )
                    }
                    if (imageFeatureAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_generate_image)) },
                            leadingIcon = { Text("\uD83C\uDFA8", fontSize = 18.sp) },
                            modifier = Modifier.alpha(if (canGenerateImages) 1f else 0.4f),
                            onClick = {
                                menuOpen = false
                                if (canGenerateImages) onToggleImageMode()
                                else Toast.makeText(context, chooseImageModelFirst, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                    if (editFeatureAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_edit_photo)) },
                            leadingIcon = { Text("\u270F\uFE0F", fontSize = 18.sp) },
                            modifier = Modifier.alpha(if (canEditPhoto) 1f else 0.4f),
                            onClick = {
                                menuOpen = false
                                if (canEditPhoto) onToggleEditMode()
                                else Toast.makeText(context, attachPhotoFirst, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                    if (videoFeatureAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_generate_video)) },
                            leadingIcon = { Text("\uD83C\uDFAC", fontSize = 18.sp) },
                            modifier = Modifier.alpha(if (canGenerateVideos) 1f else 0.4f),
                            onClick = {
                                menuOpen = false
                                if (canGenerateVideos) onToggleVideoMode()
                                else Toast.makeText(context, chooseVideoModelFirst, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.message_placeholder)) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(onSend = { if (!isLoading) onSend() }),
            singleLine = false,
            maxLines = 5,
            enabled = !isLoading,
            // One tap out to a full-height writing surface. The inline field is a single row and
            // got tighter when the draw button joined the paperclip, so anything longer than a
            // sentence is written through a keyhole; the expand arrow is the escape hatch.
            trailingIcon = {
                // Only once there is something to expand. Shown on an empty field it stole
                // enough width to wrap the placeholder onto two lines - the opposite of the
                // problem it exists to solve.
                if (!isLoading && text.isNotBlank()) {
                    val expandLabel = stringResource(R.string.compose_expand)
                    IconButton(
                        onClick = onExpandCompose,
                        modifier = Modifier.semantics { contentDescription = expandLabel }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_expand_compose),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = {
                if (preferenceRepository != null && !preferenceRepository.hasSeenVoicePrivacyNote()) {
                    showVoiceNote = true
                }
                val localeTag = when (app.localeRepository.getLocale()) {
                    "ar" -> "ar-SA"
                    "th" -> "th-TH"
                    "en" -> "en-US"
                    else -> Locale.getDefault().toLanguageTag()
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_listening))
                }
                try {
                    speechLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, context.getString(R.string.voice_not_available), Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !isLoading,
            modifier = Modifier.size(48.dp)
        ) {
            Text(
                text = "🎙",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLoading) {
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.stop_generation),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank() || hasAttachment
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_button),
                    tint = if (text.isNotBlank() || hasAttachment)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }
}

@Composable
private fun PrivacyInfoNote(
    text: String,
    onDismissForever: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.maskanColors.softLavender,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismissForever,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.privacy_note_dismiss),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ExportFormatDialog(
    onSelect: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_conversation)) },
        text = {
            Column {
                TextButton(onClick = { onSelect(ExportFormat.PLAIN_TEXT) }) {
                    Text(stringResource(R.string.export_plain_text))
                }
                TextButton(onClick = { onSelect(ExportFormat.MARKDOWN) }) {
                    Text(stringResource(R.string.export_markdown))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    return timeFormat.format(Date(timestamp))
}

/**
 * Hand a generated image to another app.
 *
 * The stored copy is encrypted, so sharing means writing a decrypted PNG somewhere the share
 * target can read it. That copy goes to the cache directory behind a FileProvider - never to the
 * shared gallery - and the directory is swept first so plaintext copies do not pile up.
 */
private fun shareImageBytes(context: Context, bytes: ByteArray, mimeType: String = "image/png") {
    try {
        val dir = java.io.File(context.cacheDir, "shared_images").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stem = if (mimeType.startsWith("video/")) "maskan-video" else "maskan-image"
        val file = java.io.File(dir, "$stem.${extensionFor(mimeType)}")
        file.writeBytes(bytes)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * What a video bubble shows before the clip exists: waiting, writing the scene, rendering with
 * a bar and an ETA, the server's expanded prompt as it appears, and a Cancel. Once the clip
 * has landed a plain "ready" line stands in until playback (step 3) replaces it; a row with
 * neither a job nor a file is a failed render and its content carries the reason.
 */
@Composable
private fun PendingRequestLine(kind: String, since: Long) {
    var elapsed by remember(since) { mutableStateOf(0L) }
    LaunchedEffect(since) {
        while (true) {
            elapsed = (System.currentTimeMillis() - since) / 1000L
            kotlinx.coroutines.delay(1000L)
        }
    }
    Column {
        Text(
            text = stringResource(if (kind == "edit") R.string.editing_photo else R.string.drawing_image),
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            text = stringResource(R.string.elapsed_seconds_fmt, elapsed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun VideoStatus(
    message: MessageEntity,
    progress: VideoProgress?,
    onCancel: () -> Unit,
    onRetry: () -> Unit = {}
) {
    when {
        // Only reached when the stored file could not be read back (the bubble draws the
        // thumbnail itself whenever the bytes are there).
        message.imagePath != null -> {
            Text(
                text = stringResource(R.string.video_ready),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        message.videoJobId != null -> {
            val state = progress ?: VideoProgress.WAITING
            var elapsed by remember(message.id) { mutableStateOf(0L) }
            LaunchedEffect(message.id) {
                while (true) {
                    elapsed = (System.currentTimeMillis() - message.timestamp) / 1000L
                    kotlinx.coroutines.delay(1000L)
                }
            }
            // A cloud provider reports no percentage: the bar stays indeterminate there.
            val rendering = (state.phase == "rendering" || state.phase == "done") && state.progress > 0
            Column(modifier = Modifier.widthIn(min = 220.dp)) {
                Text(
                    text = stringResource(
                        when (state.phase) {
                            "expanding" -> R.string.video_writing_scene
                            "rendering", "done" -> R.string.video_making
                            else -> R.string.video_waiting_server
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (rendering) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Text(
                    text = state.etaSeconds?.let { eta ->
                        stringResource(R.string.video_eta_minutes, maxOf(1, (eta + 59) / 60))
                    } ?: stringResource(R.string.elapsed_seconds_fmt, elapsed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                state.promptExpanded?.let { expanded ->
                    // The server writes the scene in English whatever the user typed. In an
                    // Arabic UI the paragraph must still flow left-to-right, or its final full
                    // stop jumps to the far side of the line.
                    Text(
                        text = expanded,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = stringResource(R.string.video_cancel),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        else -> {
            Column {
                Text(
                    text = stringResource(R.string.video_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        text = stringResource(R.string.video_retry),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/** File extension for a stored image mime - the mime was sniffed from the actual bytes. */
private fun extensionFor(mimeType: String): String = when (mimeType) {
    "video/mp4" -> "mp4"
    "image/webp" -> "webp"
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    else -> "png"
}

/**
 * ACTION_CREATE_DOCUMENT with the mime chosen per call - (fileName, mimeType) in - because a
 * message's image format is only known at save time. The stock CreateDocument contract bakes a
 * single mime into the launcher, which mislabelled animated WebP as PNG.
 */
private object SaveImageDocumentContract : ActivityResultContract<Pair<String, String>, android.net.Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.second
            putExtra(Intent.EXTRA_TITLE, input.first)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): android.net.Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}
