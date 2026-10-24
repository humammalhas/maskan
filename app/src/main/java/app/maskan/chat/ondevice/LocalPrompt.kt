package app.maskan.chat.ondevice

/**
 * The app's message list, written the way ONE model file expects to read it.
 *
 * Two shapes, and the difference between them is not cosmetic:
 *
 * - **ChatML** - Qwen2.5, the model 2.6.0 ships. Roles `system`, `user`, `assistant`, markers
 *   `<|im_start|>` / `<|im_end|>`. It has a **real system role**, so the assembled system text
 *   (preset, dialect voice, folder instructions, memory, document block) goes into a turn of its
 *   own, which is where the model was trained to look for it.
 * - **Gemma** - roles `user` and `model`, and nothing else. There is no system role at all, so
 *   the same text is folded into the first user turn. That is a wire format, the same kind of
 *   thing AnthropicProvider does when it lifts the system message into its own field, and it is
 *   why MaskanCtx names that shape `gemma-folded`: the text is all there, it is just not in a
 *   turn of its own any more.
 *
 * Which shape applies is the **file's** business and not the provider's. Both `.task` bundles
 * declare their own markers and their own role names in their METADATA, and a prompt built with
 * the other family's markers is a prompt with no turn structure at all - it still answers, which
 * is exactly why it had to be read off the file rather than assumed. So the shape travels in
 * [OnDeviceModel] and arrives here as [Shape].
 *
 * The templating is ours to do, but not because the files have none - the opposite. The runtime
 * wraps whatever it is given in the file's own template, which for an already-marked multi-turn
 * prompt means one extra user turn around the whole conversation and an empty model turn after
 * it. Qwen's template goes further and hard-codes *"You are Qwen, created by Alibaba Cloud"* into
 * its user prefix, which would sit in front of the user's own folder instructions.
 *
 * From 2.7 the engine drives LiteRT-LM's Conversation, and the runtime renders the turns with the
 * file's own chat template - read out of the file, it writes exactly the text [build] writes for
 * Qwen3.5 (ChatML, an empty think block opening every model turn). So this object no longer puts
 * a marker on the wire; it is what the window's token estimate is made of, and what the golden
 * tests pin, so a template that drifts from it is caught by comparison rather than by a user.
 */
object LocalPrompt {

    /**
     * One message. [image] is a photo the message carries, already downscaled (see
     * OnDeviceProvider) - on a vision model only, and at most one per conversation (the newest).
     */
    data class Turn(val role: String, val text: String, val image: ByteArray? = null) {
        // Arrays compare by reference; a turn is the same turn when its bytes are the same.
        override fun equals(other: Any?): Boolean = other is Turn && role == other.role &&
            text == other.text && (image?.contentEquals(other.image) ?: (other.image == null))
        override fun hashCode(): Int = (role.hashCode() * 31 + text.hashCode()) * 31 +
            (image?.contentHashCode() ?: 0)
    }

    /**
     * How one model file wants its turns written.
     *
     * [systemRole] is the whole reason this is a data class and not a pair of marker strings: it
     * decides whether the system text gets a turn or gets folded, and getting that wrong is
     * silent - the model answers either way, slightly worse.
     */
    data class Shape(
        val start: String,
        val end: String,
        /** What this family calls the model's own turn: `assistant` in ChatML, `model` in Gemma. */
        val assistantRole: String,
        val systemRole: Boolean,
        /**
         * What the file's own template writes at the start of EVERY assistant turn, before the
         * text - past turns and the open one alike. Empty for Qwen2.5 and Gemma.
         *
         * Qwen3.5's is `<think>\n\n</think>\n\n`: an empty thought, which is how its template
         * turns thinking OFF (the bundle's manifest: thinking `control: never`). Without it the
         * model may think aloud and the user gets raw tags in the answer (Humam, 2026-10-24:
         * thinking off; plan item 2 stays its own session). The bundle keeps the block in past
         * turns too, where the stock template strips it; the file is followed, not the stock.
         */
        val assistantPrefix: String = ""
    )

