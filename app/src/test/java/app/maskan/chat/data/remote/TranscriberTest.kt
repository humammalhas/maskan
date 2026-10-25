package app.maskan.chat.data.remote

import app.maskan.chat.golden.Golden
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * What a transcription request puts on the wire, and how each reply shape is read (plan item 3).
 *
 * The audio bytes here are a fixed fake: what matters is the field order, the file name, the path
 * and the headers. The reply bodies have the shapes measured on the real providers in session 11
 * (multipart `{"text":...}`; Gemini `candidates[0].content.parts`), with invented words in them -
 * nobody's real voice note is copied into the source tree.
 */
class TranscriberTest {

    private lateinit var server: MockWebServer
    private val transcriber = Transcriber(OkHttpClient())
    private val audio = "OggS-fake-bytes".toByteArray()

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun base(path: String) = server.url(path).toString()

    @Test
    fun multipartPutsTheModelBeforeTheFileAndRenamesOpusForOpenAi() {
        server.enqueue(MockResponse().setBody("""{"text":" شو رأيك نلتقي بكرة؟ "}"""))
        val text = transcriber.transcribe(
            TranscribeBackends.forProvider("openai")!!, base("/"), "sk-test", audio, "note.opus", "audio/ogg"
        )
        assertEquals("شو رأيك نلتقي بكرة؟", text)

        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        val model = body.indexOf("name=\"model\"")
        val file = body.indexOf("name=\"file\"")
        assertTrue("model must come before file", model in 0 until file)
        assertTrue(body.contains("filename=\"note.ogg\""))
        assertTrue(body.contains("gpt-transcribe"))
        // No language hint: the transcript is the speech as spoken, dialect and all.
        assertTrue(!body.contains("name=\"language\""))
    }

    @Test
    fun otherProvidersKeepTheFileNameAndTheirBasePath() {
        server.enqueue(MockResponse().setBody("""{"text":"hello"}"""))
        // Groq's base URL carries /openai; the path is appended to it, as the probe did.
        transcriber.transcribe(
            TranscribeBackends.forProvider("groq")!!, base("/openai/"), "gsk", audio, "note.opus", "audio/ogg"
        )
        val request = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", request.path)
        assertTrue(request.body.readUtf8().contains("filename=\"note.opus\""))
    }

    @Test
    fun geminiHearsTheFileInsideGenerateContentAndSkipsThoughtParts() {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},""" +
                    """{"text":"إزيك عامل إيه؟"}]}}]}"""
            )
        )
        val text = transcriber.transcribe(
            TranscribeBackends.forProvider("gemini")!!, base("/"), "AIza-test", audio, "note.mp3", "audio/mpeg"
        )
        assertEquals("إزيك عامل إيه؟", text)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.7-flash:generateContent", request.path)
        assertEquals("AIza-test", request.getHeader("x-goog-api-key"))
        // The key is never in the URL.
        assertTrue(!request.path.orEmpty().contains("key="))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"mime_type\":\"audio/mpeg\""))
        assertTrue(body.contains(java.util.Base64.getEncoder().encodeToString(audio)))
    }

    @Test
    fun aProviderErrorCarriesItsOwnWords() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Unsupported file format opus","type":"invalid_request_error"}}""")
        )
        try {
            transcriber.transcribe(
                TranscribeBackends.forProvider("groq")!!, base("/"), "k", audio, "a.opus", "audio/ogg"
            )
            fail("expected a TranscribeException")
        } catch (e: TranscribeException) {
            assertEquals("HTTP 400: Unsupported file format opus", e.message)
        }
    }

    @Test
    fun anEmptyTranscriptIsAFailureNotAMessage() {
        // Parakeet answered 200 with {"text":""} for Arabic in session 11.
        server.enqueue(MockResponse().setBody("""{"text":""}"""))
        try {
            transcriber.transcribe(
                TranscribeBackends.forProvider("venice")!!, base("/api/"), "k", audio, "a.opus", "audio/ogg"
            )
            fail("expected a TranscribeException")
        } catch (e: TranscribeException) {
            assertEquals(Transcriber.EMPTY, e.message)
        }
    }

    @Test
    fun theSevenMeasuredBackends() {
        assertEquals(
            listOf("openai", "groq", "together", "mistral", "venice", "openrouter", "gemini"),
            TranscribeBackends.ALL.map { it.providerId }
        )
        assertEquals(listOf("venice"), TranscribeBackends.ALL.filter { it.anonymizedOnly }.map { it.providerId })
    }

    /** What a model reads for a voice-note turn, in every shape it can take. A golden, like the rest of assembly. */
    @Test
    fun theTranscriptIsWhatTheModelReads() {
        val transcript = "يا جماعة، الاجتماع بكرة الساعة عشرة بدل تسعة."
        val cases = linkedMapOf(
            "no-audio" to app.maskan.chat.data.repository.RequestAssembly.withTranscript("What time?", null, null),
            "audio-only" to app.maskan.chat.data.repository.RequestAssembly.withTranscript("", transcript, "PTT-0001.opus"),
            "audio-and-question" to app.maskan.chat.data.repository.RequestAssembly.withTranscript(
                "اكتبها بالفصحى", transcript, "PTT-0001.opus"
            )
        )
        assertEquals("What time?", cases.getValue("no-audio"))
        Golden.check("assembly-voicenote", cases.entries.joinToString("\n\n---\n\n") { it.key + ":\n" + it.value })
    }
}
