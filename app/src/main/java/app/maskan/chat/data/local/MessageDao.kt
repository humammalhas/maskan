package app.maskan.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for messages.
 * Messages are queried by conversation and ordered chronologically.
 */
@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationOnce(conversationId: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: Long, content: String)

    /** A reply the user rewrote: the text and the mark that says so, in one statement. */
    @Query("UPDATE messages SET content = :content, editedAt = :editedAt WHERE id = :messageId")
    suspend fun updateEditedReply(messageId: Long, content: String, editedAt: Long)

    @Query("UPDATE messages SET sourcesJson = :sourcesJson WHERE id = :messageId")
    suspend fun updateMessageSources(messageId: Long, sourcesJson: String?)

    @Query("UPDATE messages SET imagePath = :imagePath, imageMimeType = :mimeType WHERE id = :messageId")
    suspend fun updateImagePath(messageId: Long, imagePath: String, mimeType: String)

    /**
     * File names of every generated image in a conversation. Needed before deleting it: Room's
     * cascade removes the rows but nothing removes the files on disk.
     */
    @Query("SELECT imagePath FROM messages WHERE conversationId = :conversationId AND imagePath IS NOT NULL")
    suspend fun getImagePathsForConversation(conversationId: Long): List<String>

    @Query("UPDATE messages SET heardBy = :heardBy WHERE id = :messageId")
    suspend fun updateHeardBy(messageId: Long, heardBy: String)

    /** The same for attached audio files (2.7), which live in the same encrypted store. */
    @Query("SELECT audioPath FROM messages WHERE conversationId = :conversationId AND audioPath IS NOT NULL")
    suspend fun getAudioPathsForConversation(conversationId: Long): List<String>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    /** Rows still waiting on a video: a job id but no file. Resumed on every app start. */
    @Query("SELECT * FROM messages WHERE videoJobId IS NOT NULL AND imagePath IS NULL")
    suspend fun getPendingVideoMessages(): List<MessageEntity>

    /** The clip landed: file in, job id out, in one statement so no state in between is visible. */
    @Query("UPDATE messages SET imagePath = :imagePath, imageMimeType = :mimeType, videoJobId = NULL WHERE id = :messageId")
    suspend fun updateVideoDone(messageId: Long, imagePath: String, mimeType: String)

    /** The render failed: keep the row (video mime, no path) and let content carry the reason. */
    @Query("UPDATE messages SET videoJobId = NULL, content = :reason WHERE id = :messageId")
    suspend fun markVideoFailed(messageId: Long, reason: String)

    /** How many rows this conversation holds at all, system row included. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int

    /** How many of them the USER wrote. Zero is what "nothing was ever said here" means. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND role = 'user'")
    suspend fun countUserMessages(conversationId: Long): Int

    /**
     * The first thing the user said in each conversation, for the list.
     *
     * One query for the whole screen rather than one per row: the list is a LazyColumn and a
     * per-row suspend read would fire again on every scroll. MIN(id) rather than MIN(timestamp)
     * because two rows written in the same millisecond are a real thing here.
     *
     * A Flow: the list's conversations query does not watch `messages`, so a chat's first
     * message would otherwise never reach a list that is already on its back stack.
     */
    @Query(
        "SELECT * FROM messages WHERE id IN " +
            "(SELECT MIN(id) FROM messages WHERE role = 'user' GROUP BY conversationId)"
    )
    fun getFirstUserMessages(): Flow<List<MessageEntity>>

    /**
     * Everything written after [messageId] in this conversation, newest included.
     *
     * Ordered by (timestamp, id), not timestamp alone: a user row and the assistant placeholder
     * that answers it can share a millisecond, and "after" has to be a total order or an edit
     * leaves the reply it was meant to replace sitting underneath it.
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND " +
            "(timestamp > :timestamp OR (timestamp = :timestamp AND id > :messageId)) " +
            "ORDER BY timestamp ASC, id ASC"
    )
    suspend fun getMessagesAfter(
        conversationId: Long,
        timestamp: Long,
        messageId: Long
    ): List<MessageEntity>

    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND " +
            "(timestamp > :timestamp OR (timestamp = :timestamp AND id > :messageId))"
    )
    suspend fun deleteMessagesAfter(
        conversationId: Long,
        timestamp: Long,
        messageId: Long
    )

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    @Query("SELECT DISTINCT conversationId FROM messages WHERE content LIKE '%' || :query || '%' AND role != 'system'")
    suspend fun searchMessages(query: String): List<Long>
}

