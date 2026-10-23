package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import app.maskan.chat.MaskanApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Raw requests to a cloud provider with the key the app already holds, status and body straight
 * to logcat - so "which model can see" is answered by the provider, not by its docs.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * The key is read from KeyRepository and goes into the Authorization header and nowhere else.
 * It is never logged, not even a prefix: the log line says only whether a key was found.
 *
 *   adb shell am broadcast -n app.maskan.chat.debug/app.maskan.chat.debug.ProviderProbeReceiver \
 *       --es what models
 *   ... --es what vision --es model deepseek-flash
 *   ... --es what text --es model mistral-small-latest     (a 429 on images: is it the model?)
 *   ... --es what search --es query test
 *
 * `--es provider <id>` picks the key (default deepseek); `--es base <url>` overrides the base
 * URL (default https://api.deepseek.com). The vision test draws its own picture - a red circle
 * and the number 47 on white - so the answer can be checked without pushing a file.
 *
 * Keep the app in the foreground while it runs: there is no goAsync here because a vision call
 * can outlast the broadcast window, and a backgrounded process may be killed mid-request.
 */
class ProviderProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication
        if (app == null) {
            Log.w(TAG, "no MaskanApplication")
            return
        }
        val providerId = intent.getStringExtra("provider") ?: "deepseek"
        val base = (intent.getStringExtra("base") ?: "https://api.deepseek.com").trimEnd('/')
        val what = intent.getStringExtra("what") ?: "models"

        val key = app.keyRepository.getApiKey(providerId)
        if (key.isNullOrBlank()) {
            Log.w(TAG, "no key stored for " + providerId)
            return
        }
        Log.i(TAG, "key found for " + providerId + "; what=" + what + " base=" + base)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (what) {
                    "models" -> send("GET /v1/models", get(base + "/v1/models", key))
                    "vision" -> {
                        val model = intent.getStringExtra("model") ?: "deepseek-flash"
                        send("vision model=" + model, post(base + "/v1/chat/completions", key, visionBody(model)))
                    }
                    "text" -> {
                        val model = intent.getStringExtra("model") ?: "deepseek-flash"
                        val body = buildJsonObject {
                            put("model", model)
                            put("max_tokens", 20)
                            put("messages", buildJsonArray {
                                add(buildJsonObject {
                                    put("role", "user")
                                    put("content", "Say OK.")
                                })
                            })
                        }.toString()
                        send("text model=" + model, post(base + "/v1/chat/completions", key, body))
                    }
                    "search" -> {
                        val query = intent.getStringExtra("query") ?: "test"
                        val body = buildJsonObject { put("query", query) }.toString()
                        send("POST /v1/tools/search", post(base + "/v1/tools/search", key, body))
                    }
                    else -> Log.w(TAG, "unknown what=" + what)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed: " + e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    private fun get(url: String, key: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer " + key).get().build()

    private fun post(url: String, key: String, json: String): Request =
        Request.Builder().url(url)
            .header("Authorization", "Bearer " + key)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

    private fun send(label: String, request: Request) {
        val started = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.i(TAG, "== " + label + " -> HTTP " + response.code + " in " +
                (System.currentTimeMillis() - started) + " ms, " + body.length + " chars ==")
            // logcat truncates a line near 4 KB; chunk so a long catalogue arrives whole.
            body.chunked(3000).forEachIndexed { i, part -> Log.i(TAG, "body[" + i + "] " + part) }
            Log.i(TAG, "== end " + label + " ==")
        }
    }

    /** One user turn: a text block and an image_url block, the shape OpenAiCompatibleProvider sends. */
    private fun visionBody(model: String): String {
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(testPicture(), Base64.NO_WRAP)
        return buildJsonObject {
            put("model", model)
            put("max_tokens", 200)
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "What shape, what colour, and what number are in this picture? Answer in one line.")
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", JsonPrimitive(dataUrl)) })
                        })
                    })
                })
            })
        }.toString()
    }

    private fun testPicture(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawCircle(256f, 256f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        canvas.drawText("47", 256f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 140f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        })
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "MaskanProviderProbe"

        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
