package app.maskan.chat.data.remote

import android.util.Log
import app.maskan.chat.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Venice's video API - the third shape behind the one video spine.
 *
 *     POST {root}/v1/video/queue     {model, prompt, duration "5s", aspect_ratio, image_url?}
 *                                    -> {queue_id, model}
 *     POST {root}/v1/video/retrieve  {model, queue_id}
 *                                    -> JSON {"status":"PROCESSING", execution_duration,
 *                                             average_execution_time}   ...or the MP4 itself
 *     POST {root}/v1/video/quote     {model, duration, aspect_ratio, resolution} -> {"quote": USD}
 *     POST {root}/v1/video/complete  {model, queue_id}  (frees Venice's copy; best effort)
 *
 * Retrieve needs the model as well as the queue id, so the job id the app stores is
 * "model|queue_id". Venice reports how long the job has run and how long this model usually
 * takes, which gives an honest percentage and ETA - the only cloud provider here that does.
 * Venice also QUOTES a price before generating; VideoQuoter exposes that so the composer can
 * show Venice's own number instead of an estimate. Per the Venice API docs read 2026-09-05;
 * image-to-video (image_url as a data URL) is UNTESTED at the time of writing.
 */
class VeniceVideoClient(baseClient: OkHttpClient, private val json: Json) : VideoBackend, VideoQuoter {

    private val client = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun root(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

    private fun Request.Builder.auth(apiKey: String): Request.Builder =
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") else this

    private fun split(jobId: String): Pair<String, String> {
        val bar = jobId.indexOf('|')
        if (bar <= 0) throw VideoJobClient.JobGone(jobId)
        return jobId.substring(0, bar) to jobId.substring(bar + 1)
    }

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
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("duration", "${seconds}s")
            put("resolution", "720p")
            // Image-to-video takes its shape from the photo; Venice rejects aspect_ratio there
            // ("This model does not support aspect_ratio", device-tested 2026-09-05).
            if (imageDataUri != null) put("image_url", imageDataUri) else put("aspect_ratio", size)
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/video/queue")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w("Maskan", "venice video queue ${response.code}: ${text.take(600)}")
                throw VideoJobClient.ServerError(response.code, errorMessage(text, response.code))
            }
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw VideoJobClient.ServerError(response.code, "unexpected reply from Venice")
            val queueId = (obj["queue_id"] as? JsonPrimitive)?.contentOrNull
                ?: throw VideoJobClient.ServerError(response.code, "Venice returned no queue id")
            return "$model|$queueId"
        }
    }

    override fun status(baseUrl: String, apiKey: String, jobId: String): VideoJobClient.JobStatus {
        val (model, queueId) = split(jobId)
        retrieve(baseUrl, apiKey, model, queueId, wantBytes = false).use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (type.startsWith("video/")) {
                return VideoJobClient.JobStatus(
                    id = jobId, status = "completed", phase = "done", progress = 100,
                    promptExpanded = null, error = null, etaSeconds = null, retryable = false,
                    sizeBytes = response.body?.contentLength()?.takeIf { it > 0 }
                )
            }
            val text = response.body?.string().orEmpty()
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw VideoJobClient.ServerError(response.code, "unexpected reply from Venice")
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "PROCESSING"
            val ran = (obj["execution_duration"] as? JsonPrimitive)?.longOrNull ?: 0L
            val usual = (obj["average_execution_time"] as? JsonPrimitive)?.longOrNull ?: 0L
            val progress = if (usual > 0) (100L * ran / usual).toInt().coerceIn(0, 95) else 0
            val eta = if (usual > 0) ((usual - ran) / 1000.0).coerceAtLeast(5.0) else null
            return when (status) {
                "COMPLETED" -> VideoJobClient.JobStatus(jobId, "completed", "done", 100, null, null, null, false, null)
                "FAILED", "ERROR" -> VideoJobClient.JobStatus(
                    jobId, "failed", null, 0, null,
                    (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "Venice reported a failure",
                    null, false, null
                )
                else -> VideoJobClient.JobStatus(jobId, "running", "rendering", progress, null, null, eta, false, null)
            }
        }
    }

    override fun download(baseUrl: String, apiKey: String, jobId: String): ByteArray {
        val (model, queueId) = split(jobId)
        retrieve(baseUrl, apiKey, model, queueId, wantBytes = true).use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (!type.startsWith("video/")) throw VideoJobClient.ServerError(409, "job is not finished")
            val bytes = response.body?.bytes() ?: throw VideoJobClient.ServerError(response.code, "empty video body")
            // Venice keeps its copy until told otherwise; the clip is ours now.
            runCatching { complete(baseUrl, apiKey, model, queueId) }
            return bytes
        }
    }

    /** Venice has no cancel; the app stops asking and the queued job expires on its own. */
    override fun cancel(baseUrl: String, apiKey: String, jobId: String) {}

    override fun quote(baseUrl: String, apiKey: String, model: String, seconds: Int, size: String): Double? {
        val body = buildJsonObject {
            put("model", model)
            put("duration", "${seconds}s")
            put("aspect_ratio", size)
            put("resolution", "720p")
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/video/quote")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val obj = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                (obj?.get("quote") as? JsonPrimitive)?.doubleOrNull
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun retrieve(baseUrl: String, apiKey: String, model: String, queueId: String, wantBytes: Boolean): okhttp3.Response {
        val body = buildJsonObject {
            put("model", model)
            put("queue_id", queueId)
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/video/retrieve")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        val response = (if (wantBytes) downloadClient else client).newCall(request).execute()
        if (response.code == 404 || response.code == 410) {
            response.close()
            throw VideoJobClient.JobGone(queueId)
        }
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty()
            response.close()
            throw VideoJobClient.ServerError(response.code, errorMessage(text, response.code))
        }
        return response
    }

    private fun complete(baseUrl: String, apiKey: String, model: String, queueId: String) {
        val body = buildJsonObject {
            put("model", model)
            put("queue_id", queueId)
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/video/complete")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        client.newCall(request).execute().close()
    }

    private fun errorMessage(body: String, code: Int): String {
        val parsed = try { json.parseToJsonElement(body) } catch (_: Exception) { null }
        val obj = parsed as? JsonObject
        val error = obj?.get("error")
        val message = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        }
        return message?.takeIf { it.isNotBlank() } ?: "HTTP $code from Venice"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
