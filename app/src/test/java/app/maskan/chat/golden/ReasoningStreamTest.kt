package app.maskan.chat.golden

import app.maskan.chat.data.remote.ChatCompletionChunk
import app.maskan.chat.data.remote.StreamPiece
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reasoning read out of REAL streams, kept apart from the answer (plan item 2).
 *
 * Each file in src/test/resources/reasoning is the exact body one provider streamed on Humam's key
 * on 2026-10-24 (debug `ReasoningProbeReceiver`), for "A shop sells pens at 7 for 3 dollars. How
 * much do 84 pens cost?". The reading is parseSSEPieces': `data: ` lines, `[DONE]` ends it, each
 * chunk's delta split by StreamDelta.pieces(), empty pieces dropped. The answer must be "36" and
 * must carry none of the thinking - the thinking is what is shown, the answer what is saved and
 * sent back.
 */
class ReasoningStreamTest {

    private val dir = File("src/test/resources/reasoning")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun deepSeekSendsReasoningContent() = checkThinks("deepseek")

    @Test
    fun togetherSendsReasoningContent() = checkThinks("together")

    @Test
    fun anOllamaServerSendsReasoning() = checkThinks("custom")

    @Test
    fun aStreamWithoutReasoningIsAllAnswer() {
        val (thinking, answer) = read("groq")
        assertEquals("", thinking)
        assertEquals("36", answer.trim())
    }

    private fun checkThinks(name: String) {
        val (thinking, answer) = read(name)
        assertTrue("$name: no thinking read", thinking.length > 20)
        assertEquals("$name: answer", "36", answer.trim())
        assertFalse("$name: thinking leaked into the answer", answer.contains(thinking.take(20)))
    }

    private fun read(name: String): Pair<String, String> {
        val thinking = StringBuilder()
        val answer = StringBuilder()
        for (line in File(dir, "$name-stream.txt").readLines()) {
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
            for (piece in chunk.choices.firstOrNull()?.delta?.pieces().orEmpty()) {
                when (piece) {
                    is StreamPiece.Thinking -> thinking.append(piece.text)
                    is StreamPiece.Answer -> answer.append(piece.text)
                }
            }
        }
        return thinking.toString() to answer.toString()
    }
}
