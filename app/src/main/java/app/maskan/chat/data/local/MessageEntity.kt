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
    val imagePath: String? = null,
    /**
     * Server-side job id of a video that is still rendering. Non-null with a null imagePath is
     * the "pending" state everywhere: it is what the bubble shows progress for, what the app
     * resumes polling on restart, and what a Cancel deletes. Cleared when the clip lands (or
     * the job fails - then the row keeps its video mime and no path, which is "failed").
     */
    val videoJobId: String? = null,
    /**
     * The pages the provider's own web search read for this answer, as JSON (SearchStream.encode).
     * Null on every row that did not search - which is every row written before 2.7. Kept with the
     * message because a source you cannot find again is worth little.
     */
    val sourcesJson: String? = null,
    /**
     * When the USER rewrote this reply (2.7, plan item 9), or null for every reply the model's
     * words still stand in. The bubble and the export say "edited" whenever it is set, so the
     * transcript never claims the model said something it did not. The original text is not
     * kept: an edit that removes a line is meant to remove it, from backups too.
     */
    val editedAt: Long? = null,
    /**
     * A voice note or other audio file the user attached (2.7, plan item 3). The file lives in
     * app-private storage, AES-GCM encrypted like a generated image (ImageStore); this is its name.
     * The audio itself never goes to a chat model - only [transcript] does - and backups carry the
     * transcript but not the file.
     */
    val audioPath: String? = null,
    val audioMimeType: String? = null,
    /** The file's own name as the user picked it, for the chip in the bubble. */
    val audioName: String? = null,
    val audioDurationMs: Long? = null,
    /** The audio as spoken, written out by [transcribedBy]. This is what every model reads. */
    val transcript: String? = null,
    /** "providerId/model" of the backend that wrote [transcript]. */
    val transcribedBy: String? = null
)
