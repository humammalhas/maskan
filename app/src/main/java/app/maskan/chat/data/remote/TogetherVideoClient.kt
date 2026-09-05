package app.maskan.chat.data.remote

import android.util.Log
import app.maskan.chat.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Together AI's video API - the fourth shape behind the one video spine.
 *
 *     POST {root}/v1/videos       {model, prompt, width, height, seconds:"6",
 *                                  media:{frame_images:[{input_image, frame:"first"}]}}
 *                                 -> {id, status}
 *     GET  {root}/v1/videos/{id}  -> {status: in_progress|completed|failed, outputs:{video_url}, error}
 *     GET  <video_url>            -> the MP4
 *
 * Sizes arrive as "16:9" / "9:16" (VideoOptions.sizesFor) and become 1280x720 / 720x1280.
 * No progress figure from Together; the bubble shows elapsed time. Per Together's parameter
 * reference read 2026-09-05; image-to-video is UNTESTED at the time of writing.
 */
class TogetherVideoClient(baseClient: OkHttpClient, private val json: Json) : VideoBackend {

    private val client = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun root(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

    private fun Request.Builder.auth(apiKey: String): Request.Builder =
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") else this

    override fun submit(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        seconds: Int,
        size: String,
        enhance: Boolean,
        imageDataUri: String?
    ): String {
        // Together's video API is /v2 with resolution + ratio (its Wan 2.7 quickstart, verified
        // live 2026-09-05: /v1/videos answers an HTML 404, /v2/videos answers the API).
        val ratio = if (size.contains('x')) {
            if (size.substringBefore('x').toInt() >= size.substringAfter('x').toInt()) "16:9" else "9:16"
        } else size
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("resolution", "720P")
            put("ratio", ratio)
            put("seconds", seconds.toString())
            if (imageDataUri != null) {
                put("media", buildJsonObject {
                    put("frame_images", buildJsonArray {
                        add(buildJsonObject {
                            put("input_image", imageDataUri)
                            put("frame", "first")
                        })
                    })
                })
            }
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v2/videos")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w("Maskan", "together video submit ${response.code}: ${text.take(600)}")
                throw VideoJobClient.ServerError(response.code, errorMessage(text, response.code))
            }
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw VideoJobClient.ServerError(response.code, "unexpected reply from Together")
            return (obj["id"] as? JsonPrimitive)?.contentOrNull
                ?: throw VideoJobClient.ServerError(response.code, "Together returned no job id")
        }
    }

    override fun status(baseUrl: String, apiKey: String, jobId: String): VideoJobClient.JobStatus {
        val obj = job(baseUrl, apiKey, jobId)
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "in_progress"
        val error = (obj["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val url = videoUrl(obj)
        return when {
            status == "failed" || error != null -> VideoJobClient.JobStatus(
                jobId, "failed", null, 0, null, error ?: "Together reported a failure", null, false, null
            )
            status == "completed" && url != null ->
                VideoJobClient.JobStatus(jobId, "completed", "done", 100, null, null, null, false, null)
            else -> VideoJobClient.JobStatus(jobId, "running", "rendering", 0, null, null, null, false, null)
        }
    }

    override fun download(baseUrl: String, apiKey: String, jobId: String): ByteArray {
        val url = videoUrl(job(baseUrl, apiKey, jobId))
            ?: throw VideoJobClient.ServerError(409, "job is not finished")
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw VideoJobClient.ServerError(response.code, "HTTP ${response.code} fetching the video")
            return response.body?.bytes() ?: throw VideoJobClient.ServerError(response.code, "empty video body")
        }
    }

    /** Together documents no cancel; the app stops asking. */
    override fun cancel(baseUrl: String, apiKey: String, jobId: String) {}

    private fun job(baseUrl: String, apiKey: String, jobId: String): JsonObject {
        val request = Request.Builder()
            .url("${root(baseUrl)}/v2/videos/$jobId")
            .get()
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) throw VideoJobClient.JobGone(jobId)
            if (!response.isSuccessful) throw VideoJobClient.ServerError(response.code, errorMessage(text, response.code))
            return json.parseToJsonElement(text) as? JsonObject
                ?: throw VideoJobClient.ServerError(response.code, "unexpected reply from Together")
        }
    }

    private fun videoUrl(obj: JsonObject): String? {
        val outputs = obj["outputs"]
        val single = (outputs as? JsonObject)?.get("video_url")
        val fromArray = ((outputs as? JsonArray)?.firstOrNull() as? JsonObject)?.get("video_url")
        return ((single ?: fromArray) as? JsonPrimitive)?.contentOrNull
    }

    private fun errorMessage(body: String, code: Int): String {
        val parsed = try { json.parseToJsonElement(body) } catch (_: Exception) { null }
        val error = (parsed as? JsonObject)?.get("error")
        val message = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        return message?.takeIf { it.isNotBlank() } ?: "HTTP $code from Together"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
