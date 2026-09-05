package app.maskan.chat.data.remote

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
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Veo through the Gemini API - the second shape behind the one video spine.
 *
 *     POST {root}/v1beta/models/{model}:predictLongRunning  -> {"name": "<operation>"}
 *     GET  {root}/v1beta/{operation}                        -> {done, error, response{...uri}}
 *     GET  <uri>  (x-goog-api-key header, follows a redirect) -> the MP4 bytes
 *
 * The operation name is what the app stores as the job id. There is no progress figure and
 * no ETA from Google, so status reports "rendering" with 0 % and the bubble shows an
 * indeterminate bar with elapsed time; Google keeps a finished clip for two days. Clips carry
 * native audio. Nothing here is free: every accepted request is billed per second of video.
 *
 * Request shapes per the Gemini API docs read 2026-09-05; image-to-video (inlineData) is
 * UNTESTED against a real key at the time of writing.
 */
class VeoVideoClient(baseClient: OkHttpClient, private val json: Json) : VideoBackend {

    private val client = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun root(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun Request.Builder.auth(apiKey: String): Request.Builder =
        header("x-goog-api-key", apiKey)

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
            put("instances", buildJsonArray {
                add(buildJsonObject {
                    put("prompt", prompt)
                    if (imageDataUri != null) {
                        val comma = imageDataUri.indexOf(',')
                        val header = imageDataUri.substring(0, maxOf(comma, 0))
                        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "image/jpeg" }
                        // Veo's own spelling - it answers 400 "inlineData isn't supported by
                        // this model" to the generateContent shape (device-tested 2026-09-05).
                        put("image", buildJsonObject {
                            put("bytesBase64Encoded", imageDataUri.substring(comma + 1))
                            put("mimeType", mime)
                        })
                    }
                })
            })
            put("parameters", buildJsonObject {
                // size arrives as "16:9" / "9:16" for this provider (VideoOptions.sizesFor).
                put("aspectRatio", size)
                put("durationSeconds", seconds)
                put("resolution", "720p")
            })
        }
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1beta/models/$model:predictLongRunning")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw VideoJobClient.ServerError(response.code, errorMessage(text, response.code))
            val obj = json.parseToJsonElement(text) as? JsonObject
                ?: throw VideoJobClient.ServerError(response.code, "unexpected reply from Veo")
            return (obj["name"] as? JsonPrimitive)?.contentOrNull
                ?: throw VideoJobClient.ServerError(response.code, "Veo returned no operation name")
        }
    }

    override fun status(baseUrl: String, apiKey: String, jobId: String): VideoJobClient.JobStatus {
        val obj = operation(baseUrl, apiKey, jobId)
        val done = (obj["done"] as? JsonPrimitive)?.booleanOrNull ?: false
        val error = (obj["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val uri = videoUri(obj)
        val filtered = ((obj["response"] as? JsonObject)?.get("generateVideoResponse") as? JsonObject)
            ?.get("raiMediaFilteredReasons")?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString(" ")
        val status = when {
            error != null -> "failed"
            done && uri != null -> "completed"
            done -> "failed"
            else -> "running"
        }
        return VideoJobClient.JobStatus(
            id = jobId,
            status = status,
            phase = if (status == "running") "rendering" else if (status == "completed") "done" else null,
            progress = 0,
            promptExpanded = null,
            error = error ?: if (done && uri == null) (filtered?.takeIf { it.isNotBlank() } ?: "Veo returned no video") else null,
            etaSeconds = null,
            retryable = false,
            sizeBytes = null
        )
    }

    override fun download(baseUrl: String, apiKey: String, jobId: String): ByteArray {
        val uri = videoUri(operation(baseUrl, apiKey, jobId))
            ?: throw VideoJobClient.ServerError(409, "job is not finished")
        val request = Request.Builder().url(uri).get().auth(apiKey).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw VideoJobClient.ServerError(response.code, errorMessage(response.body?.string().orEmpty(), response.code))
            }
            return response.body?.bytes() ?: throw VideoJobClient.ServerError(response.code, "empty video body")
        }
    }

    /** Google offers no cancel for a Veo operation; the app simply stops asking. */
    override fun cancel(baseUrl: String, apiKey: String, jobId: String) {}

    private fun operation(baseUrl: String, apiKey: String, name: String): JsonObject {
        val request = Request.Builder()
            .url("${root(baseUrl)}/v1beta/${name.trimStart('/')}")
            .get()
            .auth(apiKey)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) throw VideoJobClient.JobGone(name)
            if (!response.isSuccessful) throw VideoJobClient.ServerError(response.code, errorMessage(text, response.code))
            return json.parseToJsonElement(text) as? JsonObject
                ?: throw VideoJobClient.ServerError(response.code, "unexpected reply from Veo")
        }
    }

    private fun videoUri(op: JsonObject): String? {
        val samples = ((op["response"] as? JsonObject)?.get("generateVideoResponse") as? JsonObject)
            ?.get("generatedSamples") as? JsonArray
        val first = samples?.firstOrNull() as? JsonObject
        return ((first?.get("video") as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
    }

    private fun errorMessage(body: String, code: Int): String {
        val parsed = try { json.parseToJsonElement(body) } catch (_: Exception) { null }
        val error = (parsed as? JsonObject)?.get("error")
        val message = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        return message?.takeIf { it.isNotBlank() } ?: "HTTP $code from Veo"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
