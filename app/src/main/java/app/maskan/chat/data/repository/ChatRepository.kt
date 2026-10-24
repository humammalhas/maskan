package app.maskan.chat.data.repository

import android.util.Base64
import app.maskan.chat.BuildConfig
import app.maskan.chat.data.local.ConversationDao
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.DialectVoice
import app.maskan.chat.data.local.DocumentDao
import app.maskan.chat.data.local.DocumentEntity
import app.maskan.chat.data.local.FolderDao
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.data.local.MessageDao
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.PresetCategory
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.local.systemPromptFor
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.ChatCompletionResponse
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.SearchStream
import app.maskan.chat.data.remote.WebSource
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.VideoBackend
import app.maskan.chat.data.remote.VideoJobClient
import app.maskan.chat.data.remote.providers.OnDeviceProvider
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.util.ChatTitle
import app.maskan.chat.util.DocumentChunks
import app.maskan.chat.util.DocumentExtract
import app.maskan.chat.util.ErrorMapper
import app.maskan.chat.util.ImageStore
import app.maskan.chat.util.SendRefused
import app.maskan.chat.util.TokenEstimate
import app.maskan.chat.video.VideoJobs
import app.maskan.chat.video.VideoOptions
import app.maskan.chat.video.VideoRenderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flow

enum class ExportFormat { PLAIN_TEXT, MARKDOWN }

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val keyRepository: KeyRepository,
    private val localeRepository: LocaleRepository,
    private val preferenceRepository: PreferenceRepository,
    private val imageStore: ImageStore,
    private val videoJobClient: VideoJobClient,
    private val videoBackendFor: (String) -> VideoBackend,
    private val videoJobs: VideoJobs,
    /** Room's withTransaction, passed in so a fork's rows are written all at once or not at all. */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    // ── Conversations ──────────────────────────────────────────────────

    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun getConversationById(id: Long): ConversationEntity? =
        conversationDao.getConversationById(id)

    suspend fun createConversation(
        title: String = DEFAULT_TITLE,
        providerId: String = ProviderRegistry.getDefaultProvider().id,
        modelId: String? = null,
        folderId: Long? = null
    ): Long {
        val conversation = ConversationEntity(
            title = title,
            providerId = providerId,
            modelId = modelId,
            folderId = folderId
        )
        return conversationDao.insertConversation(conversation)
    }

    /**
     * Bumped after a conversation is deleted, for screens that cannot trust Room to tell them.
     *
     * Room's invalidation is unreliable under SQLCipher here - it is the reason `refresh()`
     * exists - and a delete that does not re-emit leaves the list drawing a chat that is no
     * longer in the database. Collected by the list, which re-reads when this changes. The
     * bump happens AFTER the delete, so a collector cannot read too early.
     */
    private val _conversationsRevision = MutableStateFlow(0L)
    val conversationsRevision: StateFlow<Long> = _conversationsRevision.asStateFlow()

    suspend fun deleteConversation(id: Long) {
        // Collect the image files FIRST: the foreign-key cascade wipes the message rows, and
        // after that there is nothing left to say which files belonged to this conversation.
        val images = messageDao.getImagePathsForConversation(id)
        conversationDao.deleteConversationById(id)
        if (images.isNotEmpty()) imageStore.delete(images)
        _conversationsRevision.value = _conversationsRevision.value + 1
    }

    /**
     * Point a conversation at a different model, keeping its provider. Used to un-stick a chat
     * whose frozen modelId names a model the provider has retired.
     */
    suspend fun updateConversationModel(id: Long, modelId: String?) {
        conversationDao.updateConversationModel(id, modelId)
    }

    /**
     * Move a chat onto another provider and model, from its next message on (plan item 10).
     *
     * The same path as a changed system prompt: the row keeps the record, the next request reads
     * the row. History is untouched and nothing is re-sent. Once something has been said, the
     * transcript gets ONE notice for the move - and a second move before anything is sent
     * rewrites that notice rather than stacking another, or deletes it when the chat went straight
     * back to where it was.
     */
    suspend fun switchModel(conversationId: Long, providerId: String, modelId: String) {
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val fromModel = conversation.modelId.orEmpty()
        if (conversation.providerId == providerId && fromModel.trim() == modelId.trim()) return
        conversationDao.updateProvider(conversationId, providerId, modelId)

        val rows = messageDao.getMessagesForConversationOnce(conversationId)
        // Nothing said yet: no model has answered, so there is no answer to tell apart.
        if (rows.none { it.role == "user" || it.role == "assistant" }) return

        val last = rows.lastOrNull { it.role != "system" }
        val pending = last?.takeIf { it.role == ROLE_NOTICE }?.let { ModelSwitch.decode(it.content) }
        if (last != null && pending != null) {
            val move = pending.copy(toProvider = providerId, toModel = modelId)
            if (move.isNoOp) {
                messageDao.deleteMessageById(last.id)
            } else {
                messageDao.updateMessageContent(last.id, ModelSwitch.encode(move))
            }
            return
        }
        saveMessage(
            conversationId,
            ROLE_NOTICE,
            ModelSwitch.encode(ModelSwitch.Move(conversation.providerId, fromModel, providerId, modelId))
        )
    }

    suspend fun updateConversationTitle(id: Long, title: String) {
        conversationDao.updateConversationTitle(id, title)
    }

    suspend fun updateSystemPrompt(id: Long, systemPromptId: String?, dialectId: String?) {
        conversationDao.updateSystemPrompt(id, systemPromptId, dialectId)
    }

    suspend fun moveConversationToFolder(conversationId: Long, folderId: Long?) {
        conversationDao.moveToFolder(conversationId, folderId)
    }

    suspend fun searchConversations(query: String): List<ConversationEntity> {
        val titleMatches = conversationDao.searchConversationsByTitle(query)
        val messageMatchIds = messageDao.searchMessages(query)
        val messageMatches = if (messageMatchIds.isNotEmpty()) {
            conversationDao.getConversationsByIds(messageMatchIds)
        } else {
            emptyList()
        }
        return (titleMatches + messageMatches)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
    }

    /**
     * The first line the user wrote in each conversation, keyed by conversation id.
     *
     * One query for the whole list. Only the first LINE: a message can be a pasted contract,
     * and what belongs under a title is the opening of the question, not the question.
     */
    suspend fun getFirstUserLines(): Map<Long, String> =
        messageDao.getFirstUserMessages().associate { message ->
            message.conversationId to
                message.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }

    // ── Folders ────────────────────────────────────────────────────────

    fun getAllFolders(): Flow<List<FolderEntity>> = folderDao.getAll()

    suspend fun createFolder(name: String, colorHex: String? = null): Long {
        return folderDao.insert(FolderEntity(name = name, colorHex = colorHex))
    }

    suspend fun renameFolder(id: Long, newName: String) {
        folderDao.rename(id, newName)
    }

    suspend fun updateFolderColor(id: Long, colorHex: String) {
        folderDao.updateColor(id, colorHex)
    }

    suspend fun getFolder(id: Long): FolderEntity? = folderDao.getById(id)

    suspend fun updateFolderInstructions(id: Long, text: String?) {
        folderDao.updateInstructions(id, text?.takeIf { it.isNotBlank() })
    }

    suspend fun updateFolderMemory(id: Long, text: String?) {
        folderDao.updateMemory(id, text?.takeIf { it.isNotBlank() })
    }

    suspend fun deleteFolder(id: Long) {
        folderDao.delete(id)
    }

    // ── Export ─────────────────────────────────────────────────────────

    suspend fun exportConversation(conversationId: Long, format: ExportFormat): String {
        val conversation = conversationDao.getConversationById(conversationId)
        val title = conversation?.title ?: "Chat"
        val messages = messageDao.getMessagesForConversationOnce(conversationId)
            .filter { it.role != "system" }

        return buildString {
            appendLine("# $title")
            appendLine()
            when (format) {
                // A reply the user rewrote says so here too: an export is the copy that leaves the
                // phone, and it must not claim the model wrote what the user did (plan item 9).
                ExportFormat.PLAIN_TEXT -> {
                    for (msg in messages) {
                        val label = when {
                            msg.role == "user" -> "You"
                            msg.editedAt != null -> "AI (edited)"
                            else -> "AI"
                        }
                        appendLine("$label: ${msg.content}")
                    }
                }
                ExportFormat.MARKDOWN -> {
                    for ((index, msg) in messages.withIndex()) {
                        val label = when {
                            msg.role == "user" -> "**You:**"
                            msg.editedAt != null -> "**AI (edited):**"
                            else -> "**AI:**"
                        }
                        appendLine("$label ${msg.content}")
                        appendLine()
                        if (index < messages.lastIndex) {
                            appendLine("---")
                            appendLine()
                        }
                    }
                }
            }
        }.trimEnd()
    }

    // ── Messages ───────────────────────────────────────────────────────

    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    suspend fun saveMessage(
        conversationId: Long,
        role: String,
        content: String,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )
        return messageDao.insertMessage(message)
    }

    // ── Documents ──────────────────────────────────────────────────────

    fun documentsFor(conversationId: Long): Flow<List<DocumentEntity>> =
        documentDao.getForConversation(conversationId)

    /**
     * Read once, for the screen.
     *
     * Not the Flow: Room's invalidation is unreliable under SQLCipher (the message list is
     * driven the same way for the same reason), and the notes card must show 12 of 30 the
     * moment the twelfth chunk lands, not whenever Room notices.
     */
    suspend fun documentsOnce(conversationId: Long): List<DocumentEntity> =
        documentDao.getForConversationOnce(conversationId)

    suspend fun getDocument(id: Long): DocumentEntity? = documentDao.getById(id)

    suspend fun deleteDocument(id: Long) = documentDao.delete(id)

    suspend fun setDocumentPages(id: Long, messageIds: List<Long>) {
        documentDao.updatePageImageIds(id, messageIds.joinToString(",").ifBlank { null })
    }

    suspend fun setDocumentMessage(id: Long, messageId: Long?) {
        documentDao.updateAttachedMessage(id, messageId)
    }

    /**
     * What a request to [providerId] may spend, and on what.
     *
     * Three cases, in order of how much is known. A provider that states its window (the
     * on-device one, whose `.task` file has a fixed KV cache) gets budgets derived from that
     * number. A local server does not state one - it could be running anything - and keeps the
     * numbers verified against the AI PC in session 4. A cloud provider keeps 2.5's.
     */
    private fun budgetFor(providerId: String): RequestBudget {
        val provider = ProviderRegistry.getProvider(providerId)
        val window = provider?.contextTokens
            ?: return if (provider?.isLocal == true) {
                RequestBudget(
                    system = MAX_SYSTEM_TOKENS,
                    document = MAX_DOCUMENT_TOKENS_LOCAL,
                    chunk = DocumentChunks.CHUNK_TOKENS_LOCAL,
                    chunksPerQuestion = DocumentChunks.CHUNKS_PER_QUESTION,
                    window = null
                )
            } else {
                RequestBudget(
                    system = MAX_SYSTEM_TOKENS,
                    document = MAX_DOCUMENT_TOKENS,
                    chunk = DocumentChunks.CHUNK_TOKENS,
                    chunksPerQuestion = DocumentChunks.CHUNKS_PER_QUESTION,
                    window = null
                )
            }

        // Everything below is division of one number. The shares are stated as constants
        // because they are judgements, and a judgement with a name can be argued with.
        val answer = (window * ANSWER_SHARE).toInt()
        val headroom = (window * HEADROOM_SHARE).toInt()
        val usable = (window - answer - headroom).coerceAtLeast(MIN_USABLE_TOKENS)
        val system = round100(usable * SYSTEM_SHARE)
        val document = round100(usable * DOCUMENT_SHARE)
        return RequestBudget(
            system = system,
            document = document,
            // Half the document budget, because exactly one excerpt goes with a question here:
            // two quarter-sized excerpts from a file read in 1,000-token chunks would send a
            // quarter of each chunk and pay for the other three quarters twice.
            chunk = (document / 2).coerceAtLeast(MIN_CHUNK_TOKENS),
            chunksPerQuestion = 1,
            window = window
        )
    }

    private fun round100(value: Double): Int = (value.toInt() / 100) * 100

    /**
     * What one request may spend. [window] is null when the model's own window is not knowable,
     * which is every provider but the on-device one.
     */
    private data class RequestBudget(
        val system: Int,
        val document: Int,
        val chunk: Int,
        val chunksPerQuestion: Int,
        val window: Int?
    )

    /**
     * The chunk size this conversation's provider can afford.
     *
     * Persisted with the document, because it is not a constant: a chat moved from a cloud
     * provider to the on-device one mid-pass would otherwise resume against a different set of
     * chunks than the ones already summarised.
     */
    suspend fun chunkTokensFor(conversationId: Long): Int {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: return DocumentChunks.CHUNK_TOKENS
        return budgetFor(conversation.providerId).chunk
    }

    /**
     * Store an extracted file against a conversation.
     *
     * chunkCount is 0 for a document small enough to be pasted into the message whole - that is
     * the flag everything downstream reads as "this one needs no notes pass and must not be
     * added to the request a second time, because its text is already in the history".
     */
    suspend fun saveDocument(
        conversationId: Long,
        doc: DocumentExtract.Doc,
        attachedMessageId: Long? = null
    ): Long {
        val chunkTokens = chunkTokensFor(conversationId)
        val tokens = TokenEstimate.of(doc.text)
        val chunkCount = if (tokens > DocumentChunks.INLINE_CEILING_TOKENS) {
            DocumentChunks.chunk(doc.text, chunkTokens).size
        } else {
            0
        }
        return documentDao.insert(
            DocumentEntity(
                conversationId = conversationId,
                attachedMessageId = attachedMessageId,
                name = doc.name,
                kind = doc.kind,
                pages = doc.pages,
                tokens = tokens,
                text = doc.text,
                chunkCount = chunkCount,
                chunkTokens = chunkTokens,
                warning = doc.warning
            )
        )
    }

    sealed class NotesEvent {
        data class Progress(val done: Int, val total: Int) : NotesEvent()

        /** Rate limited; waiting [seconds] before trying the same chunk again. */
        data class Waiting(val seconds: Int) : NotesEvent()

        data class Stopped(val error: Throwable) : NotesEvent()
        data object Finished : NotesEvent()
    }

    /**
     * Summarise the document's remaining chunks, one request at a time.
     *
     * Sequential rather than parallel on purpose. A free-tier key answers a burst of thirty
     * requests with 429s, and the pass that was supposed to make a long file usable instead
     * fails halfway; one at a time with a backoff finishes, slowly, on every key there is.
     *
     * Every chunk is written with the count that describes it in one statement, so the pass
     * resumes exactly where it stopped - a process killed mid-pass loses the request in flight
     * and nothing else. Nothing here starts on its own: the user taps Continue, because these
     * are their requests being spent.
     */
    fun readDocument(documentId: Long): Flow<NotesEvent> = flow {
        while (true) {
            val document = documentDao.getById(documentId) ?: return@flow
            if (document.chunkCount <= 0 || document.notesDone >= document.chunkCount) {
                emit(NotesEvent.Finished)
                return@flow
            }
            val conversation = conversationDao.getConversationById(document.conversationId)
                ?: return@flow

            val chunks = DocumentChunks.chunk(document.text, document.chunkTokens)
            val index = document.notesDone
            val chunk = chunks.getOrNull(index)
            if (chunk == null) {
                // The stored count and the re-cut text disagree, which should not happen and
                // must not spin: call the pass done rather than ask for chunk 29 of 28 forever.
                documentDao.updateNotes(documentId, document.notes, document.chunkCount)
                emit(NotesEvent.Finished)
                return@flow
            }

            var attempt = 0
            while (true) {
                try {
                    val note = summariseChunk(conversation, chunk, index + 1, chunks.size)
                    val merged = listOfNotNull(
                        document.notes?.takeIf { it.isNotBlank() },
                        note
                    ).joinToString("\n\n")
                    documentDao.updateNotes(documentId, merged, index + 1)
                    emit(NotesEvent.Progress(index + 1, chunks.size))
                    break
                } catch (e: Exception) {
                    val code = ErrorMapper.httpCode(e)
                    val worthWaiting = (code == 429 || code == 503) && attempt < NOTES_MAX_RETRIES
                    if (!worthWaiting) {
                        emit(NotesEvent.Stopped(e))
                        return@flow
                    }
                    val seconds = NOTES_BACKOFF_SECONDS shl attempt
                    emit(NotesEvent.Waiting(seconds))
                    delay(seconds * 1000L)
                    attempt++
                }
            }
        }
    }

    private suspend fun summariseChunk(
        conversation: ConversationEntity,
        chunk: String,
        number: Int,
        total: Int
    ): String {
        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: " + providerId)

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        if (apiKey.isBlank() && provider.requiresApiKey) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }
        val model = conversation.modelId
            ?: keyRepository.getSelectedModel(providerId)
            ?: provider.defaultModel

        // No history and no folder instructions: this is an indexing job, not a conversation,
        // and sending the chat's context with all thirty of these would multiply the cost of
        // reading a file by the length of the chat it was dropped into.
        val instruction = Message(
            role = "system",
            text = "You are indexing one part of a longer document so that it can be found " +
                "again later. Summarise the excerpt in AT MOST five short lines. Keep names, " +
                "dates, numbers, amounts and defined terms exactly as they are written. Write " +
                "in the same language as the excerpt. Reply with the lines alone - no " +
                "preamble, no heading, no commentary."
        )
        val summary = provider.sendMessage(
            apiKey,
            model,
            listOf(instruction, Message(role = "user", text = chunk)),
            keyRepository.getBaseUrl(providerId)
        ).trim()

        if (summary.isBlank()) throw Exception("Empty response from " + provider.displayName)
        return "[" + number + "/" + total + "] " + summary
    }

    /**
     * What this question needs from the conversation's documents, or null.
     *
     * Only documents with chunks: one small enough to have been pasted into the message is
     * already in the history, and repeating it here would send it twice.
     */
    private fun documentBlock(
        docs: List<DocumentEntity>,
        question: String,
        budget: RequestBudget
    ): String? {
        val readable = docs.filter { it.chunkCount > 0 && it.text.isNotBlank() }
        if (readable.isEmpty()) return null

        val perDocument = (budget.document / readable.size).coerceAtLeast(400)
        val blocks = readable.map {
            oneDocumentBlock(it, question, perDocument, budget.chunksPerQuestion)
        }
        return blocks.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun oneDocumentBlock(
        document: DocumentEntity,
        question: String,
        budget: Int,
        chunksPerQuestion: Int
    ): String {
        val header = StringBuilder()
        header.append("### From the file \"").append(document.name).append("\"")
        if (document.pages > 0) header.append(" (").append(document.pages).append(" pages)")
        header.append("\n")
        header.append(
            "The user attached this file. Answer from it when the question is about it, and " +
                "say which part an answer came from."
        )
        if (document.partial) {
            // The honest sentence, and the reason the Continue button is worth tapping: a model
            // asked about the half of a contract nobody has read yet must say so, not invent it.
            header.append(
                "\nOnly " + document.notesDone + " of " + document.chunkCount +
                    " parts have been read so far. If the answer is not in what follows, say " +
                    "that this part of the file has not been read yet - do not guess."
            )
        }
        if (document.warning == DocumentExtract.WARN_ARABIC) {
            header.append(
                "\nThe Arabic in this file was stored as letter shapes and its word order may " +
                    "be reversed. If a passage reads as nonsense, say so instead of guessing."
            )
        }

        // Notes first and clamped least: they are what the model has actually been told about
        // the parts the excerpts do not cover.
        val notesBudget = (budget * 0.45).toInt().coerceAtLeast(200)
        val notes = document.notes?.takeIf { it.isNotBlank() }
            ?.let { TokenEstimate.clamp(it, notesBudget) }

        val chunks = DocumentChunks.chunk(document.text, document.chunkTokens)
        val picked = DocumentChunks.rank(question, chunks, chunksPerQuestion)
        val excerptBudget = (budget - TokenEstimate.of(notes) - TokenEstimate.of(header.toString()))
            .coerceAtLeast(200)
        val perExcerpt = (excerptBudget / picked.size.coerceAtLeast(1)).coerceAtLeast(150)
        val excerpts = picked.mapNotNull { index ->
            chunks.getOrNull(index)?.let { TokenEstimate.clamp(it, perExcerpt) }
        }

        val out = StringBuilder(header)
        if (notes != null) out.append("\n\n#### Summary of the whole file\n").append(notes)
        if (excerpts.isNotEmpty()) {
            out.append("\n\n#### The parts closest to this question\n")
            out.append(excerpts.joinToString("\n\n---\n\n"))
        }
        return out.toString()
    }

    // ── API Call ───────────────────────────────────────────────────────

    suspend fun sendMessage(
        conversationId: Long,
        userContent: String,
        model: String = "deepseek-chat"
    ): Result<ChatCompletionResponse> {
        var userMessageId: Long? = null
        return try {
            val conversation = conversationDao.getConversationById(conversationId)
                ?: return Result.failure(Exception("Conversation not found"))

            val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
            val hasSystemMessage = existingMessages.any { it.role == "system" }

            if (conversation.systemPromptId != null && !hasSystemMessage) {
                val preset = resolvePreset(conversation)
                if (preset != null) {
                    val systemContent = preset.systemPromptFor(effectiveLanguage())
                    if (systemContent.isNotBlank()) {
                        saveMessage(conversationId, "system", systemContent)
                    }
                }
            }

            userMessageId = saveMessage(conversationId, "user", userContent)

            val messages = buildMessageList(conversation, conversation.modelId ?: model)
            noteVoiceDropIfNeeded(conversationId)

            val providerId = conversation.providerId
            val provider = ProviderRegistry.getProvider(providerId)
                ?: run {
                    messageDao.deleteMessageById(userMessageId)
                    return Result.failure(Exception("Unknown provider: $providerId"))
                }

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            if (apiKey.isBlank() && provider.requiresApiKey) {
                messageDao.deleteMessageById(userMessageId)
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val effectiveModel = conversation.modelId ?: model

            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val assistantContent = provider.sendMessage(apiKey, effectiveModel, messages, storedBaseUrl)

            saveMessage(conversationId, "assistant", assistantContent)

            if (conversation.title == DEFAULT_TITLE) {
                conversationDao.updateConversationTitle(conversationId, fallbackTitle(userContent))
            }

            Result.success(ChatCompletionResponse(
                id = "",
                obj = "chat.completion",
                created = System.currentTimeMillis() / 1000,
                model = effectiveModel,
                choices = emptyList(),
                usage = null
            ))
        } catch (e: Exception) {
            userMessageId?.let { messageDao.deleteMessageById(it) }
            Result.failure(e)
        }
    }

    private fun resolvePreset(conversation: ConversationEntity) =
        when (conversation.systemPromptId) {
            // "No preset at all" is a CHOICE, stored as an id, and it is not the same thing as
            // null - null still means "the user has not been asked yet", which is what raises
            // the picker. Both resolve to no preset here; only one of them shows a screen.
            PRESET_NONE -> null
            "en_to_ar" -> {
                val dialect = conversation.dialectId?.let { Dialect.fromId(it) } ?: Dialect.MSA
                Presets.enToArPreset(dialect)
            }
            "custom" -> null
            else -> conversation.systemPromptId?.let { Presets.getById(it) }
        }

    fun sendMessageStreaming(
        conversationId: Long,
        userContent: String,
        model: String = "deepseek-chat",
        imageData: ByteArray? = null,
        imageMimeType: String? = null,
        /** This one message asks the provider to search the web first (2.7). */
        webSearch: Boolean = false
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
        val hasSystemMessage = existingMessages.any { it.role == "system" }

        if (conversation.systemPromptId != null && !hasSystemMessage) {
            val preset = resolvePreset(conversation)
            if (preset != null) {
                val systemContent = preset.systemPromptFor(effectiveLanguage())
                if (systemContent.isNotBlank()) {
                    saveMessage(conversationId, "system", systemContent)
                }
            }
        }

        val imageBase64ForStorage = imageData?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        val userEntity = MessageEntity(
            conversationId = conversationId,
            role = "user",
            content = userContent,
            imageBase64 = imageBase64ForStorage,
            imageMimeType = imageMimeType
        )
        val userMessageId = messageDao.insertMessage(userEntity)
        // Drive the open chat from in-memory state: emit the saved user message so the
        // ViewModel can show it immediately, independent of the (unreliable under SQLCipher)
        // Room invalidation Flow.
        emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))

        streamAssistantReply(conversation, model, imageData, imageMimeType, webSearch)

        if (conversation.title == DEFAULT_TITLE) {
            conversationDao.updateConversationTitle(conversationId, fallbackTitle(userContent))
        }

        emit(StreamEvent.Done)
    }

    /**
     * Re-run the last user turn WITHOUT inserting it again.
     *
     * A failed send leaves the user message in the DB - it is saved and emitted before the
     * provider is ever called - and deletes only the empty assistant placeholder. So the retry
     * after moving a conversation off a retired model must not go through sendMessageStreaming,
     * which would duplicate the user's bubble. Same streaming tail, no user insert, no title
     * rewrite.
     */
    fun regenerateLastReply(conversationId: Long): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        // buildMessageList carries text only; an attached image lives on the message row, so
        // recover it from the last user turn or the retry silently drops the attachment.
        val lastUser = messageDao.getMessagesForConversationOnce(conversationId)
            .lastOrNull { it.role == "user" }
        val imageData = lastUser?.imageBase64?.let { Base64.decode(it, Base64.NO_WRAP) }

        streamAssistantReply(
            conversation = conversation,
            model = conversation.modelId ?: "",
            imageData = imageData,
            imageMimeType = lastUser?.imageMimeType
        )

        emit(StreamEvent.Done)
    }

    /**
     * Turn a rough description into a prompt an image model can actually use, using the CHAT
     * model the user already has selected.
     *
     * Two jobs in one call. Image models are trained overwhelmingly on English and answer a
     * short Arabic or Thai phrase poorly, so this bridges the language; and they respond to
     * concrete visual detail (subject, setting, light, style) that a person typing three words
     * has not supplied. The result is handed BACK to the user to edit rather than sent
     * straight on - a silent rewrite of what someone asked for is not help, it is substitution.
     *
     * Runs on the chat model, not the image model, so it costs a few cents of text at most.
     */
    suspend fun improveImagePrompt(conversationId: Long, rough: String): Result<String> {
        return try {
            val conversation = conversationDao.getConversationById(conversationId)
                ?: return Result.failure(Exception("Conversation not found"))

            val providerId = conversation.providerId
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            if (apiKey.isBlank() && provider.requiresApiKey) {
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val model = conversation.modelId
                ?: keyRepository.getSelectedModel(providerId)
                ?: provider.defaultModel

            val instruction = Message(
                role = "system",
                text = "You write prompts for image-generation models. Rewrite the user's " +
                    "description as ONE vivid English image prompt. Translate it if it is not " +
                    "in English. Keep every subject the user named and add only concrete " +
                    "visual detail: setting, lighting, colour, composition, style. Do not add " +
                    "people, text or objects they did not mention. Reply with the prompt alone " +
                    "- no quotes, no preamble, no explanation."
            )
            val ask = Message(role = "user", text = rough)

            val improved = provider.sendMessage(
                apiKey,
                model,
                listOf(instruction, ask),
                keyRepository.getBaseUrl(providerId)
            ).trim().trim('"')

            if (improved.isBlank()) {
                Result.failure(Exception("Empty response from ${provider.displayName}"))
            } else {
                Result.success(improved)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ask the provider to draw [prompt] and land the result as an image bubble in this chat.
     *
     * Shaped as the same StreamEvent flow as a chat turn so the ViewModel needs no second
     * pipeline, even though nothing streams: the prompt is saved as the user's message, an empty
     * assistant placeholder appears while the provider works, and ImageReady swaps in the
     * finished picture. On failure the placeholder is removed exactly as a failed chat turn does,
     * so a dead request never leaves a blank bubble behind.
     *
     * The image model is a SEPARATE preference from the chat model - asking for a picture must
     * not cost the user the chat model they picked.
     */
    fun generateImage(conversationId: Long, prompt: String, size: String? = null): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val isLocalProvider = provider.supportsCustomBaseUrl
        if (apiKey.isBlank() && provider.requiresApiKey) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }

        val model = keyRepository.getSelectedImageModel(providerId)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("no image model selected")

        // A model the user's own server lists as a VIDEO model takes the async job path instead
        // of the blocking image request. The server says which ids those are (GET /health) so
        // nothing is hardcoded here; a server without /health is simply an image server.
        val baseUrl = keyRepository.getBaseUrl(providerId)
        if (isLocalProvider && !baseUrl.isNullOrBlank()) {
            val capabilities = withContext(Dispatchers.IO) { videoJobClient.probe(baseUrl, apiKey) }
            if (capabilities != null && model in capabilities.videoModels) {
                submitVideo(conversation, providerId, apiKey, baseUrl, model, prompt, null,
                    VideoOptions.DEFAULT_SIZE, VideoOptions.DEFAULT_SECONDS)
                return@flow
            }
        }

        val userEntity = MessageEntity(
            conversationId = conversationId,
            role = "user",
            content = prompt
        )
        val userMessageId = messageDao.insertMessage(userEntity)
        emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = ""
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        emit(StreamEvent.Started(assistantEntity.copy(id = assistantMessageId)))

        try {
            val image = provider.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                baseUrl = keyRepository.getBaseUrl(providerId),
                // Only the user's own server takes a free-form WxH; cloud vocabularies differ.
                size = if (isLocalProvider) size else null
            )
            val fileName = imageStore.save(image.bytes)
            messageDao.updateImagePath(assistantMessageId, fileName, image.mimeType)
            emit(
                StreamEvent.ImageReady(
                    assistantEntity.copy(
                        id = assistantMessageId,
                        imagePath = fileName,
                        imageMimeType = image.mimeType
                    )
                )
            )
        } catch (e: Exception) {
            messageDao.deleteMessageById(assistantMessageId)
            throw e
        }

        if (conversation.title == DEFAULT_TITLE) {
            conversationDao.updateConversationTitle(conversationId, fallbackTitle(prompt))
        }

        emit(StreamEvent.Done)
    }

    /**
     * Start a video and return at once. Nothing here waits for the render: the job is
     * submitted, its id is written into the assistant row, and a WorkManager worker takes over
     * (VideoRenderWorker) - that worker, not this flow, survives the screen locking. The bubble
     * shows progress from the worker until the clip replaces it.
     *
     * The prompt goes to the server AS TYPED, in any language: a server that serves the job
     * API expands and translates it itself (prompt_expanded), and expanding an already
     * translated prompt would only lose the user's own words.
     */
    private suspend fun FlowCollector<StreamEvent>.submitVideo(
        conversation: ConversationEntity,
        providerId: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        /** An attached photo makes it photo-to-video: the photo is what moves. */
        image: Pair<ByteArray, String>?,
        size: String,
        seconds: Int,
        saveUserMessage: Boolean = true
    ) {
        val conversationId = conversation.id
        if (saveUserMessage) {
            val userEntity = MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = prompt,
                imageBase64 = image?.let { Base64.encodeToString(it.first, Base64.NO_WRAP) },
                imageMimeType = image?.second
            )
            val userMessageId = messageDao.insertMessage(userEntity)
            emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))
        }

        // Submit BEFORE creating the assistant row: a refused request then fails like a failed
        // chat turn (the user's message stays, nothing else appears) with no placeholder to undo.
        val jobId = withContext(Dispatchers.IO) {
            videoBackendFor(providerId).submit(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                seconds = seconds,
                size = size,
                enhance = !VideoOptions.isCloud(providerId),
                imageDataUri = image?.let { (bytes, mime) ->
                    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            )
        }

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = "",
            imageMimeType = VideoRenderWorker.VIDEO_MIME,
            videoJobId = jobId
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        videoJobs.enqueue(assistantMessageId, conversationId, providerId)
        emit(StreamEvent.VideoQueued(assistantEntity.copy(id = assistantMessageId)))

        if (conversation.title == DEFAULT_TITLE) {
            conversationDao.updateConversationTitle(conversationId, fallbackTitle(prompt))
        }

        emit(StreamEvent.Done)
    }

    /**
     * Edit the attached photo. The user's bubble keeps the original (attached-image shape,
     * imageBase64) so before and after sit one above the other; the result lands in the same
     * stored-image bubble a drawing does. Blocking like a drawing - an edit is ~2-3 minutes on
     * a home GPU, inside the image client's timeout.
     */
    fun editImage(
        conversationId: Long,
        prompt: String,
        model: String,
        imageBytes: ByteArray,
        imageMimeType: String
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")
        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")
        val apiKey = keyRepository.getApiKey(providerId) ?: ""

        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val userEntity = MessageEntity(
            conversationId = conversationId,
            role = "user",
            content = prompt,
            imageBase64 = base64,
            imageMimeType = imageMimeType
        )
        val userMessageId = messageDao.insertMessage(userEntity)
        emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = ""
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        emit(StreamEvent.Started(assistantEntity.copy(id = assistantMessageId)))

        try {
            val image = provider.editImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                imageDataUri = "data:$imageMimeType;base64,$base64",
                baseUrl = keyRepository.getBaseUrl(providerId)
            )
            val fileName = imageStore.save(image.bytes)
            messageDao.updateImagePath(assistantMessageId, fileName, image.mimeType)
            emit(
                StreamEvent.ImageReady(
                    assistantEntity.copy(
                        id = assistantMessageId,
                        imagePath = fileName,
                        imageMimeType = image.mimeType
                    )
                )
            )
        } catch (e: Exception) {
            messageDao.deleteMessageById(assistantMessageId)
            throw e
        }

        if (conversation.title == DEFAULT_TITLE) {
            conversationDao.updateConversationTitle(conversationId, fallbackTitle(prompt))
        }
        emit(StreamEvent.Done)
    }

    /**
     * Make a video from the composer's dedicated video entry: the chosen VIDEO model, the
     * prompt as typed, and the attached photo if there is one. Same flow shape as
     * [generateImage]; the render itself happens in the worker.
     */
    fun generateVideo(
        conversationId: Long,
        prompt: String,
        imageBytes: ByteArray?,
        imageMimeType: String?,
        size: String,
        seconds: Int,
        /** False on a retry: the user's message is already in the chat. */
        saveUserMessage: Boolean = true
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")
        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")
        if (!provider.supportsVideoGeneration) throw Exception("no video model selected")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val baseUrl = keyRepository.getBaseUrl(providerId)?.takeIf { it.isNotBlank() }
            ?: provider.defaultBaseUrl.takeIf { it.isNotBlank() }
            ?: throw Exception("No server URL configured. Please enter your ${provider.displayName} server URL in Settings.")
        val chosen = keyRepository.getSelectedVideoModel(providerId)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("no video model selected")

        val image = if (imageBytes != null && imageMimeType != null) imageBytes to imageMimeType else null
        // Venice names the two jobs separately - "wan-2-7-text-to-video" refuses a photo and
        // "wan-2-7-image-to-video" needs one - so the chosen family follows what was sent
        // rather than making the user re-pick a model per message.
        val model = when {
            providerId != "venice" -> chosen
            image != null -> chosen.replace("text-to-video", "image-to-video")
            else -> chosen.replace("image-to-video", "text-to-video")
        }
        submitVideo(conversation, providerId, apiKey, baseUrl, model, prompt, image, size, seconds, saveUserMessage)
    }

    /**
     * Stop waiting for a video and drop its bubble. The server is told to cancel (best effort -
     * it forgets the job on its own if unreachable) and the worker is stopped.
     */
    suspend fun cancelVideo(messageId: Long) {
        val row = messageDao.getMessageById(messageId) ?: return
        videoJobs.cancel(messageId)
        val jobId = row.videoJobId
        if (jobId != null) {
            val providerId = conversationDao.getConversationById(row.conversationId)?.providerId
            val baseUrl = providerId?.let { keyRepository.getBaseUrl(it) }
            if (providerId != null && !baseUrl.isNullOrBlank()) {
                val apiKey = keyRepository.getApiKey(providerId) ?: ""
                withContext(Dispatchers.IO) {
                    runCatching { videoBackendFor(providerId).cancel(baseUrl, apiKey, jobId) }
                }
            }
        }
        messageDao.deleteMessageById(messageId)
    }

    suspend fun deleteMessage(messageId: Long) {
        messageDao.deleteMessageById(messageId)
    }

    /** The provider's own price for a clip, when it will quote one (Venice); null otherwise. */
    suspend fun quoteVideo(providerId: String, model: String, seconds: Int, size: String): Double? {
        if (model.isBlank()) return null
        val quoter = videoBackendFor(providerId) as? app.maskan.chat.data.remote.VideoQuoter ?: return null
        val provider = ProviderRegistry.getProvider(providerId) ?: return null
        val baseUrl = keyRepository.getBaseUrl(providerId)?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        return withContext(Dispatchers.IO) {
            runCatching { quoter.quote(baseUrl, apiKey, model, seconds, size) }.getOrNull()
        }
    }

    /** One-shot read, for refreshing the open chat after a background worker changed a row. */
    suspend fun getMessagesOnce(conversationId: Long): List<MessageEntity> =
        messageDao.getMessagesForConversationOnce(conversationId)

    /**
     * The shared tail of both paths: build the context window, insert the assistant
     * placeholder, stream tokens into it, and clean up an empty placeholder on failure.
     */
    private suspend fun FlowCollector<StreamEvent>.streamAssistantReply(
        conversation: ConversationEntity,
        model: String,
        imageData: ByteArray?,
        imageMimeType: String?,
        webSearch: Boolean = false
    ) {
        val conversationId = conversation.id
        val messages = buildMessageList(conversation, conversation.modelId ?: model)
        noteVoiceDropIfNeeded(conversationId)

        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        if (apiKey.isBlank() && provider.requiresApiKey) {
            // Names the provider: after a mid-chat switch the user may not remember which one
            // this chat is on now, and "add your key" without a name sends them hunting.
            throw SendRefused(SendRefused.Kind.NO_KEY, providerId)
        }

        val effectiveModel = conversation.modelId ?: model
        val storedBaseUrl = keyRepository.getBaseUrl(providerId)

        // The hard cases of a moved chat (plan item 10) apply ONLY to a moved chat: one still on
        // the model it was born with sends exactly what it sent before 2.7.
        val switched = ModelSwitch.isSwitched(
            messageDao.getMessagesForConversationOnce(conversationId).map { it.role to it.content }
        )
        if (switched && photoUnreadable(conversation, effectiveModel, imageData)) {
            // The name the header shows: "Qwen2.5 1.5B", not the file's id.
            throw SendRefused(
                SendRefused.Kind.PHOTO_UNREADABLE,
                (provider as? OnDeviceProvider)?.model?.displayName ?: effectiveModel
            )
        }

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = ""
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        // Emit the empty assistant placeholder (with its real id) so the ViewModel inserts a
        // bubble keyed by that id - every following Token updates that exact message.
        emit(StreamEvent.Started(assistantEntity.copy(id = assistantMessageId)))

        try {
            val fullContent = StringBuilder()
            // A search's sources arrive on the stream's own thread, from inside the provider's
            // parser; a flow may only emit from its collector's coroutine, so they are held here
            // and handed on with the next token - and once more after the last one, because
            // Gemini sends its sources in a final chunk that carries no text.
            val found = AtomicReference(emptyList<WebSource>())
            var published = emptyList<WebSource>()
            val stream = if (webSearch) {
                if (!provider.searchesWeb(effectiveModel)) {
                    throw Exception("${provider.displayName} cannot search the web with $effectiveModel")
                }
                provider.sendMessageStreamingWithSearch(
                    apiKey, effectiveModel, messages, storedBaseUrl,
                    imageData, imageMimeType
                ) { more -> found.updateAndGet { SearchStream.merge(it, more) } }
            } else if (switched && provider is OnDeviceProvider) {
                // Moved here from a bigger window: the whole history fits or the send is refused.
                provider.sendMessageStreamingWhole(messages)
            } else {
                provider.sendMessageStreaming(
                    apiKey, effectiveModel, messages, storedBaseUrl,
                    imageData, imageMimeType
                )
            }
            stream.collect { token ->
                fullContent.append(token)
                // A search answer can open with the paragraph break SearchStream puts where the
                // results land; nothing was said before it, so it is not shown.
                val snapshot = if (webSearch) fullContent.toString().trimStart() else fullContent.toString()
                messageDao.updateMessageContent(assistantMessageId, snapshot)
                emit(StreamEvent.Token(assistantMessageId, snapshot))
                val sources = found.get()
                if (sources !== published) {
                    published = sources
                    publishSources(assistantMessageId, sources)
                }
            }
            found.get().let { if (it !== published) publishSources(assistantMessageId, it) }

            val finalContent = fullContent.toString()
            if (finalContent.isBlank()) {
                throw Exception("Empty response from ${provider.displayName}")
            }
        } catch (e: Exception) {
            val current = messageDao.getMessagesForConversationOnce(conversationId)
                .find { it.id == assistantMessageId }
            if (current == null || current.content.isBlank()) {
                messageDao.deleteMessageById(assistantMessageId)
            }
            throw e
        }
    }
    sealed class StreamEvent {
        data class UserSaved(val message: MessageEntity) : StreamEvent()
        data class Started(val message: MessageEntity) : StreamEvent()
        data class Token(val messageId: Long, val fullContent: String) : StreamEvent()

        /** A generated image finished and was written to disk; the entity carries its path. */
        data class ImageReady(val message: MessageEntity) : StreamEvent()

        /**
         * A video job was accepted by the server; the entity carries its job id and no file yet.
         * The flow ends here - a WorkManager worker delivers the clip minutes later.
         */
        data class VideoQueued(val message: MessageEntity) : StreamEvent()

        /** The pages a web search read so far for this answer; already saved on its row. */
        data class Sources(val messageId: Long, val sources: List<WebSource>) : StreamEvent()
        data object Done : StreamEvent()
    }

    private suspend fun FlowCollector<StreamEvent>.publishSources(messageId: Long, sources: List<WebSource>) {
        messageDao.updateMessageSources(messageId, SearchStream.encode(sources))
        emit(StreamEvent.Sources(messageId, sources))
    }

    suspend fun fetchModels(providerId: String): Result<app.maskan.chat.data.remote.providers.FetchedModels> {
        return try {
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))
            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val models = provider.fetchModels(apiKey, storedBaseUrl)
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Everything the app can honestly say about what this key can do, gathered in one pass:
     * what the catalogue offers, whether a real chat call answers, and the account balance
     * where the provider exposes one. This exists because "is it free?" is mostly unknowable
     * (a 200 looks identical on free and paid tiers) - but "does it work with YOUR key, and
     * why not" is always knowable, and that is the question users actually have.
     */
    data class KeyCapabilityReport(
        val chatFailure: Throwable?,   // null = chat answered
        val modelCount: Int,
        val freeCount: Int,
        val imageSupported: Boolean,
        val imageModelCount: Int,
        val balance: String?,          // formatted, only OpenRouter/DeepSeek publish one
        val isLocal: Boolean
    )

    suspend fun keyCapabilityReport(providerId: String): Result<KeyCapabilityReport> {
        return try {
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))
            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val isLocal = provider.supportsCustomBaseUrl
            if (apiKey.isBlank() && provider.requiresApiKey) {
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }
            val baseUrl = keyRepository.getBaseUrl(providerId)

            // What the provider says it serves. Failure here is not fatal - the report can
            // still say whether chat answers.
            val fetched = runCatching { provider.fetchModels(apiKey, baseUrl) }
                .getOrDefault(app.maskan.chat.data.remote.providers.FetchedModels())
            val modelCount = if (fetched.ids.isNotEmpty()) fetched.ids.size
                else provider.availableModels.size

            // Does a real chat call answer? The provider's own refusal wording rides along.
            val model = keyRepository.getSelectedModel(providerId) ?: provider.defaultModel
            val chat = runCatching {
                provider.sendMessage(apiKey, model, listOf(Message(role = "user", text = "Hi")), baseUrl)
            }

            val balance = runCatching { provider.fetchBalance(apiKey) }.getOrNull()

            Result.success(
                KeyCapabilityReport(
                    chatFailure = chat.exceptionOrNull(),
                    modelCount = modelCount,
                    freeCount = fetched.freeIds.size,
                    imageSupported = provider.supportsImageGeneration,
                    imageModelCount = fetched.imageIds.size,
                    balance = balance,
                    isLocal = isLocal
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(providerId: String): Result<String> {
        return try {
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            if (apiKey.isBlank() && provider.requiresApiKey) {
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val model = keyRepository.getSelectedModel(providerId) ?: provider.defaultModel
            val testMessages = listOf(Message(role = "user", text = "Hi"))

            val response = provider.sendMessage(apiKey, model, testMessages, storedBaseUrl)

            // If the user has chosen an image model, a green tick that only proves chat works is
            // a half-truth - the image model lives on a different endpoint with its own access
            // rules (Together answers 403 there while chat is fine). Test it too.
            //
            // This DRAWS A REAL PICTURE and is billed like any other, so the caller says so in
            // the result rather than spending the user's money silently.
            val imageModel = keyRepository.getSelectedImageModel(providerId)
                ?.trim()?.takeIf { it.isNotBlank() }
            if (imageModel != null && provider.supportsImageGeneration) {
                provider.generateImage(apiKey, imageModel, "a small grey circle", storedBaseUrl)
                return Result.success("$response\n\u2713 $imageModel")
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── The chat's own system prompt ────────────────────────────────

    /**
     * Point this chat at a different system prompt - before it starts, or half way through.
     *
     * The chat's single system ROW is rewritten in place. It is tempting instead to leave the
     * row alone and resolve the current preset on every request, and that is the wrong trade
     * twice over: buildMessageList would have to change, which is the one function that
     * guarantees a chat with no folder and no document still sends exactly what 2.5.0 sent; and
     * a chat started in Arabic would silently re-language itself the day the user switched the
     * app to English, because the preset text is chosen by locale at write time.
     *
     * Rewriting is not falsifying history: a system row is filtered out of the transcript
     * everywhere it is shown. The row is the record of what this chat IS set up as, and that is
     * precisely what has just changed.
     */
    suspend fun applyPreset(
        conversationId: Long,
        presetId: String,
        dialectId: String? = null,
        /** The user's own text, for [PRESET_CUSTOM]. Ignored for every other id. */
        customText: String? = null
    ) {
        conversationDao.updateSystemPrompt(conversationId, presetId, dialectId)
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val text = when (presetId) {
            PRESET_NONE -> ""
            PRESET_CUSTOM -> customText.orEmpty().trim()
            else -> {
                resolvePreset(conversation)?.systemPromptFor(effectiveLanguage()).orEmpty()
            }
        }
        val existing = messageDao.getMessagesForConversationOnce(conversationId)
            .firstOrNull { it.role == "system" }
        when {
            text.isBlank() && existing != null -> messageDao.deleteMessageById(existing.id)
            text.isBlank() -> Unit
            existing != null -> messageDao.updateMessageContent(existing.id, text)
            else -> saveMessage(conversationId, "system", text)
        }
    }

    // ── Chats nobody used ───────────────────────────────────────

    /**
     * Throw this conversation away if nothing was ever said in it.
     *
     * Called when the user leaves the screen. The row has to exist before the screen opens - the
     * preset, the attachments and the documents are all keyed by an id - so "do not save an
     * empty chat" can only mean "discard it on the way out". A preset chosen or a prompt written
     * counts as nothing: those are settings for a conversation that never happened. A document
     * read into it does NOT count as nothing - that is requests the user has already paid for.
     */
    suspend fun discardIfEmpty(conversationId: Long) {
        if (messageDao.countUserMessages(conversationId) > 0) return
        if (documentDao.getForConversationOnce(conversationId).isNotEmpty()) return
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        if (conversation.title != DEFAULT_TITLE) return
        deleteConversation(conversationId)
    }

    /**
     * The same idea applied once to the rows already in the database, for the installs carrying
     * a dozen "New Chat" rows out of 2.5. Narrower than [discardIfEmpty] on purpose - see the
     * DAO query's three conditions - so that nothing anyone ever touched can be caught by it.
     */
    suspend fun sweepEmptyConversations(): Int {
        val ids = conversationDao.getDiscardableConversationIds(DEFAULT_TITLE)
        ids.forEach { conversationDao.deleteConversationById(it) }
        return ids.size
    }

    // ── Editing a conversation's own history ─────────────────────────

    /** How many rows sit after [messageId]. The number the confirm dialog says out loud. */
    suspend fun countMessagesAfter(conversationId: Long, messageId: Long): Int {
        val message = messageDao.getMessageById(messageId) ?: return 0
        return messageDao.getMessagesAfter(conversationId, message.timestamp, messageId).size
    }

    /**
     * Delete [messageId], and everything after it when [alsoAfter].
     *
     * The rows are READ before they are deleted, twice over: a generated picture is a file next
     * to the database and nothing after the DELETE says which file belonged to which row, and a
     * document was read into one particular turn - when that turn goes, the file it was attached
     * to goes with it, or the app keeps paying to send a file whose card is no longer anywhere
     * on screen.
     */
    suspend fun deleteMessage(conversationId: Long, messageId: Long, alsoAfter: Boolean) {
        val message = messageDao.getMessageById(messageId) ?: return
        val after = if (alsoAfter) {
            messageDao.getMessagesAfter(conversationId, message.timestamp, messageId)
        } else {
            emptyList()
        }
        val doomed = after + message
        val images = doomed.mapNotNull { it.imagePath }
        val doomedIds = doomed.map { it.id }.toSet()
        val documents = documentDao.getForConversationOnce(conversationId)
            .filter { it.attachedMessageId in doomedIds }

        if (alsoAfter) messageDao.deleteMessagesAfter(conversationId, message.timestamp, messageId)
        messageDao.deleteMessageById(messageId)
        documents.forEach { documentDao.delete(it.id) }
        if (images.isNotEmpty()) imageStore.delete(images)
    }

    /**
     * Replace what the user said, and drop the conversation that grew out of the old wording.
     *
     * The row keeps its id and its attachment, so a photo asked about in different words is
     * still the same photo and is not uploaded again. Everything after it goes: a reply that
     * answered the old text, and every turn built on that reply, would be a transcript of a
     * conversation that never took place.
     */
    suspend fun editUserMessage(conversationId: Long, messageId: Long, newText: String) {
        val message = messageDao.getMessageById(messageId) ?: return
        val after = messageDao.getMessagesAfter(conversationId, message.timestamp, messageId)
        val images = after.mapNotNull { it.imagePath }
        val afterIds = after.map { it.id }.toSet()
        val documents = documentDao.getForConversationOnce(conversationId)
            .filter { it.attachedMessageId in afterIds }

        messageDao.deleteMessagesAfter(conversationId, message.timestamp, messageId)
        messageDao.updateMessageContent(messageId, newText)
        documents.forEach { documentDao.delete(it.id) }
        if (images.isNotEmpty()) imageStore.delete(images)
    }

    /**
     * Replace what the model said, and keep everything after it (plan item 9).
     *
     * Unlike editing a question, nothing is dropped and nothing is asked again: the point is to
     * fix one bad line without paying for a whole regeneration, and the turns built on the reply
     * are still the user's. The row is marked, and the next request carries the new text, because
     * it is built from the row like every other turn.
     */
    suspend fun editAssistantMessage(messageId: Long, newText: String) {
        val message = messageDao.getMessageById(messageId) ?: return
        if (message.role != "assistant") return
        messageDao.updateEditedReply(messageId, newText, System.currentTimeMillis())
    }

    // ── Forking a conversation ───────────────────────────────

    sealed class ForkOutcome {
        data class Created(val conversationId: Long) : ForkOutcome()
        object VideoRendering : ForkOutcome()
        object NotFound : ForkOutcome()
    }

    /**
     * A new chat holding [sourceId]'s conversation up to and including [messageId] (plan item 13).
     *
     * The source is not touched and nothing links the two: a dead end is deleted with the fork.
     * The fork takes the source's folder, provider, model and style as they are now, so the
     * request it sends next is built by the same code from the same rows.
     *
     * Picture files are COPIED, never shared: deleting a chat deletes its files, and a shared
     * name would vanish from the fork the day the source is deleted. The files are written
     * before the transaction and removed again if it fails, so neither a half-written fork nor
     * an orphaned file outlives an error.
     */
    suspend fun forkConversation(sourceId: Long, messageId: Long, title: String): ForkOutcome {
        val source = conversationDao.getConversationById(sourceId) ?: return ForkOutcome.NotFound
        val rows = messageDao.getMessagesForConversationOnce(sourceId)
        val documents = documentDao.getForConversationOnce(sourceId)
        val plan = when (
            val result = ChatFork.plan(rows, documents, messageId, source.providerId, source.modelId.orEmpty())
        ) {
            is ChatFork.Result.Ready -> result.plan
            ChatFork.Result.VideoRendering -> return ForkOutcome.VideoRendering
            ChatFork.Result.NotFound -> return ForkOutcome.NotFound
        }

        val copiedFiles = HashMap<String, String>()
        plan.messages.mapNotNull { it.imagePath }.distinct().forEach { name ->
            imageStore.read(name)?.let { bytes -> copiedFiles[name] = imageStore.save(bytes) }
        }

        var forkId = 0L
        try {
            inTransaction {
                forkId = conversationDao.insertConversation(
                    source.copy(id = 0, title = title, createdAt = System.currentTimeMillis())
                )
                val idMap = HashMap<Long, Long>()
                for (row in plan.messages) {
                    idMap[row.id] = messageDao.insertMessage(
                        row.copy(
                            id = 0,
                            conversationId = forkId,
                            imagePath = row.imagePath?.let { copiedFiles[it] }
                        )
                    )
                }
                plan.documents.forEach { documentDao.insert(ChatFork.remapDocument(it, forkId, idMap)) }
                plan.trailingSwitch?.let { move ->
                    val last = plan.messages.maxOf { it.timestamp }
                    messageDao.insertMessage(
                        MessageEntity(
                            conversationId = forkId,
                            role = ROLE_NOTICE,
                            content = ModelSwitch.encode(move),
                            timestamp = last + 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (copiedFiles.isNotEmpty()) imageStore.delete(copiedFiles.values)
            throw e
        }
        _conversationsRevision.value = _conversationsRevision.value + 1
        return ForkOutcome.Created(forkId)
    }

    // ── Naming a chat ─────────────────────────────────────────

    /** What a chat is called when nothing has named it: the first line of the first message. */
    private fun fallbackTitle(text: String): String =
        text.take(50).let { if (it.length == 50) "$it..." else it }

    /**
     * Ask the provider that just answered to name this chat, in the language it is being held in.
     *
     * ONE extra request, on the model the user already chose, carrying the first exchange and
     * nothing else - no folder instructions, no document notes, no later turns. The language is
     * named only when the first message is in Arabic or Thai script (2.7: Qwen 1.5B gave half of
     * Arabic chats an English or Chinese title until it was); see ChatTitle.
     *
     * Silent in every failure - no key, airplane mode, a refusal, an empty answer, a model that
     * writes a paragraph instead of a title. The chat keeps the name it had. A title that failed
     * to improve is not worth interrupting anybody for, and this runs when the reply has already
     * landed and the user is reading it.
     */
    suspend fun generateTitle(conversationId: Long) {
        try {
            if (!preferenceRepository.isAutoTitleEnabled()) return
            val conversation = conversationDao.getConversationById(conversationId) ?: return

            val rows = messageDao.getMessagesForConversationOnce(conversationId)
                .filter { it.role != "system" }
            val question = rows.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
            val answer = rows.firstOrNull { it.role == "assistant" }?.content?.trim().orEmpty()
            if (question.isEmpty() || answer.isEmpty()) return

            // Never over a name the user chose. The only two titles this may replace are the
            // one a chat is born with and the one the first message derived - anything else is
            // a rename, and a rename outranks us.
            val current = conversation.title
            if (current != DEFAULT_TITLE && current != fallbackTitle(question)) return

            // A failure leaves the title alone, which means the next turn will try again - and
            // that is wanted exactly twice. A chat started in airplane mode gets its name when
            // the network comes back; a model that answers a title request with a paragraph
            // every time would otherwise cost an extra request on every message the chat ever
            // carries. Three attempts, then the chat keeps the name it has.
            if (rows.count { it.role == "user" } > AUTO_TITLE_LAST_TURN) return

            val provider = ProviderRegistry.getProvider(conversation.providerId) ?: return
            if (!provider.canAutoTitle) return
            val apiKey = keyRepository.getApiKey(conversation.providerId) ?: ""
            if (apiKey.isBlank() && provider.requiresApiKey) return
            val model = conversation.modelId
                ?: keyRepository.getSelectedModel(conversation.providerId)
                ?: provider.defaultModel

            val instruction = Message(role = "system", text = ChatTitle.instruction(question))
            // Capped because a title is worth a few hundred tokens of context, not a whole
            // contract: the subject of a conversation is visible in its opening lines.
            val exchange = Message(
                role = "user",
                text = question.take(600) + "\n\n" + answer.take(600)
            )

            val raw = provider.sendMessage(
                apiKey,
                model,
                listOf(instruction, exchange),
                keyRepository.getBaseUrl(conversation.providerId)
            )
            val title = ChatTitle.clean(raw, question)
            // Debug only: what the model said and what was kept, so a title can be judged on
            // the device against the model's own words (2.7 session 6 measured this way).
            if (BuildConfig.DEBUG) {
                android.util.Log.d("MaskanTitle", "conv=$conversationId raw<<$raw>> kept<<$title>>")
            }
            if (title == null) return
            conversationDao.updateConversationTitle(conversationId, title)
        } catch (_: Exception) {
            // Deliberately silent. See the doc comment.
        }
    }

    /**
     * What this conversation's folder contributes to the system role, or null when it
     * contributes nothing.
     *
     * Read fresh on EVERY request. A preset is injected once, as a message row, which is why
     * editing a preset does nothing for a chat that already started; project instructions must
     * behave the opposite way - edit the text, and the chat opened last week obeys it on its
     * next message. Nothing here is ever written to the conversation.
     */
    private suspend fun projectText(conversation: ConversationEntity): RequestAssembly.ProjectText? {
        val folder = conversation.folderId?.let { folderDao.getById(it) }
        val instructions = folder?.instructions?.trim().orEmpty()
        val folderMemory = folder?.memory?.trim().orEmpty()

        // Global memory belongs to no folder, which is why a chat OUTSIDE every folder can carry
        // it: "what you know about me" is about the person, not the project. It is off until the
        // user turns it on, so on every install that has not opted in this reads empty and the
        // 2.5.0 early return below still fires for every unfiled chat, exactly as before.
        val globalMemory = if (preferenceRepository.isGlobalMemoryEnabled()) {
            preferenceRepository.getGlobalMemory()?.trim().orEmpty()
        } else {
            ""
        }
        if (instructions.isEmpty() && folderMemory.isEmpty() && globalMemory.isEmpty()) return null

        // One section, in assembly order: the folder's memory first, then the global file. The
        // model is being told facts; which file on this phone they came from is our business.
        val memory = listOf(folderMemory, globalMemory)
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        return RequestAssembly.ProjectText(instructions = instructions, memory = memory)
    }

    /**
     * Write the "the voice was dropped" line into the transcript, once per conversation.
     *
     * Once, because it is a fact about this folder's instructions and not about this message:
     * repeating it every turn would turn an explanation into nagging. It is stored as a KEY and
     * localized where it is shown, for the same reason "New Chat" is - a stored sentence would
     * stop being in the app's language the moment someone switched.
     */
    private suspend fun noteVoiceDropIfNeeded(conversationId: Long) {
        if (!voiceWasDropped) return
        voiceWasDropped = false
        val already = messageDao.getMessagesForConversationOnce(conversationId)
            .any { it.role == ROLE_NOTICE && it.content == NOTICE_VOICE_DROPPED }
        if (already) return
        saveMessage(conversationId, ROLE_NOTICE, NOTICE_VOICE_DROPPED)
    }

    /**
     * Set by [assembleSystemText] when it drops the dialect voice to fit folder instructions.
     *
     * A field and not a return value because assembleSystemText is called from three request
     * paths and threading a second value through all of them would touch far more than this
     * deserves. It is read and cleared by the caller that writes the notice row, immediately
     * after assembly, on the same coroutine.
     */
    private var voiceWasDropped = false



    /**
     * The voice for this request, from the dialect chosen in Settings.
     *
     * Keyed off the app's language for the same reason the preset text is (see applyPreset):
     * it is the best guess the app has about which language the answer will be in, and it is
     * the guess the rest of the app already makes. An empty string is the normal answer for
     * an English install and for MSA, and it is what keeps the 2.5.0 request shape intact for
     * everyone who has never touched this setting.
     *
     * Not on a TRANSLATION preset. The Arabic voice ends "if the user writes in another language,
     * understand the question and answer it in Arabic" - the exact opposite of Arabic → English,
     * and the same failure the 2.6.1 preset rewrite fixes (answering instead of translating).
     * English → Arabic carries its own dialect choice, so nothing is lost; on the phone it also
     * saves reading ~430 tokens, about 20 seconds of prefill on Qwen.
     */
    private fun dialectVoice(conversation: ConversationEntity): String {
        if (resolvePreset(conversation)?.category == PresetCategory.TRANSLATION) return ""
        return dialectVoice()
    }

    private fun dialectVoice(): String =
        RequestAssembly.voiceFor(effectiveLanguage(), preferenceRepository.getDefaultDialect())

    /**
     * The language this app is actually speaking, which is not the same question as
     * [LocaleRepository.getLocale].
     *
     * That returns the user's EXPLICIT choice and defaults to "" - follow the system. Reading it
     * alone answers "ar" only for someone who went into Settings and said so, and answers ""
     * for an Arabic phone whose owner never needed to. Falling through to the running locale
     * covers both, and covers the per-app language Android 13 offers from system settings.
     */
    private fun effectiveLanguage(): String =
        RequestAssembly.effectiveLanguage(localeRepository.getLocale(), java.util.Locale.getDefault().language)

    /**
     * Load the on-device model and feed it this chat's history now, before the user presses Send
     * (2.7, plan item 14). The list is built by [buildMessageList] - the same assembly a send uses -
     * so the send finds this history as the start of its own prompt and only prefills its turn.
     */
    suspend fun preloadOnDevice(conversationId: Long) {
        val conversation = getConversationById(conversationId) ?: return
        if (conversation.providerId != OnDeviceProvider.ID) return
        val provider = ProviderRegistry.getProvider(OnDeviceProvider.ID) as? OnDeviceProvider ?: return
        if (!provider.isInstalled) return
        provider.preload(buildMessageList(conversation, conversation.modelId ?: provider.defaultModel))
    }

    private suspend fun buildMessageList(
        conversation: ConversationEntity,
        /** The model about to answer - it decides whether photos can go back out at all. */
        model: String
    ): List<Message> {
        val entities = messageDao.getMessagesForConversationOnce(conversation.id)
        val documents = documentDao.getForConversationOnce(conversation.id)
        val carried = photosToCarry(conversation, model, entities, documents)
        val messages = entities.map { entity ->
            val base64 = entity.imageBase64
            if (entity.id in carried && base64 != null) {
                Message(
                    role = entity.role,
                    content = MessageContent.WithImage(
                        text = entity.content,
                        imageBase64 = base64,
                        mimeType = entity.imageMimeType ?: "image/jpeg"
                    )
                )
            } else {
                Message(role = entity.role, text = entity.content)
            }
        }

        val systemMessages = onDeviceTranslationSystem(conversation)
            ?: messages.filter { it.role == "system" }
        // Never a notice row: see RequestAssembly.conversationTurns.
        val nonSystemMessages = RequestAssembly.conversationTurns(messages)
        val recentMessages = withTranslationReminder(
            conversation,
            nonSystemMessages.takeLast(MAX_CONTEXT_MESSAGES)
        )

        val project = projectText(conversation)
        val voice = dialectVoice(conversation)

        // The question the document evidence is chosen for is the turn being sent: the user row
        // is written before the request is built, so the last user message IS the question.
        val question = entities.lastOrNull { it.role == "user" }?.content.orEmpty()
        val budget = budgetFor(conversation.providerId)
        val documentText = documentBlock(documents, question, budget)

        // The shape decision - 2.5.0 exactly, or one assembled system message - lives in
        // RequestAssembly, where the golden-request tests can reach it.
        val outgoing = RequestAssembly.outgoing(
            systemMessages = systemMessages,
            recentMessages = recentMessages,
            project = project,
            voice = voice,
            documentText = documentText,
            systemCeiling = budget.system
        )
        outgoing.voiceDropped?.let { voiceWasDropped = it }
        logContext(conversation, outgoing.messages, outgoing.shape)
        return outgoing.messages

    }

    /**
     * On the on-device model, a translation preset's system text goes out in ENGLISH whatever the
     * app language, replacing the stored row (which stays in the app's language for cloud models).
     *
     * Measured on the Redmi, 4 runs each, Qwen2.5 1.5B: Thai → English with the Thai instruction
     * never left Thai (0/8 replies in English); with the English one, 3 of 4 plain sentences came
     * back as English translations. Arabic → English was 16/16 correct either way. A model this
     * small follows English instructions best; users never see the instruction.
     */
    private fun onDeviceTranslationSystem(conversation: ConversationEntity): List<Message>? {
        if (conversation.providerId != OnDeviceProvider.ID) return null
        val preset = resolvePreset(conversation) ?: return null
        if (preset.category != PresetCategory.TRANSLATION) return null
        return listOf(Message(role = "system", text = preset.systemPromptFor("en")))
    }

    /**
     * On the on-device model only, the last user turn of a translation-preset chat goes out with
     * Presets.translationReminder in front of it. See that function for why; nothing is stored.
     *
     * A worked example (a question and its translation, sent ahead of the conversation) was tried
     * and measured on the Redmi, 5 runs a variant: it turned Qwen 1.5B's answers into gibberish
     * shaped like the example in 9 of 10 runs, at temperature 0.7 and 0.2 alike. Not shipped.
     */
    private fun withTranslationReminder(
        conversation: ConversationEntity,
        turns: List<Message>
    ): List<Message> {
        if (conversation.providerId != OnDeviceProvider.ID) return turns
        // English whatever the app language: see onDeviceTranslationSystem.
        val reminder = Presets.translationReminder(
            conversation.systemPromptId,
            conversation.dialectId?.let { Dialect.fromId(it) },
            "en"
        ) ?: return turns
        val last = turns.indexOfLast { it.role == "user" }
        if (last < 0) return turns
        val text = turns[last].content.textContent()
        if (BuildConfig.DEBUG) android.util.Log.d("MaskanCtx", "reminder<<" + reminder + ">>")
        return turns.toMutableList().also {
            it[last] = Message(role = "user", text = reminder + "\n\n" + text)
        }
    }

    /**
     * Which stored photos go back out with this request.
     *
     * The last user photo is what a follow-up is almost always about ("which ones are
     * vegetarian?"), and the one before it is what a comparison needs. Older ones are the
     * conversation's history, not its subject, and re-uploading them every turn costs the user
     * real money on a metered key.
     */
    private fun photosToCarry(
        conversation: ConversationEntity,
        model: String,
        entities: List<MessageEntity>,
        documents: List<DocumentEntity>
    ): Set<Long> {
        val blind = modelKnownBlind(conversation, model)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "MaskanCtx",
                "carry? model=" + model + " knownBlind=" + blind +
                    " visionModels=" + preferenceRepository.getVisionModels(conversation.providerId).size +
                    " rowsWithImage=" + entities.count { it.role == "user" && it.imageBase64 != null }
            )
        }
        if (blind) return emptySet()
        return photosInWindow(entities, documents)
    }

    /**
     * The photos a model that CAN see would be sent - photosToCarry without the blindness test.
     * Split out so a moved chat can ask whether its new, blind model is missing one.
     */
    private fun photosInWindow(entities: List<MessageEntity>, documents: List<DocumentEntity>): Set<Long> {
        // A scanned PDF went as three pictures, and the ordinary rule below would keep the last
        // two of them - dropping page 1, which is the page a letter or an invoice says
        // everything on. So while the newest picture in this chat is one of that document's
        // pages, the whole set travels together and REPLACES the two-photo carry rather than
        // adding to it; the moment the user attaches a newer photo, the ordinary rule resumes.
        val pageIds = documents.lastOrNull { it.isPages }?.pageIds().orEmpty()
        if (pageIds.isNotEmpty()) {
            val newestPicture = entities
                .lastOrNull { it.role == "user" && it.imageBase64 != null }?.id
            if (newestPicture != null && newestPicture in pageIds) return pageIds.toSet()
        }

        return entities
            .filter { it.role != "system" }
            .takeLast(MAX_CONTEXT_MESSAGES)
            .filter {
                it.role == "user" &&
                    it.imageBase64 != null &&
                    // A photo-to-video row keeps its source photo here too; a rendered clip is
                    // an assistant row and never reaches this filter.
                    it.imageMimeType?.startsWith("image/") != false
            }
            .takeLast(MAX_CARRIED_PHOTOS)
            .map { it.id }
            .toSet()
    }

    /**
     * Whether this app KNOWS the model about to answer cannot see - which is not the same as
     * not knowing it can.
     *
     * Only OpenRouter, Venice and a local server publish per-model modalities; Together and most
     * others publish nothing, and their provider-level flag is a guess about a catalogue of
     * hundreds of models. Treating "no data" as "blind" is what silently dropped the photo from
     * every follow-up on Together while the same model was answering about it (device, session
     * 3). The picture was sent once already; withholding it now cannot protect anyone.
     *
     * Where there IS data and it says this model takes text only, the photo stays behind: that
     * is a chat switched to a text model, and re-sending an image every turn would turn one
     * failed message into a chat that can no longer be used at all.
     */
    /**
     * Whether a MOVED chat's model would miss a picture the chat still relies on (plan item 10).
     *
     * Blind means known blind - the same data modelKnownBlind reads - or on-device with a text-only
     * model on the phone. A model nothing is known about gets the picture, as everywhere else. Asked only
     * for a moved chat: photosToCarry's quiet text-only fallback stays for every other chat.
     */
    private suspend fun photoUnreadable(
        conversation: ConversationEntity,
        model: String,
        imageData: ByteArray?
    ): Boolean {
        // On-device is blind unless the model on this phone is a vision one (Qwen3.5-0.8B-VL is).
        val onDeviceBlind = conversation.providerId == OnDeviceProvider.ID &&
            (ProviderRegistry.getProvider(OnDeviceProvider.ID) as? OnDeviceProvider)?.supportsVision != true
        val blind = onDeviceBlind || modelKnownBlind(conversation, model)
        if (!blind) return false
        if (imageData != null) return true
        val entities = messageDao.getMessagesForConversationOnce(conversation.id)
        val documents = documentDao.getForConversationOnce(conversation.id)
        return photosInWindow(entities, documents).isNotEmpty()
    }

    private fun modelKnownBlind(conversation: ConversationEntity, model: String): Boolean {
        val visionModels = preferenceRepository.getVisionModels(conversation.providerId)
        if (visionModels.isEmpty()) return false
        return model.trim() !in visionModels
    }

    /**
     * What is actually about to go on the wire, in the debug log: how many messages, in what
     * roles, and the whole system text.
     *
     * The alternative - turning the OkHttp interceptor up to BODY - would write the
     * Authorization header, i.e. real API keys, into logcat. This says everything the folder
     * work needs checking against and nothing secret: the system text is the user's own
     * instructions on the user's own phone.
     */
    private fun logContext(conversation: ConversationEntity, messages: List<Message>, shape: String) {
        if (!BuildConfig.DEBUG) return
        val system = messages.filter { it.role == "system" }
        val systemText = system.joinToString(" | ") { it.content.textContent() }
        val provider = ProviderRegistry.getProvider(conversation.providerId)
        // What is logged here is what the repository HANDS OVER. A provider with no system role
        // folds that text into the first user turn on its way to the wire, so the line says so
        // by name - sysTokens and voiceTokens keep counting the text that was really sent, and
        // the provider logs the folded prompt itself under MaskanLlm.
        val shapeName = provider?.promptShapeName ?: shape
        android.util.Log.d(
            "MaskanCtx",
            "conv=" + conversation.id + " folder=" + conversation.folderId +
                " provider=" + conversation.providerId + " shape=" + shapeName +
                (provider?.contextTokens?.let { " window=" + it } ?: "") +
                " msgs=" + messages.size + " systems=" + system.size +
                " imgs=" + messages.count { it.content is MessageContent.WithImage } +
                " sysTokens=" + TokenEstimate.of(systemText) +
                " voiceTokens=" + TokenEstimate.of(dialectVoice(conversation)) +
                " roles=" + messages.joinToString(",") { it.role }
        )
        if (systemText.isNotEmpty()) {
            android.util.Log.d("MaskanCtx", "system<<" + systemText + ">>")
        }
    }

    companion object {
        /**
         * A row that is shown to the reader and sent to nobody.
         *
         * The app occasionally does something to a request that the user would otherwise only
         * notice as the app being broken - dropping the dialect voice to fit a long set of
         * folder instructions is the first of them. A row says so, in the place they are
         * already looking.
         */
        const val ROLE_NOTICE = "notice"

        /** Stored in the row; localized at the point it is drawn. See noteVoiceDropIfNeeded. */
        const val NOTICE_VOICE_DROPPED = "voice_dropped"

        /**
         * The title a chat is born with, stored in English in every install.
         *
         * It is a SENTINEL as much as a name - "has anything named this chat yet" is a string
         * comparison against it in half a dozen places - and a stored value that changed with
         * the app's language would stop matching the moment the user switched. It is translated
         * where it is SHOWN instead; see displayTitle() in the UI.
         */
        const val DEFAULT_TITLE = "New Chat"

        /**
         * The last turn an automatic title may be attempted on. One is the ordinary case (the
         * first exchange); the extra two are the retries a failure gets before we stop paying
         * for them.
         */
        private const val AUTO_TITLE_LAST_TURN = 3

        /** The preset id meaning "this chat has no system prompt and that is deliberate". */
        const val PRESET_NONE = "none"
        const val PRESET_CUSTOM = "custom"

        const val MAX_CONTEXT_MESSAGES = 50

        /**
         * The ceiling on assembled system text. An Ollama model with a 4k window given 3,000
         * tokens of instructions has its HISTORY silently truncated by the server instead, and
         * the user sees a model that forgot the conversation rather than one that was told too
         * much. The editor's red meter warns long before this; this is the backstop.
         */
        const val MAX_SYSTEM_TOKENS = 6000

        /**
         * Below this much room left for a folder's instructions, the dialect voice is dropped
         * instead. Instructions shorter than this are rare; the point is that a long voice can
         * never be the reason a user's own project text arrives cut in half.
         */
        const val MIN_INSTRUCTION_TOKENS = 500

        /**
         * How many stored photos travel with a request. Every one of them is re-uploaded on
         * every following turn, so this is a cost ceiling as much as a context choice.
         */
        const val MAX_CARRIED_PHOTOS = 2

        /**
         * The ceiling on the document half of the system message - notes plus excerpts - and a
         * budget of its own rather than a share of MAX_SYSTEM_TOKENS. A folder with a long
         * instructions file and a long contract attached are two separate things the user
         * wants; neither should be able to erase the other.
         */
        const val MAX_DOCUMENT_TOKENS = 3000

        /** The same, for a 4k-context model on the user's own machine. */
        const val MAX_DOCUMENT_TOKENS_LOCAL = 2000

        // ── Deriving a budget from a model's real window (the on-device provider) ──
        //
        // Worked at 4,096: 819 reserved for the answer, 307 of headroom, 2,970 usable, of
        // which 1,200 system, 1,000 document and the remaining ~770 history. See
        // _build_2.6/patch63_window_budget.py for why each share is what it is.

        /** Reserved for the model's own answer. A short Arabic reply runs 300-600 tokens. */
        const val ANSWER_SHARE = 0.20

        /**
         * Held back for what we cannot count exactly: the prompt template's turn markers, and
         * the gap between TokenEstimate and the model's own tokenizer. The tokenizer has the
         * last word anyway - the on-device provider trims history against sizeInTokens - but a
         * budget that needs the last word every time is a budget set too high.
         */
        const val HEADROOM_SHARE = 0.075

        const val SYSTEM_SHARE = 0.40
        const val DOCUMENT_SHARE = 0.34

        /** Floors, so a hypothetically tiny window still produces a usable request. */
        const val MIN_USABLE_TOKENS = 600
        const val MIN_CHUNK_TOKENS = 200

        /** How many times one chunk is retried after a 429 before the pass stops and says so. */
        const val NOTES_MAX_RETRIES = 4

        /** First wait after a rate limit; it doubles per attempt (2, 4, 8, 16 seconds). */
        const val NOTES_BACKOFF_SECONDS = 2
    }
}
