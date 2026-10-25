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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * "Listen and answer" (2.7, plan item 3, layer C): the model HEARS the voice note on this one turn -
 * tone, speakers, dialect, everything a transcript throws away. Two providers out of twelve can:
 * Gemini, inside any generateContent request, and OpenAI's gpt-audio models, as `input_audio` in a
 * chat completion (measured, session 11). The rest of the conversation goes as text, exactly as
 * RequestAssembly built it - the transcript included - so the model has both.
 *
 * One request, not streamed: both answered in seconds in the probe, and gpt-audio's audio input is
 * documented for the plain request. Later turns carry the transcript only; the audio is not resent.
 */
class Listener(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
) {

    /**
     * [format] is OpenAI's word for the bytes ("wav" or "mp3"); Gemini takes [mimeType] as it is.
     * Blocking; call it off the main thread. Throws with the provider's own words on an error.
     */
    fun answer(
        providerId: String,
        model: String,
        baseUrl: String,
        apiKey: String,
        messages: List<Message>,
        audio: ByteArray,
        mimeType: String,
        format: String
    ): String {
        val root = baseUrl.trim().trimEnd('/').removeSuffix("/v1").removeSuffix("/v1beta").trimEnd('/')
        val data = java.util.Base64.getEncoder().encodeToString(audio)
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content.textContent() }.trim()
        val turns = messages.filter { it.role != "system" }
        val lastUser = turns.indexOfLast { it.role == "user" }

        val request = if (providerId == "gemini") {
            val body = buildJsonObject {
                if (system.isNotEmpty()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
                    })
                }
                put("contents", buildJsonArray {
                    turns.forEachIndexed { i, message ->
                        add(buildJsonObject {
                            put("role", if (message.role == "assistant") "model" else "user")
                            put("parts", buildJsonArray {
                                if (i == lastUser) {
                                    add(buildJsonObject {
                                        put("inline_data", buildJsonObject {
                                            put("mime_type", mimeType)
                                            put("data", data)
                                        })
                                    })
                                }
                                add(buildJsonObject { put("text", message.content.textContent()) })
                            })
                        })
                    }
                })
            }
            Request.Builder().url("$root/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(JSON))
                .build()
        } else {
            val body = buildJsonObject {
                put("model", model)
                put("modalities", buildJsonArray { add(JsonPrimitive("text")) })
                put("messages", buildJsonArray {
                    if (system.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", system) })
                    turns.forEachIndexed { i, message ->
                        add(buildJsonObject {
                            put("role", message.role)
                            if (i == lastUser) {
                                put("content", buildJsonArray {
                                    add(buildJsonObject { put("type", "text"); put("text", message.content.textContent()) })
                                    add(buildJsonObject {
                                        put("type", "input_audio")
                                        put("input_audio", buildJsonObject {
                                            put("data", data)
                                            put("format", format)
                                        })
                                    })
                                })
                            } else {
                                put("content", message.content.textContent())
                            }
                        })
                    }
                })
            }
            Request.Builder().url("$root/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(JSON))
                .build()
        }

        val text = client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception(Transcriber.errorMessage(raw, response.code))
            if (providerId == "gemini") Transcriber.parse(TranscribeBackends.Shape.GEMINI, raw) else parseChat(raw)
        }
        if (text.isBlank()) throw Exception("Empty response from $providerId")
        return text
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The one model OpenAI lets hear audio in a chat; the reply says it answered. */
        const val OPENAI_LISTENER = "gpt-audio"

        /** Who can hear: the providers whose chats get "Listen and answer". */
        val PROVIDERS = setOf("gemini", "openai")

        fun parseChat(raw: String): String {
            val root = ApiClients.json.parseToJsonElement(raw).jsonObject
            val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            return (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }
}