    /**
     * One prompt from [system] and [turns].
     *
     * A turn whose role is not "user" is written as the model's role, which covers "assistant"
     * and anything else that reaches here; a system row in the middle of the list (2.5 chats
     * have one) is dropped, because its text has already been assembled into [system].
     */
    fun build(system: String?, turns: List<Turn>, shape: Shape): String {
        val body = StringBuilder()
        var systemPending = system?.takeIf { it.isNotBlank() }

        // A file with a real system role gets one, first, exactly as its template describes it.
        if (systemPending != null && shape.systemRole) {
            body.append(shape.start).append("system\n")
                .append(systemPending).append(shape.end).append("\n")
            systemPending = null
        }

        for (turn in turns) {
            if (turn.role == "system") continue
            val isUser = turn.role == "user"
            val text = if (isUser && systemPending != null) {
                val folded = systemPending + "\n\n" + turn.text
                systemPending = null
                folded
            } else {
                turn.text
            }
            body.append(shape.start).append(if (isUser) "user" else shape.assistantRole)
                .append("\n").append(if (isUser) "" else shape.assistantPrefix)
                .append(text).append(shape.end).append("\n")
        }

        // Instructions with nothing to apply them to: a chat whose first turn is somehow not the
        // user's still has to carry them, or the model is answering with no idea who it is. Only
        // reachable on a folded shape - a system role has already been written above.
        if (systemPending != null) {
            body.insert(0, shape.start + "user\n" + systemPending + shape.end + "\n")
        }

        // The open model turn is the invitation to answer. Without it the model continues the
        // conversation as the user.
        body.append(openTurn(shape))
        return body.toString()
    }

    /**
     * The system text an on-device request carries: the model's [identity] first, then the chat's
     * own [assembled] system text - or [fallback] when it has none.
     *
     * The identity is plan item 7, done on Qwen3.5 (2.7 session 8): a small model with no word
     * about who it is invents one - Qwen2.5 told users it was "Claude, made by Anthropic". Neither
     * Qwen3.5's bundled template nor Qwen's own has a default system line to copy, so the line is
     * Maskan's (Humam, 2026-10-24). It goes IN FRONT of a folder's, a preset's or a voice's text,
     * so it survives an Arabic UI too.
     */
    fun systemFor(identity: String?, assembled: String?, fallback: String?): String? {
        val own = assembled?.takeIf { it.isNotBlank() } ?: fallback
        return listOfNotNull(identity, own).joinToString("\n\n").ifEmpty { null }
    }

    /**
     * The open model turn [build] ends every prompt with. Without it, a prompt is the conversation
     * so far - which is what LlmEngine.preload feeds a session before the user has typed anything.
     */
    fun openTurn(shape: Shape): String = shape.start + shape.assistantRole + "\n" + shape.assistantPrefix

    /**
     * Drop whole turns from the front until the prompt fits, keeping the newest.
     *
     * Pairs, not messages: half an exchange left at the front of the history reads to the model
     * as a question it already answered, or an answer to a question that is not there. The
     * system text is never what gives way here - it has already been clamped to its own budget
     * by the time it arrives, and an app that drops the user's folder instructions to fit one
     * more old message has its priorities backwards.
     */
    fun trimToFit(
        system: String?,
        turns: List<Turn>,
        shape: Shape,
        limit: Int,
        countTokens: (String) -> Int
    ): Pair<String, Int> {
        var kept = turns
        while (true) {
            val prompt = build(system, kept, shape)
            val tokens = countTokens(prompt)
            // A count the runtime refused to give (-1) is not a reason to keep trimming.
            if (tokens < 0 || tokens <= limit || kept.size <= 1) return prompt to tokens
            kept = kept.drop(if (kept.size >= 2) 2 else 1)
        }
    }
}
