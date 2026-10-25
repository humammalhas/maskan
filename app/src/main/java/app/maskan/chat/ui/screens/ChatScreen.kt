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
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import app.maskan.chat.data.remote.SearchStream
import app.maskan.chat.data.remote.WebSource
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import app.maskan.chat.BuildConfig
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import app.maskan.chat.util.CameraCapture
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.localizedName
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.PreferenceRepository
import app.maskan.chat.ui.theme.maskanColors
import androidx.compose.ui.text.style.TextAlign
import app.maskan.chat.data.remote.providers.OnDeviceProvider
import app.maskan.chat.data.local.isAppArabic
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.ondevice.LlmEngine
import app.maskan.chat.data.repository.ExportFormat
import app.maskan.chat.ui.viewmodel.ChatViewModel
import app.maskan.chat.ui.viewmodel.PendingMessageAction
import app.maskan.chat.ui.viewmodel.ProjectFilesViewModel
import app.maskan.chat.ui.viewmodel.ReplyThinking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    conversationId: Long,
    preferenceRepository: PreferenceRepository,
    onNavigateBack: () -> Unit,
    /** Where a remembered fact was just written: the folder's memory, or the shared file. */
    onOpenProjectMemory: (Long) -> Unit = {},
    onOpenFolderInstructions: (Long) -> Unit = {},
    /** A fork was just made from this chat: open it. */
    onOpenConversation: (Long) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by rememberSaveable { mutableStateOf("") }
    // A voice note's message leaves the box only once its transcript is back (see onSend).
    LaunchedEffect(uiState.audioSent) { if (uiState.audioSent > 0) inputText = "" }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showCustomPromptDialog by remember { mutableStateOf(false) }
    // The picker, raised over a chat that already has a preset, to change it. The same screen
    // as the one a new chat opens on - a second design for the same fourteen cards would be a
    // second thing to keep in step.
    var showPresetPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val app = context.applicationContext as MaskanApplication
    // Loading 1.6 GB takes about a second warm and a good deal longer cold, and until the
    // first token arrives an empty bubble is indistinguishable from a hang. The engine is the
    // only thing that knows which of the two is happening, so the screen asks it rather than
    // guessing from a timer.
    val engineState by app.llmEngine.state.collectAsState()
    val modelLoading = engineState is LlmEngine.State.Loading
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var speakingMessageId by remember { mutableStateOf<Long?>(null) }

    // A fact has just been remembered: say where, then open the file so the line that was written
    // is visible. Nothing in this app writes to memory without showing the result.
    val rememberedFolderId = uiState.rememberedFolderId
    LaunchedEffect(rememberedFolderId) {
        if (rememberedFolderId != null) {
            val message = if (rememberedFolderId == ProjectFilesViewModel.GLOBAL_SCOPE) {
                context.getString(R.string.remembered_in_shared_memory)
            } else {
                context.getString(R.string.remembered_in_folder, uiState.folderName)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearRemembered()
            onOpenProjectMemory(rememberedFolderId)
        }
    }

    // A fork opens as soon as it exists; the chat it came from stays one Back away, untouched.
    val forkedConversationId = uiState.forkedConversationId
    LaunchedEffect(forkedConversationId, uiState.forkRefusedForVideo) {
        if (uiState.forkRefusedForVideo) {
            Toast.makeText(context, context.getString(R.string.fork_video_rendering), Toast.LENGTH_LONG).show()
        }
        viewModel.clearForkEvent()
        if (forkedConversationId != null) onOpenConversation(forkedConversationId)
    }

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

    // Two things start narration now - the speak button on a bubble and the "Read this to me"
    // chip - so the engine handling lives here instead of inside the button.
    val speakText: (Long, String) -> Unit = speak@{ messageId, content ->
        val engine = tts.value
        if (!ttsReady || engine == null) {
            Toast.makeText(context, context.getString(R.string.voice_narration_unavailable), Toast.LENGTH_SHORT).show()
            return@speak
        }
        val appLocale = app.localeRepository.getLocale()
        val locale = when (appLocale) {
            "ar" -> Locale("ar", "SA")
            "th" -> Locale("th", "TH")
            "en" -> Locale("en", "US")
            else -> Locale.getDefault()
        }
        var langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to en-US only for English/system content - reading Arabic with an
            // English voice is gibberish.
            if (appLocale == "en" || appLocale.isEmpty()) {
                langResult = engine.setLanguage(Locale.US)
            }
        }
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(context, context.getString(R.string.tts_language_unavailable), Toast.LENGTH_SHORT).show()
            return@speak
        }
        engine.stop()
        speakingMessageId = messageId
        engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, messageId.toString())
    }

    // "Read this to me" is armed, not queued: it holds the id of the last reply that existed
    // when the chip was tapped, and speaks the first one AFTER it. A failed request leaves no
    // new reply, so nothing is spoken and the arming is dropped - never the previous answer
    // read out for no reason.
    var speakAfterMessageId by remember { mutableStateOf<Long?>(null) }

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

    // The system file picker again: no storage permission, and the user opens exactly one file.
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachAudio(uri)
        }
    }

    // The photo being taken right now. rememberSaveable, not remember: the camera app is a
    // different process, ours can be killed behind it, and on the way back this is the only
    // thing that says which file the photo went into.
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped on a successful capture; the effect below puts the cursor in the field so the
    // question can be typed without a second tap.
    var focusAfterCapture by remember { mutableStateOf(0) }
    val inputFocusRequester = remember { FocusRequester() }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { taken ->
        val file = pendingCameraPath?.let { java.io.File(it) }
        pendingCameraPath = null
        // A camera app that reports success and writes nothing is the same case as a cancel -
        // GrapheneOS's camera, a scoped return, a user backing out: nothing is attached, the
        // composer is untouched, and the empty file goes away.
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "MaskanCam",
                "result taken=" + taken + " file=" + (file?.name ?: "none") +
                    " exists=" + (file?.exists() ?: false) + " bytes=" + (file?.length() ?: -1L)
            )
        }
        if (taken && file != null && file.length() > 0L) {
            viewModel.attachCameraPhoto(CameraCapture.uriFor(context, file))
            focusAfterCapture++
        } else {
            file?.delete()
        }
    }
    val cameraUnavailable = stringResource(R.string.camera_not_available)
    val takePhoto: () -> Unit = {
        try {
            val file = CameraCapture.newPhotoFile(context)
            pendingCameraPath = file.absolutePath
            cameraLauncher.launch(CameraCapture.uriFor(context, file))
        } catch (_: Exception) {
            // No camera app at all, or the file could not be made. Either way there is nothing
            // to wait for.
            pendingCameraPath = null
            Toast.makeText(context, cameraUnavailable, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(focusAfterCapture) {
        if (focusAfterCapture > 0) {
            // The field may not be composed if the chat is still choosing a preset.
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    // Android 13+: the render notification needs this; asked the first time a video is armed.
    // Declining costs only the notification - the render itself is unaffected.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    var showComposeSheet by remember { mutableStateOf(false) }
    // Plan 1.4: the two pickers the + menu opens. Hosted here, next to the other dialogs, so the
    // menu can close before one appears.
    var showImageModelPicker by remember { mutableStateOf(false) }
    var showVideoModelPicker by remember { mutableStateOf(false) }
    // The header's Change: the provider list first, then that provider's models.
    var showProviderSwitch by remember { mutableStateOf(false) }
    var switchProviderId by remember { mutableStateOf<String?>(null) }

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
        // A capture the process did not live to collect. By age, so a photo being taken right
        // now is never pulled out from under the camera app.
        viewModel.sweepCameraCache()
    }

    // A chat nobody said anything in is not a chat. The row has to be created before this
    // screen can open - the preset, the attachments and the documents are all keyed by its id -
    // so the only honest place to undo it is on the way out. onDispose covers the back arrow,
    // the system back gesture and a deep link that navigates somewhere else, which is all three
    // ways out of here.
    DisposableEffect(conversationId) {
        onDispose { viewModel.discardIfEmpty() }
    }

    val lastReply = uiState.messages.lastOrNull { it.role == "assistant" }
    LaunchedEffect(speakAfterMessageId, uiState.isLoading, uiState.isStreaming, lastReply?.id, lastReply?.content, uiState.error) {
        val armed = speakAfterMessageId ?: return@LaunchedEffect
        if (uiState.error != null) {
            speakAfterMessageId = null
            return@LaunchedEffect
        }
        if (uiState.isLoading || uiState.isStreaming) return@LaunchedEffect
        val reply = lastReply ?: return@LaunchedEffect
        if (reply.id > armed && reply.content.isNotBlank()) {
            speakAfterMessageId = null
            speakText(reply.id, reply.content)
        }
    }

    BackHandler(enabled = showPresetPicker) { showPresetPicker = false }

    val visibleMessages = uiState.messages.filter { it.role != "system" }
    // Derived from the collected state, not read from the ViewModel: a plain function call
    // would be invisible to Compose and the button would keep whichever shape it was first
    // drawn with.
    // lastOrNull { not a notice }: a notice row written between the question and the answer
    // would otherwise make the composer think the turn was already taken.
    val awaitingReply = !uiState.isLoading && !uiState.isStreaming &&
        visibleMessages.lastOrNull { it.role != ChatRepository.ROLE_NOTICE }?.role == "user"
    val speakButtonShown = viewModel.speakButtonShown()

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

    // The same dialog Settings uses, on the same two preferences. No verification pass: checking
    // an image model means drawing a real, paid picture on every tap.
    if (showImageModelPicker) {
        ModelPickerDialog(
            models = viewModel.imageModelChoices(),
            visionModels = emptySet(),
            verifiedModels = emptySet(),
            freeModels = viewModel.freeModels(),
            selectedModel = uiState.selectedImageModelName,
            onSelect = { model ->
                viewModel.selectImageModel(model)
                showImageModelPicker = false
            },
            onDismiss = { showImageModelPicker = false },
            allowCustom = true,
            allowNone = true,
            imageModels = viewModel.imageModelChoices().toSet()
        )
    }

    if (showVideoModelPicker) {
        ModelPickerDialog(
            models = viewModel.videoModelChoices(),
            visionModels = emptySet(),
            verifiedModels = emptySet(),
            freeModels = viewModel.freeModels(),
            selectedModel = uiState.selectedVideoModelName,
            onSelect = { model ->
                viewModel.selectVideoModel(model)
                showVideoModelPicker = false
            },
            onDismiss = { showVideoModelPicker = false },
            allowCustom = true,
            allowNone = true
        )
    }

    if (showProviderSwitch) {
        ProviderPickerDialog(
            choices = viewModel.providerChoices(),
            currentProviderId = uiState.selectedProviderId,
            onSelect = { choice ->
                showProviderSwitch = false
                val models = viewModel.modelChoices(choice.provider.id)
                // One model and nothing to choose between (on-device): switch straight away
                // rather than show a list of one.
                if (models.size == 1) {
                    viewModel.switchModel(choice.provider.id, models.first())
                } else {
                    switchProviderId = choice.provider.id
                }
            },
            onDismiss = { showProviderSwitch = false }
        )
    }

    switchProviderId?.let { providerId ->
        val isCurrentProvider = providerId == uiState.selectedProviderId
        ModelPickerDialog(
            models = viewModel.modelChoices(providerId),
            visionModels = viewModel.visionModelsFor(providerId),
            verifiedModels = viewModel.verifiedModelsFor(providerId),
            freeModels = viewModel.freeModelsFor(providerId),
            // Ticked only on the provider this chat is on: the same id at another provider is a
            // different model.
            selectedModel = if (isCurrentProvider) uiState.selectedModel else "",
            onSelect = { model ->
                switchProviderId = null
                viewModel.switchModel(providerId, model)
            },
            onDismiss = { switchProviderId = null }
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
            // Opens on the last prompt the user wrote, not on an empty box. Writing it out
            // again from memory for every new chat is what "custom prompts are not saved"
            // meant in practice.
            initialText = viewModel.savedCustomPrompt(),
            onConfirm = { prompt ->
                showCustomPromptDialog = false
                showPresetPicker = false
                viewModel.setCustomPrompt(prompt)
            },
            onDismiss = { showCustomPromptDialog = false }
        )
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = displayTitle(uiState.title),
            titleRes = R.string.rename_chat,
            labelRes = R.string.chat_name_hint,
            onDismiss = { showRenameDialog = false },
            onRename = { newName ->
                viewModel.renameConversation(newName)
                showRenameDialog = false
            }
        )
    }

    uiState.pendingMessageAction?.let { action ->
        when (action.kind) {
            PendingMessageAction.Kind.EDIT -> EditMessageDialog(
                initialText = action.text,
                followers = action.followers,
                onConfirm = { viewModel.confirmEdit(it) },
                onDismiss = { viewModel.cancelMessageAction() }
            )
            PendingMessageAction.Kind.EDIT_REPLY -> EditMessageDialog(
                initialText = action.text,
                followers = 0,
                isReply = true,
                onConfirm = { viewModel.confirmEditReply(it) },
                onDismiss = { viewModel.cancelMessageAction() }
            )
            PendingMessageAction.Kind.DELETE -> AlertDialog(
                onDismissRequest = { viewModel.cancelMessageAction() },
                title = { Text(stringResource(R.string.delete_message_title)) },
                text = {
                    Text(
                        if (action.followers > 0) {
                            stringResource(R.string.delete_message_body_with_followers, action.followers)
                        } else {
                            stringResource(R.string.delete_message_body)
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmDelete() }) {
                        Text(stringResource(R.string.delete_button))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelMessageAction() }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }
    }

    uiState.documentCost?.let { cost ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDocumentCost() },
            title = { Text(stringResource(R.string.document_cost_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.document_cost_body,
                            cost.doc.name,
                            cost.tokens,
                            cost.requests
                        )
                    )
                    Text(
                        text = stringResource(R.string.document_not_read),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDocumentCost() }) {
                    Text(stringResource(R.string.document_cost_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDocumentCost() }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    uiState.scannedOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { viewModel.declineScannedPages() },
            title = { Text(stringResource(R.string.document_scan_title)) },
            text = {
                Text(stringResource(R.string.document_scan_body, offer.name, offer.pages))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.acceptScannedPages() }) {
                    Text(stringResource(R.string.document_scan_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declineScannedPages() }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
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
                        // The chat's own name, not the word "Chat". Tapping it renames it -
                        // the other way in is the list's long-press menu, and a name is most
                        // often worth changing while you are reading what is under it.
                        Text(
                            text = displayTitle(uiState.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                enabled = uiState.title.isNotEmpty()
                            ) { showRenameDialog = true }
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
                // The strip is ALWAYS here once a chat has been set up, and it is a control.
                // It used to appear only for a named preset, which meant a chat with a custom
                // prompt showed nothing at all - and there was no way to change the prompt of a
                // chat that had started, only to start a different chat.
                // Which model answers this chat, and the way to change it - on every chat, cloud,
                // local and on-device alike (2.7, plan item 10). It replaces 2.6's on-device-only
                // "Basic on-device model · Upgrade", disclosure 2 of 3: the model's own name now
                // says which model wrote the answer below it, for on-device and everyone else.
                // The SAME gate as the preset strip below, and for the same two reasons. The
                // picker is a height-filling grid sized to fit with no scroll (PresetPicker.kt),
                // so any row added to the header comes straight out of the cards and clips their
                // Arabic descriptions - which is what the device showed. `presetSelected` covers
                // the first-run picker, `showPresetPicker` the one raised over an existing chat.
                // No height of its own: "Change" is translated text and the row must grow with it.
                if (uiState.presetSelected && !showPresetPicker) {
                    val changeLabel = stringResource(R.string.change_model)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.maskanColors.softLavender)
                            .clickable(onClickLabel = changeLabel) { showProviderSwitch = true }
                            // Under the title, like the preset name below it. Start-relative.
                            .padding(start = 60.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = viewModel.modelLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.model_header_change),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (uiState.presetSelected && !showPresetPicker) {
                    val presetLabel = when (uiState.presetId) {
                        ChatRepository.PRESET_CUSTOM -> stringResource(R.string.preset_custom_label)
                        ChatRepository.PRESET_NONE, null -> stringResource(R.string.preset_none_label)
                        else -> uiState.currentPreset?.localizedName()
                            ?: stringResource(R.string.preset_none_label)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.maskanColors.softLavender)
                            // 16dp bar inset + 48dp nav icon, so the name sits under the title.
                            // Start-relative, so it mirrors correctly in RTL.
                            .padding(start = 60.dp, end = 16.dp, bottom = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable { showPresetPicker = true }
                        ) {
                            Text(
                                text = presetLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.change_preset),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Which folder's instructions this chat carries - before 2.7 nothing in
                        // the chat said so - and the way to them. On the style row, not a row of
                        // its own: a new header row comes out of the preset picker's height.
                        val folderId = uiState.folderId
                        if (folderId != null) {
                            val openLabel = stringResource(R.string.folder_chip_open_instructions)
                            Spacer(modifier = Modifier.width(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable(onClickLabel = openLabel) {
                                        onOpenFolderInstructions(folderId)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(colorFromHex(uiState.folderColorHex))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.folderName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        textDirection = TextDirection.Content
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
                    } else if (viewModel.canRetry()) {
                        {
                            TextButton(onClick = { viewModel.retryLast() }) {
                                Text(stringResource(R.string.video_retry))
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
            // Nothing to compose with while the picker is up. It is drawn over an existing
            // chat, and on the device a message typed and sent into the composer underneath it
            // went out with the OLD prompt while the picker sat there waiting to change it -
            // which is the one thing the screen is open to prevent.
            if (uiState.presetSelected && !showPresetPicker) {
                Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                    uiState.pendingImageBytes?.let { bytes ->
                        ImagePreview(
                            imageBytes = bytes,
                            onRemove = { viewModel.clearPendingImage() }
                        )
                        // The whole point of pointing a phone at a menu is that the question does
                        // not have to be composed. Not shown while the photo is the raw material
                        // for something else (an edit, a video), where these would send it to the
                        // wrong place.
                        if (viewModel.photoQuestionsAvailable() &&
                            !uiState.imageMode && !uiState.videoMode && !uiState.editMode
                        ) {
                            PhotoQuestionChips(
                                onAsk = { prompt ->
                                    inputText = ""
                                    viewModel.sendMessage(prompt)
                                },
                                onReadAloud = { prompt ->
                                    speakAfterMessageId =
                                        uiState.messages.lastOrNull { it.role == "assistant" }?.id ?: 0L
                                    inputText = ""
                                    viewModel.sendMessage(prompt)
                                }
                            )
                        }
                        if (!preferenceRepository.hasSeenImagePrivacyNote()) {
                            val providerName = ProviderRegistry.getProvider(uiState.selectedProviderId)?.let { provider ->
                                if (app.localeRepository.getLocale() == "ar") provider.nameAr else provider.displayName
                            } ?: uiState.selectedProviderId
                            PrivacyInfoNote(
                                // On-device nothing is sent anywhere; "sent to On-device" said it was.
                                text = if (uiState.selectedProviderId == OnDeviceProvider.ID) {
                                    stringResource(R.string.privacy_note_image_ondevice)
                                } else {
                                    stringResource(R.string.privacy_note_image, providerName)
                                },
                                onDismissForever = { preferenceRepository.setImagePrivacyNoteSeen() }
                            )
                        }
                    }
                    uiState.pendingFileName?.let { fileName ->
                        FileAttachmentChip(
                            fileName = fileName,
                            onRemove = { viewModel.clearPendingFile() }
                        )
                        // Said before the file is sent, not discovered afterwards: a .docx
                        // whose charts and comments were silently dropped is a wrong answer
                        // waiting to happen.
                        if (uiState.pendingFileKind == "docx" || uiState.pendingFileKind == "xlsx" ||
                            uiState.pendingFileKind == "pdf"
                        ) {
                            Text(
                                text = stringResource(R.string.document_not_read),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                            )
                        }
                        documentWarningText(uiState.pendingFileWarning)?.let { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                            )
                        }
                    }
                    uiState.pendingPages?.let { pages ->
                        FileAttachmentChip(
                            fileName = stringResource(
                                R.string.document_pages_chip,
                                pages.size,
                                uiState.pendingPagesName.orEmpty()
                            ),
                            onRemove = { viewModel.clearPendingPages() }
                        )
                    }
                    uiState.pendingAudio?.let { audio ->
                        FileAttachmentChip(
                            fileName = "🎙 " + audio.name +
                                (audio.durationMs?.let { " · " + audioDuration(it) } ?: ""),
                            onRemove = { viewModel.clearPendingAudio() }
                        )
                        // The text box is emptied only when the transcript is back (audioSent).
                        val answerPrompt = stringResource(R.string.audio_prompt_answer)
                        val listenPrompt = stringResource(R.string.audio_prompt_listen)
                        AudioActionChips(
                            enabled = !uiState.transcribing && !uiState.isLoading && !uiState.isStreaming,
                            onTranscribeOnly = { viewModel.transcribeVoiceNote(inputText, answer = false) },
                            // Nothing typed: the chip asks a visible question, as the photo chips do.
                            // A note answered with no question was answered AS ITS SPEAKER'S
                            // listener, even with the voice-note label (Gemini and DeepSeek, session 11).
                            onTranscribeAndAnswer = {
                                viewModel.transcribeVoiceNote(inputText.ifBlank { answerPrompt }, answer = true)
                            },
                            canListen = viewModel.canListen(),
                            onListenAndAnswer = {
                                viewModel.transcribeVoiceNote(inputText.ifBlank { listenPrompt }, answer = true, listen = true)
                            }
                        )
                        // Said every time, not once: a voice note is usually somebody else's
                        // voice, and the provider receiving it can change with the user's keys.
                        val isArabic = app.localeRepository.getLocale() == "ar"
                        val backend = viewModel.transcribeBackend()
                        Text(
                            text = stringResource(
                                if (backend?.anonymizedOnly == true) R.string.privacy_note_audio_anonymized
                                else R.string.privacy_note_audio,
                                viewModel.transcribeProviderName(isArabic)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                        )
                        if (uiState.transcribing) {
                            Text(
                                text = stringResource(
                                    R.string.audio_transcribing,
                                    viewModel.transcribeProviderName(isArabic)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                            )
                        }
                    }
                    if (uiState.readingFile) {
                        Text(
                            text = stringResource(R.string.document_reading_file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                        )
                    }
                    if (uiState.editMode) {
                        FileAttachmentChip(
                            fileName = stringResource(R.string.edit_mode_chip),
                            onRemove = { viewModel.setEditMode(false) }
                        )
                    }
                    if (uiState.webSearchMode) {
                        FileAttachmentChip(
                            fileName = stringResource(R.string.web_search_mode_chip),
                            onRemove = { viewModel.setWebSearchMode(false) }
                        )
                        // The question leaves for a search engine as well as the model, and the
                        // provider decides which one - said before the first search, not after.
                        if (!preferenceRepository.hasSeenWebSearchPrivacyNote()) {
                            val providerName = ProviderRegistry.getProvider(uiState.selectedProviderId)?.let { provider ->
                                if (app.localeRepository.getLocale() == "ar") provider.nameAr else provider.displayName
                            } ?: uiState.selectedProviderId
                            PrivacyInfoNote(
                                text = stringResource(R.string.privacy_note_web_search, providerName),
                                onDismissForever = { preferenceRepository.setWebSearchPrivacyNoteSeen() }
                            )
                        }
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
                            model = uiState.selectedVideoModelName,
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
                            // Pages of a scan count as an attachment: with only pages waiting
                            // and nothing typed, Send was dead and the chip just sat there.
                            if (uiState.pendingAudio != null) {
                                // Kept in the box until the transcript is back: a failed
                                // transcription sends nothing, so nothing typed may be lost.
                                if (!uiState.transcribing) viewModel.sendMessage(inputText)
                            } else if (inputText.isNotBlank() || uiState.pendingImageBytes != null ||
                                uiState.pendingFileText != null || uiState.pendingPages != null
                            ) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            } else if (awaitingReply) {
                                // The last send failed, or its answer was deleted. The question
                                // is already in the chat; asking the user to retype it to get
                                // an answer is the dead end the report is about.
                                viewModel.generateFromLastUserMessage()
                            }
                        },
                        awaitingReply = awaitingReply,
                        onStop = { viewModel.cancelGeneration() },
                        isLoading = uiState.isLoading || uiState.isStreaming,
                        onAttachFile = {
                            filePickerLauncher.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "text/plain",
                                    "text/html",
                                    "text/markdown",
                                    "text/csv"
                                )
                            )
                        },
                        onAttachPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onTakePhoto = takePhoto,
                        photoAttached = uiState.pendingImageBytes != null &&
                            !uiState.imageMode && !uiState.videoMode && !uiState.editMode,
                        focusRequester = inputFocusRequester,
                        // A photo can be attached for chat (vision), for editing, or as the first
                        // frame of a video - any one of those earns the entry.
                        supportsVision = viewModel.currentProviderSupportsVision() ||
                            viewModel.editModel() != null || viewModel.canGenerateVideos(),
                        hasAttachment = uiState.pendingImageBytes != null ||
                            uiState.pendingFileText != null || uiState.pendingPages != null ||
                            uiState.pendingAudio != null,
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
                        preferenceRepository = preferenceRepository,
                        imageModelName = uiState.selectedImageModelName,
                        videoModelName = uiState.selectedVideoModelName,
                        onPickImageModel = { showImageModelPicker = true },
                        onPickVideoModel = { showVideoModelPicker = true },
                        canSearchWeb = viewModel.canSearchWeb(),
                        webSearchMode = uiState.webSearchMode,
                        onToggleWebSearch = { viewModel.setWebSearchMode(!uiState.webSearchMode) },
                        canAttachAudio = viewModel.transcribeBackend() != null,
                        onAttachAudio = {
                            audioPickerLauncher.launch(arrayOf("audio/*", "application/ogg"))
                        }
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
        } else if (!uiState.presetSelected || showPresetPicker) {
            PresetPicker(
                defaultDialect = preferenceRepository.getDefaultDialect(),
                onPresetSelected = { preset, dialect ->
                    when (preset.id) {
                        ChatRepository.PRESET_CUSTOM -> showCustomPromptDialog = true
                        else -> {
                            viewModel.setPreset(preset, dialect)
                            showPresetPicker = false
                        }
                    }
                },
                onNoPreset = {
                    viewModel.setNoPreset()
                    showPresetPicker = false
                },
                onCancel = if (showPresetPicker) {
                    { showPresetPicker = false }
                } else {
                    null
                },
                modifier = Modifier.padding(paddingValues)
            )
        // ... and nothing attached either. A file read into an empty chat is the FIRST thing
        // that happens in a document conversation, and the empty-state panel replaces the whole
        // list - so without this the notes card is invisible until the user has typed something,
        // which is exactly when they most want to see that the file was read. (Device, session 4.)
        } else if (visibleMessages.isEmpty() && uiState.documents.isEmpty()) {
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
                    if (message.role == ChatRepository.ROLE_NOTICE) {
                        // Centred, quiet, and not a bubble: it is the app talking about the
                        // request, not either side of the conversation.
                        Text(
                            text = when (message.content) {
                                ChatRepository.NOTICE_VOICE_DROPPED ->
                                    stringResource(R.string.clamp_voice_dropped)
                                else -> viewModel.switchNoticeParts(message.content, isAppArabic())?.let { (provider, model) ->
                                    stringResource(R.string.notice_model_switched, provider, model)
                                } ?: message.content
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 4.dp)
                        )
                        return@items
                    }
                    val isLastMessage = message == visibleMessages.lastOrNull()
                    val busy = uiState.isLoading || uiState.isStreaming
                    val forkTitle = stringResource(R.string.fork_title, displayTitle(uiState.title))
                    val isActivelyStreaming = uiState.isStreaming && isLastMessage && message.role == "assistant"
                    // Decrypt once per message, not once per recomposition.
                    val generatedImage = message.imagePath?.let { path ->
                        remember(path) { viewModel.readImage(path) }
                    }
                    // The card is declared AFTER the bubble inside this item on purpose:
                    // the list is reverseLayout, so later content in one item renders below -
                    // which here means "just after the message it arrived with".
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        onRemember = if (message.content.isNotBlank() &&
                            viewModel.rememberTarget() != null
                        ) {
                            { viewModel.rememberFact(message.content) }
                        } else {
                            null
                        },
                        onSpeakToggle = {
                            val engine = tts.value
                            if (engine != null && speakingMessageId == message.id) {
                                engine.stop()
                                speakingMessageId = null
                            } else {
                                speakText(message.id, message.content)
                            }
                        },
                        speakButtonShown = speakButtonShown,
                        // Nothing is offered while a request is in flight: every one of these
                        // rewrites the conversation the request is being built from.
                        onRegenerate = if (
                            !busy && isLastMessage && message.role == "assistant" &&
                            message.content.isNotBlank() && message.imagePath == null &&
                            message.imageMimeType == null && message.videoJobId == null
                        ) {
                            { viewModel.regenerateLast() }
                        } else {
                            null
                        },
                        onEdit = if (!busy && message.role == "user" && message.content.isNotBlank()) {
                            { viewModel.beginEdit(message.id) }
                        } else {
                            null
                        },
                        // Text replies only: a picture or a video is not something to retype.
                        onEditReply = if (
                            !busy && message.role == "assistant" && message.content.isNotBlank() &&
                            message.imagePath == null && message.imageMimeType == null &&
                            message.videoJobId == null
                        ) {
                            { viewModel.beginEditReply(message.id) }
                        } else {
                            null
                        },
                        onDelete = if (!busy) {
                            { viewModel.beginDelete(message.id) }
                        } else {
                            null
                        },
                        onFork = if (!busy && (message.role == "user" || message.role == "assistant")) {
                            { viewModel.forkFrom(message.id, forkTitle) }
                        } else {
                            null
                        },
                        thinking = uiState.thinking[message.id]
                    )
                    if (message.role == "assistant" && message.sourcesJson != null) {
                        val sources = remember(message.sourcesJson) { SearchStream.decode(message.sourcesJson) }
                        if (sources.isNotEmpty()) WebSourcesList(sources)
                    }
                    if (modelLoading && isLastMessage && message.role == "assistant" &&
                        message.content.isBlank()
                    ) {
                        Text(
                            text = stringResource(R.string.ondevice_loading_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    uiState.documents
                        .filter { it.attachedMessageId == message.id }
                        .forEach { document ->
                            DocumentNotesCard(
                                document = document,
                                reading = uiState.reading,
                                onContinue = { viewModel.startReading(document.id) },
                                onStop = { viewModel.stopReading() },
                                onRemove = { viewModel.forgetDocument(document.id) }
                            )
                        }
                    }
                }

                // A file attached before anything was said belongs at the top of the
                // conversation; with reverseLayout the LAST item declared renders there.
                item {
                    uiState.documents
                        .filter { it.attachedMessageId == null }
                        .forEach { document ->
                            DocumentNotesCard(
                                document = document,
                                reading = uiState.reading,
                                onContinue = { viewModel.startReading(document.id) },
                                onStop = { viewModel.stopReading() },
                                onRemove = { viewModel.forgetDocument(document.id) }
                            )
                        }
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

/** "0:37", "3:29", "1:02:05": a recording's length the way a phone's player writes it. */
private fun audioDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/**
 * A voice note inside the user's bubble (plan item 3): what was attached, the transcript as spoken,
 * and who wrote it out. The transcript is what the model read, so it is shown in full - the user
 * checks it rather than trusting it - in its own text's direction, like the rest of the bubble.
 */
@Composable
private fun VoiceNoteBlock(message: MessageEntity, hasText: Boolean) {
    val isArabic = LocalContext.current.resources.configuration.locales[0].language == "ar"
    Column(modifier = Modifier.padding(bottom = if (hasText) 8.dp else 0.dp)) {
        Text(
            text = "🎙 " + (message.audioName ?: "") +
                (message.audioDurationMs?.let { " · " + audioDuration(it) } ?: ""),
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        message.transcript?.let { transcript ->
            SelectionContainer {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        message.transcribedBy?.let { by ->
            val providerId = by.substringBefore('/')
            val model = by.substringAfter('/', "")
            val provider = ProviderRegistry.getProvider(providerId)
            val name = when {
                provider == null -> providerId
                isArabic -> provider.nameAr
                else -> provider.displayName
            }
            Text(
                // The model id is Latin inside a possibly Arabic sentence: isolate it.
                text = stringResource(R.string.audio_transcribed_by, name, "⁨" + model + "⁩"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // "Listen and answer": the model heard the recording itself, not only the transcript.
        message.heardBy?.let { by ->
            val provider = ProviderRegistry.getProvider(by.substringBefore('/'))
            val name = when {
                provider == null -> by.substringBefore('/')
                isArabic -> provider.nameAr
                else -> provider.displayName
            }
            Text(
                text = stringResource(R.string.audio_heard_by, name, "⁨" + by.substringAfter('/', "") + "⁩"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CustomPromptDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

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
    onShareImage: () -> Unit = {},
    /**
     * Null when this conversation has nowhere to remember to - no folder, and shared memory off.
     *
     * Deliberately a button in the footer row and not a long press on the bubble: the text is
     * inside a SelectionContainer, and on the device a long press raised our menu AND Compose's
     * Copy / Select all toolbar at the same time, one drawn over the other. There is no gesture
     * to share with selection, so this takes a tap of its own.
     */
    onRemember: (() -> Unit)? = null,
    /**
     * False hides the speak button on every reply. The "Read this to me" chip is not this, and
     * neither is the STOP control: a reply that is speaking keeps its button either way, or
     * speech the chip started could not be stopped at all.
     */
    speakButtonShown: Boolean = true,
    /**
     * Ask the same question again and replace this answer. Offered on the LAST reply only: a
     * regenerate half way up a conversation would have to drop everything built on the answer
     * it replaces, and a one-tap action must not be destructive. Edit-and-resend is the way to
     * change the middle of a conversation, and it asks first.
     */
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    /** Rewrite this reply in place, marked as edited; nothing after it goes (plan item 9). */
    onEditReply: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Copy the chat up to and including this message into a new one (plan item 13). */
    onFork: (() -> Unit)? = null,
    /** The model's reasoning for this reply, while the chat is open (plan item 2). */
    thinking: ReplyThinking? = null
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
                    if (!isVideo && message.imagePath != null && generatedImage == null) {
                        // The row says a picture was generated here and the file is not on this
                        // phone. After a restore that is every picture the app ever drew - they
                        // stay behind by design - so the bubble says so rather than showing a
                        // caption over nothing.
                        Text(
                            text = stringResource(R.string.restore_picture_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp)
                        )
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
                    if (message.audioName != null || message.transcript != null) {
                        VoiceNoteBlock(message = message, hasText = message.content.isNotBlank())
                    }
                    if (thinking != null && !isUser) {
                        ThinkingRow(thinking = thinking, hasAnswer = message.content.isNotBlank())
                    }
                    if (message.content.isNotBlank() && !isVideo) {
                        SelectionContainer {
                            if (!isUser && !isStreaming) {
                                MarkdownText(text = message.content)
                            } else {
                                // The user's own words, and a reply still streaming: in the
                                // direction of their text, as MarkdownText does, not the app's.
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
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
                if (onRemember != null) {
                    IconButton(onClick = onRemember) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = stringResource(R.string.add_to_project_memory),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // While this reply is BEING SPOKEN the control is always here, whatever the
                // setting says - because it is the same button, and with the setting off there
                // was no way to stop speech that "Read this to me" had started. (Humam, on the
                // device: the only way out was deleting the message.) Nothing shows when nothing
                // is playing, which is what the setting is actually about.
                if (!isUser && message.content.isNotBlank() && (speakButtonShown || isSpeaking)) {
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
                // An overflow button, NOT a long press on the bubble: the text is inside a
                // SelectionContainer and a long press there raises Compose's own Copy / Select
                // all toolbar. Two menus at once is what killed the long-press idea in session
                // 2, and nothing about this message has changed since.
                if (onRegenerate != null || onEdit != null || onEditReply != null || onDelete != null || onFork != null) {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.message_actions),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            onRegenerate?.let { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.regenerate_reply)) },
                                    onClick = { showMenu = false; action() }
                                )
                            }
                            onEdit?.let { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_and_resend)) },
                                    onClick = { showMenu = false; action() }
                                )
                            }
                            onEditReply?.let { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_reply)) },
                                    onClick = { showMenu = false; action() }
                                )
                            }
                            onFork?.let { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fork_from_here)) },
                                    onClick = { showMenu = false; action() }
                                )
                            }
                            onDelete?.let { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_message)) },
                                    onClick = { showMenu = false; action() }
                                )
                            }
                        }
                    }
                }
                // The reply was rewritten by the user. Same style as the time beside it, and no
                // height of its own: it is a translated word and must be free to grow.
                if (message.editedAt != null) {
                    Text(
                        text = stringResource(R.string.reply_edited_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
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
    /** The conversation ends on a question nobody answered: Send becomes Generate. */
    awaitingReply: Boolean = false,
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
    preferenceRepository: PreferenceRepository? = null,
    /** Named under the generate entries so the current choice is visible where it is used. */
    imageModelName: String = "",
    videoModelName: String = "",
    onPickImageModel: () -> Unit = {},
    onPickVideoModel: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    /** The provider and model can search the web themselves (2.7); otherwise the entry is greyed. */
    canSearchWeb: Boolean = false,
    webSearchMode: Boolean = false,
    onToggleWebSearch: () -> Unit = {},
    canAttachAudio: Boolean = false,
    onAttachAudio: () -> Unit = {},
    /** A photo is waiting to be asked about: the hint becomes the question it invites. */
    photoAttached: Boolean = false,
    focusRequester: FocusRequester? = null
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
            val attachPhotoFirst = stringResource(R.string.error_no_photo_to_edit)
            val modelCannotSee = stringResource(R.string.model_cannot_see_images)
            val cannotSearch = stringResource(R.string.web_search_unavailable)
            val cannotTranscribe = stringResource(
                R.string.audio_no_backend,
                ProviderRegistry.getProvider("groq")?.let {
                    if (LocalContext.current.resources.configuration.locales[0].language == "ar") it.nameAr else it.displayName
                } ?: "Groq"
            )
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.attach_type_title),
                        tint = if (imageMode || videoMode || editMode || webSearchMode) MaterialTheme.colorScheme.tertiary
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
                    // First, above the gallery: the common case is the thing in front of you,
                    // not the thing you photographed yesterday. Dimmed with the same toast when
                    // the model cannot see.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_take_photo)) },
                        leadingIcon = { Text("\uD83D\uDCF7", fontSize = 18.sp) },
                        modifier = Modifier.alpha(if (supportsVision) 1f else 0.4f),
                        onClick = {
                            menuOpen = false
                            if (supportsVision) onTakePhoto()
                            else Toast.makeText(context, modelCannotSee, Toast.LENGTH_LONG).show()
                        }
                    )
                    // Always listed: a hidden entry reads as a broken app. Dimmed, and it says
                    // why, when the current model cannot take a photo and nothing else here
                    // (editing, video) could use one either.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_choose_image)) },
                        leadingIcon = { Text("\uD83D\uDDBC\uFE0F", fontSize = 18.sp) },
                        modifier = Modifier.alpha(if (supportsVision) 1f else 0.4f),
                        onClick = {
                            menuOpen = false
                            if (supportsVision) onAttachPhoto()
                            else Toast.makeText(context, modelCannotSee, Toast.LENGTH_LONG).show()
                        }
                    )
                    // Always listed, greyed where the provider cannot search, so the feature is
                    // never invisible and never pretends: the toast says why it is off.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_web_search)) },
                        leadingIcon = { Text("🌐", fontSize = 18.sp) },
                        trailingIcon = if (webSearchMode) {
                            { Text("✓", color = MaterialTheme.colorScheme.tertiary) }
                        } else {
                            null
                        },
                        modifier = Modifier.alpha(if (canSearchWeb) 1f else 0.4f),
                        onClick = {
                            menuOpen = false
                            if (canSearchWeb) onToggleWebSearch()
                            else Toast.makeText(context, cannotSearch, Toast.LENGTH_LONG).show()
                        }
                    )
                    // Greyed, never hidden, when none of the user's keys can transcribe: the
                    // toast names the free key that would (plan item 3).
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_audio)) },
                        leadingIcon = { Text("🎙", fontSize = 18.sp) },
                        modifier = Modifier.alpha(if (canAttachAudio) 1f else 0.4f),
                        onClick = {
                            menuOpen = false
                            if (canAttachAudio) onAttachAudio()
                            else Toast.makeText(context, cannotTranscribe, Toast.LENGTH_LONG).show()
                        }
                    )
                    if (imageFeatureAvailable) {
                        // The model is named right here and changed right here. It used to be
                        // four taps deep in Settings, which is not where the decision is made -
                        // and with nothing chosen the entry now OPENS the picker instead of
                        // telling the user to go elsewhere and do it.
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.attach_generate_image))
                                    Text(
                                        text = imageModelName.ifBlank {
                                            stringResource(R.string.model_picker_none)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            leadingIcon = { Text("\uD83C\uDFA8", fontSize = 18.sp) },
                            trailingIcon = {
                                IconButton(onClick = { menuOpen = false; onPickImageModel() }) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.change_model)
                                    )
                                }
                            },
                            modifier = Modifier.alpha(if (canGenerateImages) 1f else 0.4f),
                            onClick = {
                                menuOpen = false
                                if (canGenerateImages) onToggleImageMode() else onPickImageModel()
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
                            text = {
                                Column {
                                    Text(stringResource(R.string.attach_generate_video))
                                    Text(
                                        text = videoModelName.ifBlank {
                                            stringResource(R.string.model_picker_none)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            leadingIcon = { Text("\uD83C\uDFAC", fontSize = 18.sp) },
                            trailingIcon = {
                                IconButton(onClick = { menuOpen = false; onPickVideoModel() }) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.change_model)
                                    )
                                }
                            },
                            modifier = Modifier.alpha(if (canGenerateVideos) 1f else 0.4f),
                            onClick = {
                                menuOpen = false
                                if (canGenerateVideos) onToggleVideoMode() else onPickVideoModel()
                            }
                        )
                    }
                }
            }
        }
        if (!isLoading && supportsVision) {
            // One tap to the camera, beside the + it would otherwise be buried in. Only where the
            // model can see: a permanently dimmed camera would cost the text field width on every
            // text-only model, and the + menu still lists it (dimmed, with the toast) so the
            // feature is never invisible.
            val takePhotoLabel = stringResource(R.string.attach_take_photo)
            IconButton(
                onClick = onTakePhoto,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = takePhotoLabel,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            placeholder = {
                Text(stringResource(if (photoAttached) R.string.photo_question_hint else R.string.message_placeholder))
            },
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
            val hasSomethingToSend = text.isNotBlank() || hasAttachment
            val generating = !hasSomethingToSend && awaitingReply
            IconButton(
                onClick = onSend,
                enabled = hasSomethingToSend || awaitingReply
            ) {
                Icon(
                    imageVector = if (generating) {
                        Icons.Default.Refresh
                    } else {
                        Icons.AutoMirrored.Filled.Send
                    },
                    contentDescription = stringResource(
                        if (generating) R.string.generate_reply else R.string.send_button
                    ),
                    tint = if (hasSomethingToSend || awaitingReply)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }
}

/**
 * Edit what was said and send it again.
 *
 * The warning line is the whole point of it being a dialog: on the last message this costs
 * nothing, and in the middle of a conversation it throws away everything said since. The user
 * is told which of those two they are about to do, with the number, before anything happens.
 */
@Composable
private fun EditMessageDialog(
    initialText: String,
    followers: Int,
    /** A reply, not a question: saved in place, nothing is sent and nothing after it goes. */
    isReply: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isReply) R.string.edit_reply else R.string.edit_and_resend)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 220.dp),
                    maxLines = 8
                )
                if (isReply) {
                    // Said out loud because editing a question taught the opposite: there, what
                    // follows is dropped. Here it stays, and the reply is marked as edited.
                    Text(
                        text = stringResource(R.string.edit_reply_keeps_followers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else if (followers > 0) {
                    Text(
                        text = stringResource(R.string.edit_drops_followers, followers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(if (isReply) R.string.save_button else R.string.send_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        }
    )
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
/**
 * The pages the provider's web search read, under its answer: folded to one line by default
 * (Groq can return twenty), unfolded to a numbered list that opens each page in the browser.
 *
 * Text only, no fixed heights: the titles are whatever the page called itself, in any script,
 * and a box that cannot grow is the Arabic-clipping bug this app has fixed four times.
 */
@Composable
private fun WebSourcesList(sources: List<WebSource>) {
    var open by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(
                text = "🌐 " + stringResource(R.string.sources_title_fmt, sources.size) +
                    if (open) " ▴" else " ▾",
                style = MaterialTheme.typography.labelLarge
            )
        }
        if (open) {
            sources.forEachIndexed { index, source ->
                // The site, because pages name themselves "world news" or "top stories" and a list
                // of those says nothing. Not for Gemini, whose urls are its own redirect address
                // and whose titles already ARE the site.
                val host = remember(source.url) {
                    runCatching { java.net.URI(source.url).host?.removePrefix("www.") }.getOrNull()
                }
                val showHost = host != null && !host.contains("vertexaisearch") &&
                    !source.title.contains(host, ignoreCase = true)
                Text(
                    // First-strong isolate: an English title in the Arabic app otherwise has its
                    // number thrown to the far end ("NPR · .1"). Inside the isolate the line takes
                    // its direction from its own first letters, Latin or Arabic.
                    text = "⁨${index + 1}. ${source.title}" + (if (showHost) " · $host" else "") + "⁩",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(source.url) } }
                        .padding(vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * The model's reasoning, folded to one muted line above the answer (plan item 2).
 *
 * "Thinking… 12 s" while it thinks, "Thought for 12 s" once the answer starts; a tap unfolds the
 * text. No height cap on the unfolded text - it can be Arabic, and a box that cannot grow is the
 * defect CLAUDE.md forbids. Styles come from the theme; no lineHeight is set here.
 */
@Composable
private fun ThinkingRow(thinking: ReplyThinking, hasAnswer: Boolean) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (thinking.answeredAt == null) {
        LaunchedEffect(thinking.startedAt) {
            while (true) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000L)
            }
        }
    }
    val seconds = thinking.seconds(now)
    val label = if (thinking.answeredAt == null) {
        stringResource(R.string.thinking_now_fmt, seconds)
    } else {
        stringResource(R.string.thought_for_fmt, seconds)
    }
    val toggleLabel = stringResource(if (open) R.string.thinking_hide else R.string.thinking_show)
    Column(modifier = Modifier.padding(bottom = if (hasAnswer) 8.dp else 0.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clickable(onClickLabel = toggleLabel) { open = !open }
        ) {
            Icon(
                imageVector = if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                text = thinking.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, top = 4.dp)
            )
        }
    }
}

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
            text = stringResource(
                when (kind) {
                    "edit" -> R.string.editing_photo
                    "search" -> R.string.searching_web
                    "listen" -> R.string.listening_audio
                    else -> R.string.drawing_image
                }
            ),
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
        // thumbnail itself whenever the bytes are there) - which, after a restore, is every clip
        // the app ever rendered.
        message.imagePath != null -> {
            Text(
                text = stringResource(R.string.restore_clip_missing),
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
