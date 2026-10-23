package app.maskan.chat.data.repository

/**
 * A chat moved onto another provider or model half way through (2.7, plan item 10).
 *
 * The move itself is one UPDATE on the conversation row: the next request reads the row, exactly
 * as a changed system prompt is read, so history is untouched and nothing is re-sent. What lives
 * here is the RECORD of the move - the notice row the transcript shows, so a chat answered by two
 * models does not read as if one model said everything - and the question "has this chat been
 * moved", which is what the hard cases ask before they refuse to send.
 *
 * Pure, so the tests reach it without a database.
 */
internal object ModelSwitch {

    /** The start of a notice row's content. The rest names the move, one field per line. */
    const val PREFIX = "model_switched\n"

    data class Move(
        val fromProvider: String,
        val fromModel: String,
        val toProvider: String,
        val toModel: String
    ) {
        /** Moved away and straight back before anything was sent: there is nothing to say. */
        val isNoOp: Boolean get() = fromProvider == toProvider && fromModel.trim() == toModel.trim()
    }

    // Newlines, not a printable separator: model ids carry '/', ':' and '|' between them
    // (OpenRouter's "a/b:free", Ollama's "qwen2.5vl:7b"), and none of them carries a line break.
    fun encode(move: Move): String =
        PREFIX + listOf(move.fromProvider, move.fromModel, move.toProvider, move.toModel).joinToString("\n")

    fun decode(content: String): Move? {
        if (!content.startsWith(PREFIX)) return null
        val parts = content.removePrefix(PREFIX).split("\n")
        if (parts.size != 4) return null
        return Move(parts[0], parts[1], parts[2], parts[3])
    }

    /**
     * Whether this chat has been moved at least once. The hard cases apply ONLY to a moved chat:
     * a chat still on the model it was born with must send exactly what it sent before 2.7.
     */
    fun isSwitched(rows: List<Pair<String, String>>): Boolean =
        rows.any { (role, content) -> role == ChatRepository.ROLE_NOTICE && decode(content) != null }
}
