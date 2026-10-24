package app.maskan.chat.data.repository

import app.maskan.chat.data.local.DocumentEntity
import app.maskan.chat.data.local.MessageEntity

/**
 * What a fork copies (2.7, plan item 13): a new chat holding the source's conversation from the
 * start up to and including one message, so a "what if" can be tried without the main chat ever
 * seeing the dead end.
 *
 * Pure, so the tests reach it without a database. The repository does the I/O around it: the
 * picture files first, then every row in one transaction, so a fork is never half written.
 */
internal object ChatFork {

    data class Plan(
        /** In order: the system row(s), then every row up to and including the chosen one. */
        val messages: List<MessageEntity>,
        /** Documents whose message is copied, or that belong to no message at all. */
        val documents: List<DocumentEntity>,
        /**
         * The source's model has changed since the chosen message. The fork runs on the model the
         * source has NOW (Humam, 2026-10-24), and its transcript says so with the same notice a
         * switch leaves - otherwise it would read as if the old model carried on.
         */
        val trailingSwitch: ModelSwitch.Move?
    )

    sealed class Result {
        data class Ready(val plan: Plan) : Result()
        /** A video in the copied range is still rendering. Its job id cannot be shared or copied. */
        object VideoRendering : Result()
        /** The message is not in this conversation, or is a system row. */
        object NotFound : Result()
    }

    fun plan(
        rows: List<MessageEntity>,
        documents: List<DocumentEntity>,
        chosenId: Long,
        currentProvider: String,
        currentModel: String
    ): Result {
        // (timestamp, id): a question and the placeholder answering it can share a millisecond.
        val ordered = rows.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val chosenIndex = ordered.indexOfFirst { it.id == chosenId }
        if (chosenIndex < 0 || ordered[chosenIndex].role == "system") return Result.NotFound

        val upTo = ordered.subList(0, chosenIndex + 1)
        // The system row is the chat's style, rewritten in place when the style changes, so it is
        // copied wherever it sits - the fork takes the style the source has now.
        val systems = ordered.filter { it.role == "system" }
        val copied = systems + upTo.filter { it.role != "system" }

        if (copied.any { it.videoJobId != null && it.imagePath == null }) return Result.VideoRendering

        val copiedIds = copied.map { it.id }.toSet()
        val docs = documents.filter { it.attachedMessageId == null || it.attachedMessageId in copiedIds }

        val inForce = modelAt(ordered, chosenIndex)
        val trailing = inForce?.let { (provider, model) ->
            ModelSwitch.Move(provider, model, currentProvider, currentModel).takeUnless { it.isNoOp }
        }
        return Result.Ready(Plan(copied, docs, trailing))
    }

    /**
     * The provider and model that were answering at [index], read off the switch notices. Null
     * when the chat was never moved - then the current model is the one it always had.
     */
    private fun modelAt(ordered: List<MessageEntity>, index: Int): Pair<String, String>? {
        val switches = ordered.withIndex().mapNotNull { (i, row) ->
            if (row.role != ChatRepository.ROLE_NOTICE) null else ModelSwitch.decode(row.content)?.let { i to it }
        }
        switches.lastOrNull { it.first <= index }?.let { return it.second.toProvider to it.second.toModel }
        switches.firstOrNull { it.first > index }?.let { return it.second.fromProvider to it.second.fromModel }
        return null
    }

    /**
     * A copied document, pointed at the copied rows. A scan's page ids are message ids, so each
     * is remapped; one that was not copied is dropped rather than left pointing into the source.
     */
    fun remapDocument(doc: DocumentEntity, conversationId: Long, idMap: Map<Long, Long>): DocumentEntity =
        doc.copy(
            id = 0,
            conversationId = conversationId,
            attachedMessageId = doc.attachedMessageId?.let { idMap[it] },
            pageImageIds = doc.pageIds().mapNotNull { idMap[it] }
                .joinToString(",").ifBlank { null }
                ?.takeIf { doc.isPages }
        )
}
