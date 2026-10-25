package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.remote.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Which providers transcribe audio, and what do they make of real Arabic? Asked of the provider,
 * not its docs (plan item 3, 2.7 session 11, phase 1).
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * Three steps, run one call at a time:
 *
 *   adb shell am start -n app.maskan.chat.debug/app.maskan.chat.debug.ProbeActivity --es probe audio \
 *       --es what catalogue
 *     Each provider's /models ids that look like audio models, and the Custom server's /health keys.
 *     NO AUDIO IS SENT.
 *
 *   ... --es what transcribe --es target groq=whisper-large-v3 --es file <name>
 *       [--es language ar] [--es format verbose_json] [--es sendas <wire file name>]
 *     multipart POST <root>/v1/audio/transcriptions, `model` before `file` (Together needs that order).
 *
 *   ... --es what native --es target gemini=<model> --es file <name>
 *     The clip inside the chat request (Gemini inline data; OpenAI-shaped `input_audio`) with three
 *     questions a transcript cannot answer: tone, number of speakers, dialect.
 *
 * Privacy (Humam's test audio is other people's voices): the probe reads ONLY
 * files/probe/audio/<name> in the app's private storage - a name with a path in it is refused - and
 * sends it to the ONE provider named in `target`. There is no loop over providers or files. The
 * response goes to files/probe/audio-out/ and is never logged: logcat gets the status, timing, sizes,
 * usage/cost and, on an error, the provider's error body. Keys go in headers and are never logged;
 * no URL is logged either. Lines are tagged MaskanAudio. Keep the app in the foreground while it runs.
 */
class AudioProbeReceiver : BroadcastReceiver() {

    private data class Target(val provider: String, val model: String, val root: String, val key: String)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication ?: return
        val what = intent.getStringExtra("what") ?: "catalogue"
        val probeDir = File(app.filesDir, "probe")
        val outDir = File(probeDir, "audio-out").apply { mkdirs() }
        logFile = File(outDir, "probe.log")

