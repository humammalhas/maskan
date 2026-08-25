package app.maskan.chat.data.remote

import android.util.Base64
import app.maskan.chat.data.remote.providers.GeneratedImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finds the generated picture anywhere in a Gemini generateContent response.
 *
 * Gemini has moved the image between response shapes across API revisions - the long-standing
 * candidates[].content.parts[].inlineData.{mimeType,data}, and newer step/content forms carrying
 * {"type":"image","data":...}. Rather than pin the app to whichever is current (and break the
 * day it changes, the way the hardcoded model lists broke before v2.4.5), walk the tree and take
 * the first base64 image blob found, whatever it is nested inside.
 */
object GeminiImageExtractor {

    fun extract(element: JsonElement): GeneratedImage? = walk(element)

    private fun walk(element: JsonElement): GeneratedImage? {
        when (element) {
            is JsonArray -> {
                element.forEach { child -> walk(child)?.let { return it } }
                return null
            }
            is JsonObject -> {
                imageFrom(element)?.let { return it }
                element.values.forEach { child -> walk(child)?.let { return it } }
                return null
            }
            else -> return null
        }
    }

    /** An object IS the image if it carries base64 data that is declared, or named, as an image. */
    private fun imageFrom(obj: JsonObject): GeneratedImage? {
        val data = (obj["data"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["bytesBase64Encoded"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        if (data.length < 64) return null

        val mime = ((obj["mimeType"] ?: obj["mime_type"]) as? JsonPrimitive)?.contentOrNull
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull

        val looksLikeImage = mime?.startsWith("image/") == true || type == "image"
        if (!looksLikeImage) return null

        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            if (bytes.isEmpty()) null else GeneratedImage(bytes, mime ?: "image/png")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gemini reports refusals and safety blocks in the body of a 200 response, so a missing image
     * is not necessarily a transport failure. Pull the reason out so the user sees it.
     */
    fun failureReason(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null
        ((obj["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?.let { return it }

        val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val finish = (candidate?.get("finishReason") as? JsonPrimitive)?.contentOrNull
        if (finish != null && finish != "STOP") return finish

        val blocked = ((obj["promptFeedback"] as? JsonObject)
            ?.get("blockReason") as? JsonPrimitive)?.contentOrNull
        return blocked
    }
}