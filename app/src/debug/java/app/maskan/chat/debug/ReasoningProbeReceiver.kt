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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Where does each provider put a model's reasoning? Asked of the provider, not its docs (plan item 2).
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * One streamed request per provider that has a key (or, for a local server, a URL), on the model the
 * user selected there. The request asks for nothing extra - no thinking switch, no include-thoughts -
 * because Humam decided (2026-10-24) that thinking is never turned on where it is off by default.
 * The raw stream is saved to files/probe/reason-<provider>.txt and a summary is logged: where the
 * reasoning arrived (a `reasoning_content` / `reasoning` field, `<think>` tags in the text, Anthropic
 * `thinking` blocks, Gemini `thought` parts) or that none did, and how long the first byte took.
 *
 *   adb shell am start -n app.maskan.chat.debug/app.maskan.chat.debug.ProbeActivity --es probe reasoning
 *   ... --es models "groq=qwen/qwen3-32b;openrouter=deepseek/deepseek-r1"   (override, these only)
 *   ... --es what catalogue      (log each provider's model ids that look like reasoning models)
 *   ... --es question64 <base64 UTF-8>   (ask this instead of QUESTION; the file name gets "-q")
 *
 * Keys go into a header (Gemini: the query string, as the app sends it) and are never logged; no URL
 * is logged either. Lines are tagged MaskanReason. Keep the app in the foreground while it runs.
 */
class ReasoningProbeReceiver : BroadcastReceiver() {

    private data class Target(val provider: String, val model: String, val base: String, val key: String)

    private var question = QUESTION
    private var asked = false

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication ?: return
        val overrides = intent.getStringExtra("models")?.split(';', ',')?.mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        val providers = overrides?.map { it.first } ?: PROVIDERS
        val targets = providers.mapNotNull { id ->
            val provider = ProviderRegistry.getProvider(id) ?: return@mapNotNull null
            val key = app.keyRepository.getApiKey(id).orEmpty()
            val base = (app.keyRepository.getBaseUrl(id) ?: provider.defaultBaseUrl).trim()
            val local = id in setOf("ollama", "lmstudio", "custom")
            when {
                key.isBlank() && !local -> { Log.i(TAG, "skip " + id + ": no key"); null }
                local && (base.isBlank() || app.keyRepository.getBaseUrl(id) == null) -> {
                    Log.i(TAG, "skip " + id + ": no server URL"); null
                }
                else -> Target(
                    id,
                    overrides?.firstOrNull { it.first == id }?.second
                        ?: app.keyRepository.getSelectedModel(id) ?: provider.defaultModel,
                    if (base.endsWith("/")) base else "$base/",
                    key
                )
            }
        }
        // --es extra64 <base64 JSON object>: merged into an OpenAI-shaped body, to try a provider's
        // own "thinking off" switch. Absent: the request asks for nothing extra.
        val extra = intent.getStringExtra("extra64")?.let {
            Json.parseToJsonElement(
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
            ).jsonObject
        }
        intent.getStringExtra("question64")?.let {
            question = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
            asked = true
        }
        val probeDir = File(app.filesDir, "probe").apply { mkdirs() }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (target in targets) {
                try {
                    if (intent.getStringExtra("what") == "catalogue") catalogue(target) else ask(target, probeDir, extra)
                } catch (e: Throwable) {
                    Log.e(TAG, target.provider + " failed: " + e.javaClass.simpleName + ": " + e.message)
                }
            }
            Log.i(TAG, "DONE")
        }
    }

    private fun ask(target: Target, dir: File, extra: JsonObject?) {
        val request = when (target.provider) {
            "anthropic" -> Request.Builder().url(target.base + "v1/messages")
                .header("x-api-key", target.key)
                .header("anthropic-version", "2023-06-01")
                .post(json(buildJsonObject {
                    put("model", target.model)
                    put("max_tokens", 600)
                    put("stream", true)
                    put("messages", userTurn())
                }.toString()))
            "gemini" -> Request.Builder()
                .url(target.base + "v1beta/models/" + target.model + ":streamGenerateContent?alt=sse&key=" + target.key)
                .post(json(buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", question) }) })
                        })
                    })
                }.toString()))
            else -> Request.Builder().url(target.base + "v1/chat/completions").apply {
                if (target.key.isNotBlank()) header("Authorization", "Bearer " + target.key)
            }.post(json(buildJsonObject {
                put("model", target.model)
                // OpenAI's current models refuse max_tokens (measured: HTTP 400).
                put(if (target.provider == "openai") "max_completion_tokens" else "max_tokens", 600)
                put("stream", true)
                put("messages", userTurn())
                extra?.forEach { (key, value) -> put(key, value) }
            }.toString()))
        }.build()

        val started = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val source = response.body?.source()
            val text = StringBuilder()
            var firstByteMs = -1L
            if (source != null) {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - started
                    text.append(line).append('\n')
                }
            }
            val body = text.toString()
            File(
                dir,
                "reason-" + target.provider + "-" + target.model.replace(Regex("[^A-Za-z0-9.]"), "_") +
                    (if (extra != null) "-extra" else "") + (if (asked) "-q" else "") + ".txt"
            ).writeText(body)
            val tokens = Regex("\"(completion_tokens|reasoning_tokens|eval_count)\":(\\d+)").findAll(body)
                .map { it.groupValues[1] + "=" + it.groupValues[2] }.toList().takeLast(2).joinToString(" ")
            val found = MARKERS.filter { (_, needle) -> body.contains(needle) }
                .joinToString(" ") { (name, needle) -> name + "x" + occurrences(body, needle) }
            Log.i(
                TAG,
                target.provider + " model=" + target.model + " HTTP " + response.code +
                    " firstByte=" + firstByteMs + "ms total=" + (System.currentTimeMillis() - started) +
                    "ms chars=" + body.length + (if (extra != null) " extra=" + extra else "") + " " + tokens +
                    " reasoning: " + found.ifEmpty { "NONE" }
            )
            if (response.code != 200) Log.i(TAG, target.provider + " body: " + body.take(600))
        }
    }

    /** The model ids a provider lists that look like reasoning models, to pick from for a second run. */
    private fun catalogue(target: Target) {
        val url = when (target.provider) {
            "anthropic" -> target.base + "v1/models"
            "gemini" -> target.base + "v1beta/models?key=" + target.key
            else -> target.base + "v1/models"
        }
        val builder = Request.Builder().url(url).get()
        when (target.provider) {
            "anthropic" -> builder.header("x-api-key", target.key).header("anthropic-version", "2023-06-01")
            "gemini" -> Unit
            else -> if (target.key.isNotBlank()) builder.header("Authorization", "Bearer " + target.key)
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val ids = Regex("\"(?:id|name)\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }
                .filter { id -> CATALOGUE_HINTS.any { id.contains(it, ignoreCase = true) } }
                .distinct().take(40).toList()
            Log.i(TAG, "catalogue " + target.provider + " HTTP " + response.code + ": " + ids.joinToString(", "))
        }
    }

    private fun userTurn() = buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            put("content", question)
        })
    }

    private fun json(body: String) = body.toRequestBody("application/json".toMediaType())

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.windowed(needle.length, 1).count { it == needle }

    private companion object {
        const val TAG = "MaskanReason"
        const val QUESTION = "A shop sells pens at 7 for 3 dollars. How much do 84 pens cost? Answer with the number only."
        val PROVIDERS = listOf(
            "deepseek", "openai", "groq", "together", "mistral", "venice", "openrouter",
            "anthropic", "gemini", "ollama", "lmstudio", "custom"
        )
        // What a reasoning channel looks like in each shape, as raw substrings of the stream.
        val MARKERS = listOf(
            "reasoning_content" to "\"reasoning_content\":\"",
            "reasoning" to "\"reasoning\":\"",
            "reasoning_details" to "\"reasoning_details\"",
            "think-tag" to "<think>",
            "anthropic-thinking" to "\"thinking_delta\"",
            "gemini-thought" to "\"thought\""
        )
        val CATALOGUE_HINTS = listOf("think", "reason", "r1", "qwen3", "magistral", "gpt-oss", "o3", "o4")
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
