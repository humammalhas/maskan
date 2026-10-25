package app.maskan.chat.data.remote

import app.maskan.chat.util.AudioToWav
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Listen and answer" on the wire (plan item 3, layer C): the conversation as text, and the audio on
 * the LAST user turn only - Gemini as inline_data in its own model, OpenAI as input_audio to
 * gpt-audio. Reply bodies have the shapes measured in session 11, with invented words.
 */
class ListenerTest {

    private lateinit var server: MockWebServer
    private val listener = Listener(OkHttpClient())
    private val audio = "fake-audio".toByteArray()
    private val encoded = java.util.Base64.getEncoder().encodeToString(audio)
    private val messages = listOf(
        Message("system", "Answer in Levantine."),
        Message("user", "first question"),
        Message("assistant", "first answer"),
        Message("user", "[A voice note the user attached: n.opus. ...]\n\nشو بدّه؟")
    )

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun geminiHearsTheLastTurnInTheChatsOwnModel() {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"بدّه يعرف الموعد."}]}}]}"""))
        val text = listener.answer(
            "gemini", "gemini-3.7-flash", server.url("/").toString(), "AIza", messages, audio, "audio/ogg", ""
        )
        assertEquals("بدّه يعرف الموعد.", text)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.7-flash:generateContent", request.path)
        assertEquals("AIza", request.getHeader("x-goog-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"systemInstruction\""))
        assertTrue(body.contains("\"role\":\"model\""))
        // The audio appears once, and after the earlier turns.
        assertEquals(1, Regex("inline_data").findAll(body).count())
        assertTrue(body.indexOf("inline_data") > body.indexOf("first answer"))
        assertTrue(body.contains("\"mime_type\":\"audio/ogg\""))
        assertTrue(body.contains(encoded))
    }

    @Test
    fun openAiHearsThroughGptAudioWithTheFormatGiven() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"He asks when."}}]}"""))
        val text = listener.answer(
            "openai", Listener.OPENAI_LISTENER, server.url("/").toString(), "sk", messages, audio, "audio/wav", "wav"
        )
        assertEquals("He asks when.", text)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-audio\""))
        assertTrue(body.contains("\"modalities\":[\"text\"]"))
        // The audio rides the last user turn only.
        assertEquals(1, Regex("\"type\":\"input_audio\"").findAll(body).count())
        assertTrue(body.indexOf("input_audio") > body.indexOf("first answer"))
        assertTrue(body.contains("\"format\":\"wav\""))
        assertTrue(body.contains(encoded))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    /** "Rewrite in…" (layer B) asks for the target's voice in Arabic whatever the app speaks. */
    @Test
    fun everyRewriteTargetHasAnArabicVoice() {
        for (dialect in app.maskan.chat.data.model.Dialect.entries) {
            assertTrue(dialect.id, app.maskan.chat.data.repository.RequestAssembly.voiceFor("ar", dialect).isNotBlank())
        }
    }

    @Test
    fun aWavIsSixteenKilohertzMonoSixteenBit() {
        val pcm = ShortArray(48_000) { (it % 100).toShort() }       // one second at 48 kHz
        val resampled = AudioToWav.resample(pcm, 48_000)
        assertEquals(16_000, resampled.size)
        val wav = AudioToWav.wav(resampled)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, header.getShort(22).toInt())               // mono
        assertEquals(16_000, header.getInt(24))                    // sample rate
        assertEquals(16, header.getShort(34).toInt())              // bits per sample
        assertEquals(32_000, header.getInt(40))                    // data bytes
        assertEquals(44 + 32_000, wav.size)
    }
}
