package app.maskan.chat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record a mid-chat model switch leaves in the transcript (2.7, plan item 10). */
class ModelSwitchTest {

    @Test
    fun roundTripsModelIdsWithEverySeparatorTheyUse() {
        val move = ModelSwitch.Move("openrouter", "deepseek/deepseek-v4.1-flash:free", "ollama", "qwen2.5vl:7b|x")
        assertEquals(move, ModelSwitch.decode(ModelSwitch.encode(move)))
    }

    @Test
    fun anyOtherNoticeIsNotASwitch() {
        assertNull(ModelSwitch.decode(ChatRepository.NOTICE_VOICE_DROPPED))
        assertNull(ModelSwitch.decode("model_switched\nonly\nthree"))
        assertNull(ModelSwitch.decode("Amman."))
    }

    @Test
    fun aChatIsSwitchedOnlyByASwitchNotice() {
        val switch = ModelSwitch.encode(ModelSwitch.Move("deepseek", "deepseek-flash", "groq", "qwen/qwen3.8-27b"))
        assertTrue(ModelSwitch.isSwitched(listOf("user" to "hi", ChatRepository.ROLE_NOTICE to switch)))
        assertFalse(ModelSwitch.isSwitched(listOf("user" to "hi", ChatRepository.ROLE_NOTICE to ChatRepository.NOTICE_VOICE_DROPPED)))
        // A user who TYPES the marker has not moved the chat.
        assertFalse(ModelSwitch.isSwitched(listOf("user" to switch)))
    }

    @Test
    fun movingAwayAndStraightBackIsNothing() {
        assertTrue(ModelSwitch.Move("groq", "qwen/qwen3.8-27b", "groq", " qwen/qwen3.8-27b ").isNoOp)
        assertFalse(ModelSwitch.Move("groq", "a", "openrouter", "a").isNoOp)
    }
}
