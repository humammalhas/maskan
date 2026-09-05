package app.maskan.chat.data.remote

import android.util.Log
import app.maskan.chat.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The asynchronous video job API - the ONE video client in the app.
 *
 * A video render is minutes, not seconds (a 5-second local clip is ~4 minutes, a 15-second one
 * ~29), and a blocking HTTP request cannot survive that on a phone: the screen locks, Doze
 * cuts the network, the OS kills the socket. So video is never awaited. The server hands back
 * a job id at once and the app asks after it later - from a WorkManager job that outlives the
 * screen, the activity and the process (see VideoRenderWorker).
 *
 * The shape is deliberately OpenAI's Sora shape, and the local ComfyUI proxy serves the same
 * four routes, so this class points at either without knowing which:
 *
 *     POST   {root}/v1/videos              -> {"id": "...", "status": "queued"}
 *     GET    {root}/v1/videos/{id}         -> {status, phase, progress, error, prompt_expanded, ...}
 *     GET    {root}/v1/videos/{id}/content -> the MP4 bytes
 *     DELETE {root}/v1/videos/{id}         -> cancel
 *
 * [root] strips a trailing "/v1" because the service paths carry it: a user who enters
 * "http://host:8189/v1" as the Custom URL must not end up posting to /v1/v1/videos.
 */
class VideoJobClient(baseClient: OkHttpClient, private val json: Json) : VideoBackend {

    /** The server no longer knows this job - it cannot be resumed, only re-requested. */
    class JobGone(id: String) : IOException("video job $id is unknown to the server")

    /**
     * A non-2xx answer with the server's own words. An ApiHttpException, NOT an IOException:
     * ErrorMapper reads any IOException as "no internet", which is exactly wrong for a 404
     * from a server that clearly answered. The worker keeps polling on 5xx and gives up on 4xx.
     */
    class ServerError(code: Int, message: String) : ApiHttpException(code, message) {
        val isTransient: Boolean get() = code >= 500 || code == 429
    }

    /** What GET /health said about video, or null when the server has no such endpoint. */
    data class Capabilities(
        val videoModels: List<String>,
        val enhanceDefault: Boolean,
        val maxSeconds: Double?
    )

    data class JobStatus(
        val id: String,
        /** queued | running | completed | failed | cancelled */
        val status: String,
        /** Local proxy only: queued | expanding | rendering | done. Null on other servers. */
        val phase: String?,
        val progress: Int,
        val promptExpanded: String?,
        val error: String?,
        val etaSeconds: Double?,
        val retryable: Boolean,
        val sizeBytes: Long?
    ) {
        val isTerminal: Boolean get() = status == "completed" || status == "failed" || status == "cancelled"
    }

