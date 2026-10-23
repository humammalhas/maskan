package app.maskan.chat.golden

import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent

/**
 * The conversations every golden is built from. Changing one re-records every golden that uses
 * it, so add a new fixture rather than editing an old one.
 *
 * Arabic and Thai are in the text on purpose: a change in how non-Latin text is escaped on the
 * wire is exactly the kind of difference nobody sees in a debug log.
 */
object Fixtures {

    const val SYSTEM = "You are a helpful assistant. Answer briefly."

    /** A preset's system row, three finished turns and a new question. */
    val plain: List<Message> = listOf(
        Message("system", SYSTEM),
        Message("user", "What is the capital of Jordan?"),
        Message("assistant", "Amman."),
        Message("user", "ما عاصمة الأردن؟ وما أشهر معالمها؟"),
        Message("assistant", "عمّان. ومن أشهر معالمها جبل القلعة والمدرّج الروماني."),
        Message("user", "เมืองหลวงของไทยคืออะไร")
    )

    /** "No style": no system row at all. */
    val noSystem: List<Message> = plain.filter { it.role != "system" }

    /** An old chat with two system rows - the 2.5 trap that one provider rejected outright. */
    val twoSystems: List<Message> = listOf(Message("system", "Always answer in Arabic.")) + plain

    /**
     * A photo sent two turns ago and carried forward (ChatRepository.photosToCarry): the bytes
     * are already base64 in the row, so no encoder runs on this path.
     */
    val carriedPhoto: List<Message> = listOf(
        Message("system", SYSTEM),
        Message(
            "user",
            MessageContent.WithImage(
                text = "ما هذا؟",
                imageBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                mimeType = "image/jpeg"
            )
        ),
        Message("assistant", "A blue rectangle with the number 83."),
        Message("user", "What colour is the number?")
    )

    /**
     * A chat answered on DeepSeek, then moved to another provider (2.7, plan item 10): the rows
     * as stored, notice included. What goes out must be [plain]'s turns plus the new question,
     * with no trace of the notice.
     */
    val switchedRows: List<Message> = plain.dropLast(1) + listOf(
        Message("notice", "model_switched\ndeepseek\ndeepseek-flash\ngroq\nqwen/qwen3.8-27b"),
        Message("user", "And of Thailand?")
    )

    /** A new photo arrives as raw bytes beside the messages, never inside them. */
    val photoBytes: ByteArray = ByteArray(48) { it.toByte() }
    const val PHOTO_MIME = "image/jpeg"
}
