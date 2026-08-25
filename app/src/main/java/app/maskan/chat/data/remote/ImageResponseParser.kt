package app.maskan.chat.data.remote

import android.util.Base64
import app.maskan.chat.data.remote.providers.GeneratedImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pulls the actual image bytes out of an /v1/images/generations response.
 *
 * Deliberately tolerant, for the same reason the model list is: providers disagree on where the
 * bytes live. OpenAI uses {"data":[{"b64_json": ...}]}, Together names the same field "base64"
 * (and its request parameter too), some return {"data":["<base64>"]}, and several answer with a
 * URL instead. A strict DTO would throw on all but one of them.
 *
 * Bytes are strongly preferred over a URL: the image is already in the response, so there is no
 * second request to a CDN and nothing about the picture leaves the provider we already talked to.
 */
object ImageResponseParser {

    private val BASE64_FIELDS = listOf("b64_json", "base64", "image_base64", "b64", "image")

    suspend fun parse(
        element: JsonElement,
        download: suspend (String) -> ByteArray
    ): GeneratedImage {
        val first = firstEntry(element)
            ?: throw Exception("Provider returned no image")

        if (first is JsonPrimitive) {
            val raw = first.contentOrNull ?: throw Exception("Provider returned no image")
            return if (raw.startsWith("http")) {
                GeneratedImage(download(raw), "image/png")
            } else {
                GeneratedImage(decode(raw), "image/png")
            }
        }

        val obj = first as? JsonObject ?: throw Exception("Provider returned no image")

        BASE64_FIELDS.firstNotNullOfOrNull { field ->
            (obj[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }?.let { return GeneratedImage(decode(it), "image/png") }

        val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ((obj["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: throw Exception("Provider returned no image")

        // A data: URL is bytes wearing a URL costume - decode it rather than fetching it.
        val dataPrefix = url.substringBefore(",", "")
        return if (dataPrefix.startsWith("data:") && dataPrefix.contains("base64")) {
            GeneratedImage(decode(url.substringAfter(",")), mimeOf(dataPrefix))
        } else {
            GeneratedImage(download(url), "image/png")
        }
    }

    private fun firstEntry(element: JsonElement): JsonElement? {
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["data"] ?: element["images"] ?: element["output"]) as? JsonArray
            else -> null
        }
        return array?.firstOrNull()
    }

    private fun decode(value: String): ByteArray {
        val payload = if (value.startsWith("data:")) value.substringAfter(",") else value
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        if (bytes.isEmpty()) throw Exception("Provider returned no image")
        return bytes
    }

    private fun mimeOf(dataPrefix: String): String =
        dataPrefix.removePrefix("data:").substringBefore(";").ifBlank { "image/png" }
}