    // Under GPU load the Tailscale path to a home server stalls: one status GET was measured at
    // 35 s. A read timeout shorter than that would turn every render into a stream of false
    // failures, so status polls wait a full minute and a timeout is treated as "ask again".
    private val client = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // The capability probe runs on the send path, so it must be quick to give up: a server
    // without /health should cost the user a second, not a minute.
    private val probeClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun root(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

    private fun Request.Builder.auth(apiKey: String): Request.Builder =
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") else this

    /**
     * Ask the server what it can do. Returns null - never throws - when there is no /health or
     * it does not describe video models; the caller then treats the model as an image model.
     * This is how the app tells a video model id from an image one without a hardcoded list.
     */
    fun probe(baseUrl: String, apiKey: String): Capabilities? = try {
        val request = Request.Builder().url("${root(baseUrl)}/health").get().auth(apiKey).build()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                val body = json.parseToJsonElement(response.body?.string().orEmpty())
                val obj = body as? JsonObject
                val models = (obj?.get("video_models") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (models == null) {
                    null
                } else {
                    Capabilities(
                        videoModels = models,
                        enhanceDefault = (obj["enhance_default"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        maxSeconds = (obj["max_seconds"] as? JsonPrimitive)?.doubleOrNull
                    )
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Create the job. Returns its id at once; nothing has been rendered yet.
     * [imageDataUri] is the conditioning photo for photo-to-video, as a data: URI.
     */
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
            // Two spellings of the same two facts: the local proxy reads seconds/size (and
            // tolerates the rest), OpenRouter reads duration and aspect_ratio or size. A size
            // written as "16:9" is an aspect ratio; "1024x576" is a size.
            put("seconds", seconds)
            put("duration", seconds)
            if (size.contains(':')) put("aspect_ratio", size) else put("size", size)
            put("enhance", enhance)
            if (imageDataUri != null) {
                // The proxy reads image; OpenRouter reads frame_images[0] as the first frame.
                put("image", imageDataUri)
                // Shape from OpenRouter's own validation error (device-tested 2026-09-05):
                // {type:"image_url", image_url:{url}, frame_type}.
                put("frame_images", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", imageDataUri) })
                        put("frame_type", "first_frame")
                    })
                })
            }
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/videos")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w("Maskan", "video submit ${response.code}: ${text.take(600)}")
                throw ServerError(response.code, errorMessage(text, response.code))
            }
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw ServerError(response.code, "unexpected reply from the video server")
            return (obj["id"] as? JsonPrimitive)?.contentOrNull
                ?: throw ServerError(response.code, "the video server returned no job id")
        }
    }

    /** One poll. Throws IOException on the wire, [JobGone] on 404, [ServerError] otherwise. */
    override fun status(baseUrl: String, apiKey: String, jobId: String): JobStatus {
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/videos/$jobId")
            .get()
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) throw JobGone(jobId)
            if (!response.isSuccessful) throw ServerError(response.code, errorMessage(text, response.code))
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw ServerError(response.code, "unexpected reply from the video server")
            return parseStatus(obj, jobId)
        }
    }

    /** The finished MP4. 409 means "not finished yet" and surfaces as [ServerError]. */
    override fun download(baseUrl: String, apiKey: String, jobId: String): ByteArray {
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/videos/$jobId/content")
            .get()
            .auth(apiKey)
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (response.code == 404) throw JobGone(jobId)
            if (!response.isSuccessful) {
                throw ServerError(response.code, errorMessage(response.body?.string().orEmpty(), response.code))
            }
            return response.body?.bytes() ?: throw ServerError(response.code, "empty video body")
        }
    }

    /** Best effort: a cancel that fails on the wire is not worth failing anything else over. */
    override fun cancel(baseUrl: String, apiKey: String, jobId: String) {
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1/videos/$jobId")
            .delete()
            .auth(apiKey)
            .build()
        client.newCall(request).execute().close()
    }

    private fun parseStatus(obj: JsonObject, fallbackId: String): JobStatus {
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val errorField = obj["error"]
        val error = when (errorField) {
            is JsonObject -> (errorField["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> errorField.contentOrNull
            else -> null
        }
        return JobStatus(
            id = str("id") ?: fallbackId,
            status = str("status") ?: "running",
            phase = str("phase"),
            progress = (obj["progress"] as? JsonPrimitive)?.intOrNull ?: 0,
            promptExpanded = str("prompt_expanded")?.takeIf { it.isNotBlank() },
            error = error?.takeIf { it.isNotBlank() },
            etaSeconds = (obj["eta_seconds"] as? JsonPrimitive)?.doubleOrNull,
            retryable = (obj["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            sizeBytes = (obj["size_bytes"] as? JsonPrimitive)?.longOrNull
        )
    }

    /** The server's own words out of {"error":{"message":..}} or {"error":".."}, else the code. */
    private fun errorMessage(body: String, code: Int): String {
        val parsed = try { json.parseToJsonElement(body) } catch (_: Exception) { null }
        val error = (parsed as? JsonObject)?.get("error")
        val message = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        return message?.takeIf { it.isNotBlank() } ?: "HTTP $code from the video server"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
