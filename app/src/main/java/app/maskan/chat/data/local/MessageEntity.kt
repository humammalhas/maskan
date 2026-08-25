package app.maskan.chat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    /**
     * File name of a GENERATED image, held in app-private storage rather than inline here.
     *
     * Attached images (imageBase64) are small and already compressed to <=500KB; a generated one
     * is a full 1024px picture, 1-2 MB, and base64 inflates it by another ~35% - a few dozen of
     * those would bloat the SQLCipher database badly. The file itself is AES-256-GCM encrypted,
     * so it gets the same protection as the prompt that produced it. See ImageStore.
     */
    val imagePath: String? = null
)
