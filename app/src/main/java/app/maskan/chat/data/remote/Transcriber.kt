package app.maskan.chat.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Who can turn an audio file into text, and how (2.7, plan item 3, layer 2).
 *
 * Measured on Humam's keys in session 11 (`AudioProbeReceiver`, 2.7_sessions/NOTES.md): seven of the
 * twelve providers transcribe. Six take the same multipart POST <root>/v1/audio/transcriptions;
 * Gemini has no such endpoint and hears the file inside an ordinary generateContent request.
 * DeepSeek, Anthropic, Ollama, LM Studio and on-device have no audio input at all, and Custom is a
 * server that does not say it transcribes - none of them appear here.
 *
 * The transcript is the speech AS SPOKEN: no language hint goes with the file, so a Levantine note
 * stays Levantine. Rewriting it into Fusha or another dialect is a separate, later step.
 */
object TranscribeBackends {

    enum class Shape { MULTIPART, GEMINI }

    data class Backend(
        val providerId: String,
        val model: String,
        val shape: Shape,
        /** The provider's own label for what it keeps, where it says the model is not "private". */
        val anonymizedOnly: Boolean = false
    )

    /** In registry order; the user's own provider order decides the default. */
    val ALL = listOf(
        Backend("openai", "gpt-transcribe", Shape.MULTIPART),
        Backend("groq", "whisper-large-v3", Shape.MULTIPART),
        Backend("together", "openai/whisper-large-v3", Shape.MULTIPART),
        Backend("mistral", "voxtral-mini-transcribe-3", Shape.MULTIPART),
        // Venice marks scribe-v2 "anonymized", not "private": the privacy line says so.
        Backend("venice", "elevenlabs/scribe-v2", Shape.MULTIPART, anonymizedOnly = true),
        Backend("openrouter", "openai/whisper-large-v3", Shape.MULTIPART),
        Backend("gemini", "gemini-3.7-flash", Shape.GEMINI)
    )

    fun forProvider(providerId: String): Backend? = ALL.firstOrNull { it.providerId == providerId }

    /** The largest file any of the seven is documented to take in one request (OpenAI, Groq: 25 MB). */
    const val MAX_BYTES = 25L * 1024 * 1024
}

class TranscribeException(message: String) : Exception(message)

class Transcriber(
    // Its own client: a three-minute note takes seconds, but a long recording on a slow phone
    // connection must not be cut by the chat client's 60 s read timeout.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
) {

    /** Blocking; call it off the main thread. Returns the transcript or throws [TranscribeException]. */
    fun transcribe(
        backend: TranscribeBackends.Backend,
        baseUrl: String,
        apiKey: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String
    ): String {
        val root = baseUrl.trim().trimEnd('/').removeSuffix("/v1").removeSuffix("/v1beta").trimEnd('/')
        val request = when (backend.shape) {
            TranscribeBackends.Shape.MULTIPART -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    // model BEFORE file: Together reads the fields in order (session 11, probe).
                    .addFormDataPart("model", backend.model)
                    .addFormDataPart(
                        "file",
                        wireName(backend.providerId, fileName),
                        bytes.toRequestBody(mimeType.toMediaType())
                    )
                    .build()
                Request.Builder().url("$root/v1/audio/transcriptions")
                    // A local server (the AI PC proxy) has no key; send no header rather than "Bearer ".
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .post(body)
                    .build()
            }
            TranscribeBackends.Shape.GEMINI -> {
                val body = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("inline_data", buildJsonObject {
                                        put("mime_type", mimeType)
                                        put("data", java.util.Base64.getEncoder().encodeToString(bytes))
                                    })
                                })
                                add(buildJsonObject { put("text", GEMINI_PROMPT) })
                            })
                        })
                    })
                }.toString()
                Request.Builder().url("$root/v1beta/models/${backend.model}:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            }
        }

        val text = try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw TranscribeException(errorMessage(raw, response.code))
                parse(backend.shape, raw)
            }
        } catch (e: TranscribeException) {
            throw e
        } catch (e: Exception) {
            throw TranscribeException(e.message ?: e.javaClass.simpleName)
        }
        if (text.isBlank()) throw TranscribeException(EMPTY)
        return text.trim()
    }

    companion object {
        /** Thrown text when a backend answered 200 with nothing (Parakeet did, on Arabic). */
        const val EMPTY = "empty transcript"

        const val GEMINI_PROMPT =
            "Transcribe this audio exactly as spoken, in the dialect and language the speaker uses. " +
                "Do not translate, correct or rewrite it into Modern Standard Arabic. Output only the transcript."

        /**
         * OpenAI refuses the name "x.opus" ("Unsupported file format opus") and accepts the same bytes
         * named "x.ogg" (session 11). A WhatsApp voice note IS an Ogg file, so this is a rename of
         * the container, not a conversion. Humam approved it.
         */
        fun wireName(providerId: String, fileName: String): String =
            if (providerId == "openai" && fileName.lowercase().endsWith(".opus")) {
                fileName.dropLast(".opus".length) + ".ogg"
            } else {
                fileName
            }

        fun parse(shape: TranscribeBackends.Shape, raw: String): String {
            val root = ApiClients.json.parseToJsonElement(raw).jsonObject
            return when (shape) {
                TranscribeBackends.Shape.MULTIPART -> (root["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                TranscribeBackends.Shape.GEMINI -> {
                    val parts = ((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                        ?.let { it["content"] as? JsonObject }
                        ?.let { it["parts"] as? JsonArray }
                        .orEmpty()
                    parts.mapNotNull { part ->
                        val obj = part as? JsonObject ?: return@mapNotNull null
                        if ((obj["thought"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
                        (obj["text"] as? JsonPrimitive)?.contentOrNull
                    }.joinToString("")
                }
            }
        }

        /** The provider's own words when it gave any (`error.message`), else the status code. */
        fun errorMessage(raw: String, code: Int): String {
            val message = runCatching {
                val root = ApiClients.json.parseToJsonElement(raw).jsonObject
                ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["detail"] as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            return "HTTP $code" + (message?.let { ": $it" } ?: "")
        }
    }
}
