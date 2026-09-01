package app.maskan.chat.data.repository

import android.util.Base64
import app.maskan.chat.data.local.ConversationDao
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.FolderDao
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.data.local.MessageDao
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.ChatCompletionResponse
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.util.ImageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

enum class ExportFormat { PLAIN_TEXT, MARKDOWN }

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val keyRepository: KeyRepository,
    private val localeRepository: LocaleRepository,
    private val imageStore: ImageStore
) {

    // ── Conversations ──────────────────────────────────────────────────

    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun getConversationById(id: Long): ConversationEntity? =
        conversationDao.getConversationById(id)

    suspend fun createConversation(
        title: String = "New Chat",
        providerId: String = ProviderRegistry.getDefaultProvider().id,
        modelId: String? = null
    ): Long {
        val conversation = ConversationEntity(
            title = title,
            providerId = providerId,
            modelId = modelId
        )
        return conversationDao.insertConversation(conversation)
    }

    suspend fun deleteConversation(id: Long) {
        // Collect the image files FIRST: the foreign-key cascade wipes the message rows, and
        // after that there is nothing left to say which files belonged to this conversation.
        val images = messageDao.getImagePathsForConversation(id)
        conversationDao.deleteConversationById(id)
        if (images.isNotEmpty()) imageStore.delete(images)
    }

    /**
     * Point a conversation at a different model, keeping its provider. Used to un-stick a chat
     * whose frozen modelId names a model the provider has retired.
     */
    suspend fun updateConversationModel(id: Long, modelId: String?) {
        conversationDao.updateConversationModel(id, modelId)
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
                ExportFormat.PLAIN_TEXT -> {
                    for (msg in messages) {
                        val label = if (msg.role == "user") "You" else "AI"
                        appendLine("$label: ${msg.content}")
                    }
                }
                ExportFormat.MARKDOWN -> {
                    for ((index, msg) in messages.withIndex()) {
                        val label = if (msg.role == "user") "**You:**" else "**AI:**"
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
                    val isArabic = localeRepository.getLocale() == "ar"
                    val systemContent = if (isArabic) preset.systemPromptAr else preset.systemPromptEn
                    if (systemContent.isNotBlank()) {
                        saveMessage(conversationId, "system", systemContent)
                    }
                }
            }

            userMessageId = saveMessage(conversationId, "user", userContent)

            val messages = buildMessageList(conversationId)

            val providerId = conversation.providerId
            val provider = ProviderRegistry.getProvider(providerId)
                ?: run {
                    messageDao.deleteMessageById(userMessageId)
                    return Result.failure(Exception("Unknown provider: $providerId"))
                }

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val isLocalProvider = provider.supportsCustomBaseUrl
            if (apiKey.isBlank() && !isLocalProvider) {
                messageDao.deleteMessageById(userMessageId)
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val effectiveModel = conversation.modelId ?: model

            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val assistantContent = provider.sendMessage(apiKey, effectiveModel, messages, storedBaseUrl)

            saveMessage(conversationId, "assistant", assistantContent)

            if (conversation.title == "New Chat") {
                val title = userContent.take(50).let {
                    if (it.length == 50) "$it..." else it
                }
                conversationDao.updateConversationTitle(conversationId, title)
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
        imageMimeType: String? = null
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
        val hasSystemMessage = existingMessages.any { it.role == "system" }

        if (conversation.systemPromptId != null && !hasSystemMessage) {
            val preset = resolvePreset(conversation)
            if (preset != null) {
                val isArabic = localeRepository.getLocale() == "ar"
                val systemContent = if (isArabic) preset.systemPromptAr else preset.systemPromptEn
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

        streamAssistantReply(conversation, model, imageData, imageMimeType)

        if (conversation.title == "New Chat") {
            val title = userContent.take(50).let {
                if (it.length == 50) "$it..." else it
            }
            conversationDao.updateConversationTitle(conversationId, title)
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
            if (apiKey.isBlank() && !provider.supportsCustomBaseUrl) {
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
    fun generateImage(conversationId: Long, prompt: String): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val isLocalProvider = provider.supportsCustomBaseUrl
        if (apiKey.isBlank() && !isLocalProvider) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }

        val model = keyRepository.getSelectedImageModel(providerId)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("no image model selected")

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

        if (conversation.title == "New Chat") {
            val title = prompt.take(50).let { if (it.length == 50) "$it..." else it }
            conversationDao.updateConversationTitle(conversationId, title)
        }

        emit(StreamEvent.Done)
    }

    /**
     * The shared tail of both paths: build the context window, insert the assistant
     * placeholder, stream tokens into it, and clean up an empty placeholder on failure.
     */
    private suspend fun FlowCollector<StreamEvent>.streamAssistantReply(
        conversation: ConversationEntity,
        model: String,
        imageData: ByteArray?,
        imageMimeType: String?
    ) {
        val conversationId = conversation.id
        val messages = buildMessageList(conversationId)

        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val isLocalProvider = provider.supportsCustomBaseUrl
        if (apiKey.isBlank() && !isLocalProvider) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }

        val effectiveModel = conversation.modelId ?: model
        val storedBaseUrl = keyRepository.getBaseUrl(providerId)

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
            provider.sendMessageStreaming(
                apiKey, effectiveModel, messages, storedBaseUrl,
                imageData, imageMimeType
            ).collect { token ->
                fullContent.append(token)
                val snapshot = fullContent.toString()
                messageDao.updateMessageContent(assistantMessageId, snapshot)
                emit(StreamEvent.Token(assistantMessageId, snapshot))
            }

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
        data object Done : StreamEvent()
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
            if (apiKey.isBlank() && !isLocal) {
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
            val isLocalProvider = provider.supportsCustomBaseUrl
            if (apiKey.isBlank() && !isLocalProvider) {
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

    private suspend fun buildMessageList(conversationId: Long): List<Message> {
        val entities = messageDao.getMessagesForConversationOnce(conversationId)
        val messages = entities.map { Message(role = it.role, text = it.content) }

        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }
        val recentMessages = nonSystemMessages.takeLast(MAX_CONTEXT_MESSAGES)

        return systemMessages + recentMessages
    }

    companion object {
        const val MAX_CONTEXT_MESSAGES = 50
    }
}
