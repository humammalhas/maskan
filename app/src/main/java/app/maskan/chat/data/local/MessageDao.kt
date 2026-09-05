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

    @Query("UPDATE messages SET imagePath = :imagePath, imageMimeType = :mimeType WHERE id = :messageId")
    suspend fun updateImagePath(messageId: Long, imagePath: String, mimeType: String)

    /**
     * File names of every generated image in a conversation. Needed before deleting it: Room's
     * cascade removes the rows but nothing removes the files on disk.
     */
    @Query("SELECT imagePath FROM messages WHERE conversationId = :conversationId AND imagePath IS NOT NULL")
    suspend fun getImagePathsForConversation(conversationId: Long): List<String>

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

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    @Query("SELECT DISTINCT conversationId FROM messages WHERE content LIKE '%' || :query || '%' AND role != 'system'")
    suspend fun searchMessages(query: String): List<Long>
}

