package app.maskan.chat.data.repository

import app.maskan.chat.data.local.DialectVoice
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.Message
import app.maskan.chat.util.TokenEstimate

/**
 * The decisions that shape every request, with nothing Android in them.
 *
 * Moved out of ChatRepository in 2.7 (plan item 5) so the golden-request tests can run them on
 * the JVM. ChatRepository still gathers the inputs - the database rows, the folder, the document
 * evidence, the settings - and hands them here; what comes back is exactly what it used to build
 * itself. The move was proved byte-identical on the device: AssemblyProbeReceiver fingerprints
 * the outgoing list for every conversation, before and after, in English and Arabic.
 */
internal object RequestAssembly {

    /**
     * The turns a request carries: never a system row (assembled separately) and never a notice.
     * A notice row is written FOR the reader and is never sent: it describes what the app did -
     * dropped the voice, moved the chat to another model - which is not a turn in the
     * conversation and would read to a model as the user talking about themselves in the third
     * person. Here, rather than inline in ChatRepository, so a switched chat's request has a golden.
     */
    fun conversationTurns(messages: List<Message>): List<Message> =
        messages.filter { it.role != "system" && it.role != ChatRepository.ROLE_NOTICE }

    /** What a conversation's folder contributes to the system role (see ChatRepository.projectText). */
    data class ProjectText(val instructions: String, val memory: String)

    data class SystemText(
        val text: String,
        /** The dialect voice gave way to fit folder instructions; the caller writes the notice. */
        val voiceDropped: Boolean
    )

    data class Outgoing(
        val messages: List<Message>,
        /** The name logContext prints: "2.5.0-shape", "assembled" or "document". */
        val shape: String,
        /** Null when no folder text was assembled, so nothing was decided about the voice. */
        val voiceDropped: Boolean?
    )

    /**
     * The message list a provider receives, from the parts ChatRepository gathered.
     *
     * A chat outside a folder, in a folder with both files empty, and with no document must
     * send EXACTLY what 2.5.0 sent. Not nearly - exactly: this is the line that keeps the new
     * features from quietly changing every existing conversation in the app.
     *
     * Otherwise, one system message, always. The preset's row stays in the database (it is the
     * user's record of what this chat was set up as) but it is REPLACED in the outgoing request
     * by the assembled text: Anthropic and Gemini fold multiple system messages into one field
     * anyway, and an OpenAI-shaped provider would otherwise receive two, which is the 2.5 trap
     * that rejected calls outright.
     *
     * The document block is appended AFTER the project text and outside its ceiling: the
     * folder's instructions and the file the user just attached are two different budgets, and
     * a long style guide must not be able to squeeze out the contract being asked about.
     */
    fun outgoing(
        systemMessages: List<Message>,
        recentMessages: List<Message>,
        project: ProjectText?,
        voice: String,
        documentText: String?,
        systemCeiling: Int
    ): Outgoing {
        if (project == null && documentText == null && voice.isEmpty()) {
            return Outgoing(systemMessages + recentMessages, "2.5.0-shape", voiceDropped = null)
        }

        val preset = systemMessages.joinToString("\n") { it.content.textContent() }.trim()
        var voiceDropped: Boolean? = null
        val assembled = listOfNotNull(
            if (project != null) {
                systemText(preset, voice, project, systemCeiling)
                    .also { voiceDropped = it.voiceDropped }
                    .text
            } else {
                listOf(preset, voice).filter { it.isNotBlank() }
                    .joinToString("\n\n").ifBlank { null }
            },
            documentText
        ).joinToString("\n\n")

        return Outgoing(
            messages = listOf(Message(role = "system", text = assembled)) + recentMessages,
            shape = if (documentText != null) "document" else "assembled",
            voiceDropped = voiceDropped
        )
    }

    /**
     * The system message this request will carry, within the ceiling.
     *
     * Which half gives way matters, and the device showed why: a folder with 7,795 tokens of
     * instructions had its memory cut away entirely, because the clamp trims the tail and memory
     * is assembled last - so the model kept the 40th paragraph of a style guide and lost "our
     * office is in Jabal Amman". Memory is a handful of short facts the user asked to keep; the
     * instructions are the part that can run to pages. So the instructions are cut to whatever
     * the ceiling leaves after the preset and the memory, and the whole is clamped again as a
     * backstop for the case where the memory alone is enormous.
     */
    fun systemText(
        preset: String,
        voice: String,
        project: ProjectText,
        ceiling: Int
    ): SystemText {
        var voiceDropped = false
        val memoryBlock = if (project.memory.isEmpty()) {
            ""
        } else {
            "### What you know about me\n" + project.memory
        }
        // The voice is a global setting, one tap to restore. The instructions are words this
        // user typed for this folder. When both cannot fit under the ceiling the voice is what
        // gives way - Humam's call, session D - rather than silently truncating their text.
        var voiceBlock = voice
        var keptTokens = TokenEstimate.of(preset) + TokenEstimate.of(memoryBlock) +
            TokenEstimate.of(voiceBlock)
        if (project.instructions.isNotEmpty() &&
            ceiling - keptTokens < ChatRepository.MIN_INSTRUCTION_TOKENS &&
            voiceBlock.isNotEmpty()
        ) {
            voiceBlock = ""
            keptTokens = TokenEstimate.of(preset) + TokenEstimate.of(memoryBlock)
            // Something the user chose was silently not sent. With the on-device provider back
            // the ceiling is the window-derived 1,200 again, so this fires on ordinary folders
            // rather than only on enormous ones, and a dialect that stops working with no
            // explanation reads as the setting being broken.
            voiceDropped = true
        }
        val budget = (ceiling - keptTokens).coerceAtLeast(0)
        val instructionsBlock = if (project.instructions.isEmpty()) {
            ""
        } else {
            "### Project instructions\n" + TokenEstimate.clamp(project.instructions, budget)
        }
        val assembled = listOf(preset, voiceBlock, instructionsBlock, memoryBlock)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return SystemText(TokenEstimate.clamp(assembled, ceiling), voiceDropped)
    }

    /**
     * The language this app is actually speaking, which is not the same question as the user's
     * explicit choice.
     *
     * That choice defaults to "" - follow the system. Reading it alone answers "ar" only for
     * someone who went into Settings and said so, and answers "" for an Arabic phone whose owner
     * never needed to. Falling through to the running locale covers both, and covers the per-app
     * language Android 13 offers from system settings. Getting this wrong is the 2.6 bug where
     * the dialect voice reached nobody whose Arabic came from the phone.
     */
    fun effectiveLanguage(explicitChoice: String, runningLanguage: String): String =
        explicitChoice.ifEmpty { runningLanguage }

    /**
     * The voice for a request in [language]. An empty string is the normal answer for an English
     * install and for MSA, and it is what keeps the 2.5.0 request shape intact for everyone who
     * has never touched this setting. (Translation presets get no voice; ChatRepository decides
     * that before asking.)
     */
    fun voiceFor(language: String, dialect: Dialect): String = when (language) {
        "ar" -> DialectVoice.forDialect(dialect)
        // Thai has no dialect to choose, so there is nothing in Settings for it: answering in
        // Thai IS the choice. The guide behind it is about writing Thai that a Thai reader does
        // not wince at, which is mostly about where the spaces go.
        "th" -> DialectVoice.forThai()
        else -> ""
    }
}
