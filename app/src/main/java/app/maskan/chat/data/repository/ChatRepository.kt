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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class ExportFormat { PLAIN_TEXT, MARKDOWN }

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val keyRepository: KeyRepository,
    private val localeRepository: LocaleRepository
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
        conversationDao.deleteConversationById(id)
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
                val dialect = conversation.dialectId?.let { Dialect.fromId(it) } ?: Dialect.LEVANTINE
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
        saveMessage(conversationId, "user", userContent, imageBase64ForStorage, imageMimeType)

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

        val assistantMessageId = saveMessage(conversationId, "assistant", "")
        emit(StreamEvent.Started(assistantMessageId))

        try {
            val fullContent = StringBuilder()
            provider.sendMessageStreaming(
                apiKey, effectiveModel, messages, storedBaseUrl,
                imageData, imageMimeType
            ).collect { token ->
                    fullContent.append(token)
                    val snapshot = fullContent.toString()
                    messageDao.updateMessageContent(assistantMessageId, snapshot)
                    emit(StreamEvent.Token(snapshot))
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

        if (conversation.title == "New Chat") {
            val title = userContent.take(50).let {
                if (it.length == 50) "$it..." else it
            }
            conversationDao.updateConversationTitle(conversationId, title)
        }

        emit(StreamEvent.Done)
    }

    sealed class StreamEvent {
        data class Started(val messageId: Long) : StreamEvent()
        data class Token(val fullContent: String) : StreamEvent()
        data object Done : StreamEvent()
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
