package app.maskan.chat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conversations.
 * Exposes Flow-based queries so the UI can reactively observe changes.
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateConversationTitle(id: Long, title: String)

    @Query("UPDATE conversations SET systemPromptId = :systemPromptId, dialectId = :dialectId WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, systemPromptId: String?, dialectId: String?)

    @Query("UPDATE conversations SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?)

    @Query("UPDATE conversations SET providerId = :providerId, modelId = :modelId WHERE id = :id")
    suspend fun updateProvider(id: Long, providerId: String, modelId: String?)

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchConversationsByTitle(query: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id IN (:ids) ORDER BY createdAt DESC")
    suspend fun getConversationsByIds(ids: List<Long>): List<ConversationEntity>
}