        fun target(id: String, model: String): Target? {
            val provider = ProviderRegistry.getProvider(id) ?: run { log("unknown provider " + id); return null }
            val key = app.keyRepository.getApiKey(id).orEmpty()
            val local = id in setOf("ollama", "lmstudio", "custom")
            val stored = app.keyRepository.getBaseUrl(id)
            val base = (stored ?: provider.defaultBaseUrl).trim()
            return when {
                key.isBlank() && !local -> { log("skip " + id + ": no key"); null }
                local && (base.isBlank() || stored == null) -> { log("skip " + id + ": no server URL"); null }
                else -> Target(id, model, root(base), key)
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (what) {
                    "catalogue" -> for (id in PROVIDERS) {
                        val t = target(id, "") ?: continue
                        try { catalogue(t) } catch (e: Throwable) {
                            log("ERROR " + id + " failed: " + e.javaClass.simpleName + ": " + e.message)
                        }
                    }
                    "transcribe", "native" -> {
                        val spec = intent.getStringExtra("target")?.split('=', limit = 2)
                        val name = intent.getStringExtra("file").orEmpty()
                        if (spec == null || spec.size != 2) { log("ERROR " + "need --es target provider=model"); return@launch }
                        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.startsWith(".")) {
                            log("ERROR " + "refused file name (a bare name inside files/probe/audio only)"); return@launch
                        }
                        val clip = File(File(probeDir, "audio"), name)
                        if (!clip.isFile) { log("ERROR " + "no such clip in files/probe/audio: " + name); return@launch }
                        val t = target(spec[0].trim(), spec[1].trim()) ?: return@launch
                        if (what == "transcribe") transcribe(t, clip, intent, outDir)
                        else native(t, clip, outDir, intent.getStringExtra("ask") == "transcribe")
                    }
                    else -> log("ERROR " + "unknown what=" + what)
                }
            } catch (e: Throwable) {
                log("ERROR " + "failed: " + e.javaClass.simpleName + ": " + e.message)
            }
            log("DONE")
        }
    }

    /** Audio-looking model ids from /models (and, for Custom, the keys /health advertises). */
    private fun catalogue(t: Target) {
        val urls = when (t.provider) {
            "anthropic" -> listOf(t.root + "/v1/models")
            "gemini" -> listOf(t.root + "/v1beta/models?pageSize=1000")
            // Venice filters /models by type; ask for all of them as well as the default listing.
            "venice" -> listOf(t.root + "/v1/models", t.root + "/v1/models?type=all")
            else -> listOf(t.root + "/v1/models")
        }
        for (url in urls) {
            listing.newCall(authed(t, Request.Builder().url(url).get()).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val ids = Regex("\"(?:id|name)\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }
                    .filter { id -> AUDIO_HINTS.any { id.contains(it, ignoreCase = true) } }
                    .distinct().take(60).toList()
                val variant = if (url.contains("type=all")) " (type=all)" else ""
                log("catalogue " + t.provider + variant + " HTTP " + response.code + ": " + ids.joinToString(", ").ifEmpty { "NONE" })
                if (response.code != 200) log(t.provider + " body: " + body.take(400))
            }
        }
        if (t.provider in setOf("custom", "ollama", "lmstudio")) {
            listing.newCall(authed(t, Request.Builder().url(t.root + "/health").get()).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val keys = Regex("\"([A-Za-z_]+)\"\\s*:").findAll(body).map { it.groupValues[1] }.distinct().toList()
                log("health " + t.provider + " HTTP " + response.code + " keys: " + keys.joinToString(", ").ifEmpty { "NONE" } +
                    " transcribe=" + body.contains("transcri", ignoreCase = true))
            }
        }
    }

    private fun transcribe(t: Target, clip: File, intent: Intent, outDir: File) {
        val bytes = clip.readBytes()
        val wireName = intent.getStringExtra("sendas") ?: clip.name
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", t.model)
            .addFormDataPart("file", wireName, bytes.toRequestBody(mime(wireName).toMediaType()))
            .apply {
                intent.getStringExtra("language")?.let { addFormDataPart("language", it) }
                intent.getStringExtra("format")?.let { addFormDataPart("response_format", it) }
            }
            .build()
        val request = authed(t, Request.Builder().url(t.root + "/v1/audio/transcriptions").post(body)).build()
        val tag = listOfNotNull(
            intent.getStringExtra("language")?.let { "lang-$it" },
            intent.getStringExtra("format"),
            intent.getStringExtra("sendas")?.let { "as-" + safe(it) }
        ).joinToString("-")
        send(t, clip, request, body.contentLength(), "transcribe" + (if (tag.isNotEmpty()) "-$tag" else ""), outDir)
    }

    private fun native(t: Target, clip: File, outDir: File, asTranscript: Boolean) {
        val question = if (asTranscript) TRANSCRIBE_QUESTION else NATIVE_QUESTION
        val b64 = android.util.Base64.encodeToString(clip.readBytes(), android.util.Base64.NO_WRAP)
        val json = when (t.provider) {
            "gemini" -> buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", mime(clip.name))
                                    put("data", b64)
                                })
                            })
                            add(buildJsonObject { put("text", question) })
                        })
                    })
                })
            }
            "anthropic" -> { log("ERROR " + "anthropic takes no audio"); return }
            else -> buildJsonObject {
                put("model", t.model)
                put("modalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", question) })
                            add(buildJsonObject {
                                put("type", "input_audio")
                                put("input_audio", buildJsonObject {
                                    put("data", b64)
                                    // Sent as the file says; OpenAI documents wav/mp3 only - its refusal is the finding.
                                    put("format", clip.name.substringAfterLast('.', "").lowercase())
                                })
                            })
                        })
                    })
                })
            }
        }.toString()
        val url = if (t.provider == "gemini") t.root + "/v1beta/models/" + t.model + ":generateContent"
        else t.root + "/v1/chat/completions"
        val body = json.toRequestBody("application/json".toMediaType())
        send(t, clip, authed(t, Request.Builder().url(url).post(body)).build(), body.contentLength(), if (asTranscript) "native-transcript" else "native", outDir)
    }

    private fun send(t: Target, clip: File, request: Request, bytesSent: Long, kind: String, outDir: File) {
        log("SENDING " + clip.name + " (" + clip.length() + " bytes) to " + t.provider + " model=" + t.model + " " + kind)
        val started = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val secs = (System.currentTimeMillis() - started) / 1000.0
            val out = File(outDir, safe(clip.name) + "__" + t.provider + "__" + safe(t.model) + "__" + kind + ".json")
            out.writeText(
                "{\"clip\":\"" + clip.name + "\",\"clipBytes\":" + clip.length() + ",\"bytesSent\":" + bytesSent +
                    ",\"provider\":\"" + t.provider + "\",\"model\":\"" + t.model + "\",\"kind\":\"" + kind +
                    "\",\"http\":" + response.code + ",\"seconds\":" + secs + ",\"response\":\n" + text + "\n}\n"
            )
            val usage = Regex("\"(cost|usage|duration|total_tokens|prompt_tokens|promptTokenCount|totalTokenCount|audio_tokens|seconds)\"\\s*:\\s*([0-9.]+|\\{[^}]*\\})")
                .findAll(text).map { it.groupValues[1] + "=" + it.groupValues[2] }.distinct().take(8).joinToString(" ")
            log(
                t.provider + " model=" + t.model + " " + kind + " HTTP " + response.code + " " + secs + "s sent=" +
                    bytesSent + "B reply=" + text.length + "chars " + usage.ifEmpty { "no usage" } + " -> " + out.name
            )
            if (response.code != 200) log(t.provider + " error body: " + text.take(600))
        }
    }

    /** Logcat, and the same line appended to audio-out/probe.log: polling logcat over adb floods its buffer. */
    private fun log(line: String) {
        Log.i(TAG, line)
        logFile?.appendText(java.time.LocalTime.now().toString().take(8) + " " + line + "\n")
    }

    private fun authed(t: Target, builder: Request.Builder): Request.Builder = when (t.provider) {
        "anthropic" -> builder.header("x-api-key", t.key).header("anthropic-version", "2023-06-01")
        "gemini" -> builder.header("x-goog-api-key", t.key)
        else -> if (t.key.isNotBlank()) builder.header("Authorization", "Bearer " + t.key) else builder
    }

    private fun root(base: String) = base.trim().trimEnd('/').removeSuffix("/v1").removeSuffix("/v1beta").trimEnd('/')

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9.]"), "_")

    private fun mime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "opus", "ogg", "oga" -> "audio/ogg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3", "mpga", "mpeg" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "MaskanAudio"
        const val NATIVE_QUESTION =
            "Listen to this voice message and answer three questions, briefly. " +
                "1. Does the speaker sound angry, calm, or something else? Say what in the voice tells you. " +
                "2. How many different speakers are there? " +
                "3. Which Arabic dialect is this (country or region), or which language if it is not Arabic? " +
                "Do not transcribe the message."
        // --es ask transcribe: the same native request as a transcription backend (Gemini has no audio endpoint).
        const val TRANSCRIBE_QUESTION =
            "Transcribe this audio exactly as spoken, in the dialect and language the speaker uses. " +
                "Do not translate, correct or rewrite it into Modern Standard Arabic. Output only the transcript."
        val PROVIDERS = listOf(
            "openai", "groq", "together", "mistral", "venice", "openrouter", "deepseek",
            "anthropic", "gemini", "ollama", "lmstudio", "custom"
        )
        val AUDIO_HINTS = listOf(
            "whisper", "transcri", "voxtral", "parakeet", "scribe", "wizper", "stt", "audio",
            "speech", "nemotron", "deepgram", "canary", "asr", "native-audio"
        )
        // OpenRouter's upstream cut is said to be 60 s; ours is longer so theirs is what we see.
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
        // A model listing that has not answered in 30 s is the finding; it must not hold up the rest.
        val listing: OkHttpClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        var logFile: File? = null
    }
}
