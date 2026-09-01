package app.maskan.chat.util

import android.content.Context
import app.maskan.chat.R
import app.maskan.chat.data.remote.ApiHttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import javax.net.ssl.SSLException

object ErrorMapper {

    fun mapToUserMessage(context: Context, throwable: Throwable): String {
        return when (throwable) {
            is HttpException -> mapStatus(context, throwable.code(), providerMessage(throwable))
            is ApiHttpException -> mapStatus(context, throwable.code, throwable.providerMessage)
            is UnknownHostException -> context.getString(R.string.error_dns_failed)
            is ConnectException -> context.getString(R.string.error_connection_refused)
            is SocketTimeoutException -> context.getString(R.string.error_timeout)
            is SerializationException -> context.getString(R.string.error_serialization)
            is SSLException -> context.getString(R.string.error_ssl)
            is java.io.IOException -> context.getString(R.string.error_no_internet)
            else -> mapByMessage(context, throwable)
        }
    }

    /**
     * What, if anything, moving this conversation onto a different model would achieve.
     *
     * DEAD  - the model itself is gone or barred (403/404, or a 400 naming it). Worth both
     *         offering the switch AND recording the model as unavailable so the picker drops it.
     * SWITCHABLE - the model still exists but this request cannot use it right now: 402 (no
     *         credit for a paid model) or 429 (that model is rate limited). Offer the switch,
     *         because a chat pinned to an old model while the user has since selected a working
     *         one is fixed by exactly this - but NEVER blacklist: an empty balance or a rate
     *         limit is temporary, and hiding a good model would need a manual refresh to undo.
     * NONE  - nothing about the model would help (bad key, network, server error).
     *
     * Read once: pulling an HttpException's error body is destructive-ish, so callers get both
     * facts from a single call rather than asking two questions.
     */
    fun classifyModelFailure(throwable: Throwable, modelId: String? = null): ModelRecovery {
        val (code, detail) = when (throwable) {
            is HttpException -> throwable.code() to providerMessage(throwable)
            is ApiHttpException -> throwable.code to throwable.providerMessage
            else -> return if (looksLikeMissingModel(throwable.message, modelId)) {
                ModelRecovery.DEAD
            } else {
                ModelRecovery.NONE
            }
        }
        return when (code) {
            403, 404 -> ModelRecovery.DEAD
            // 400 is the ambiguous one: it covers both "that model is gone" and a genuinely
            // malformed request (the Anthropic system-block bug was a 400). Providers phrase
            // the model complaint every possible way - "does not exist", "decommissioned",
            // OpenRouter's "is not a valid model ID" - so rather than chase wordings, treat a
            // 400 as model-related when the provider's message NAMES the model we sent. A 400
            // about max_tokens or system blocks never quotes the model id.
            400 -> if (looksLikeMissingModel(detail, modelId)) {
                ModelRecovery.DEAD
            } else {
                ModelRecovery.NONE
            }
            402, 429 -> ModelRecovery.SWITCHABLE
            else -> ModelRecovery.NONE
        }
    }

    enum class ModelRecovery { NONE, SWITCHABLE, DEAD }

    private fun looksLikeMissingModel(message: String?, modelId: String?): Boolean {
        val msg = message?.lowercase() ?: return false
        val named = modelId?.trim()?.lowercase()?.takeIf { it.length >= 3 }
        if (named != null && msg.contains(named)) return true
        return MODEL_GONE_MARKERS.any { msg.contains(it) }
    }

    /**
     * Providers put the actual reason in the error body ("model X not found", "max_tokens too
     * large", "credit balance too low"). Showing that beats "an unknown error occurred", which
     * tells the user nothing and sends them guessing at their key. Error bodies never contain
     * the API key, so this is safe to surface.
     */
    private fun providerMessage(e: HttpException): String? = try {
        val body = e.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
        val obj = try {
            body?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (_: Throwable) {
            null
        }
        val message = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
            // Not every failure is JSON. A gateway or WAF in front of a provider answers with
            // plain text or HTML, and dropping that on the floor leaves the user staring at a
            // generic sentence with no idea what to change. Strip any tags and show it.
            ?: body?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")
        message?.trim()?.takeIf { it.isNotBlank() }?.take(200)
    } catch (t: Throwable) {
        null
    }

    private fun mapStatus(context: Context, code: Int, detail: String?): String {
        return when (code) {
            401 -> context.getString(R.string.error_auth_invalid)
            // 403 is NOT a bad key: providers use it for "your account/plan can't use this
            // model" (Together returns it for models that need a dedicated endpoint). Telling
            // the user their working key was rejected sends them chasing the wrong thing.
            403 -> {
                // Keep the plain-English sentence (a 403 is NOT a bad key) but append the
                // provider's own words when it gives any: "requires a dedicated endpoint",
                // "accept the terms", "not enabled for your account" are all 403s that read
                // identically otherwise, and the user cannot act without knowing which.
                val base = context.getString(R.string.error_model_access_denied)
                val message = if (detail.isNullOrBlank()) base else "$base ($detail)"
                // Together's own wording for this 403 is "third-party data sharing", but the
                // switch that fixes it is labelled "Allow passthrough models" in its dashboard -
                // different words for the same thing, which is exactly why nobody finds it.
                // Say where the switch is instead of dead-ending.
                if (detail?.contains("third-party data sharing", ignoreCase = true) == true ||
                    detail?.contains("passthrough", ignoreCase = true) == true
                ) {
                    message + "\n" + context.getString(R.string.error_together_passthrough_hint)
                } else {
                    message
                }
            }
            404 -> detail ?: context.getString(R.string.error_model_not_found)
            413 -> context.getString(R.string.error_request_too_large)
            429 -> {
                // Append the provider's own words. A 429 can mean "slow down" OR "this model has
                // no quota on your plan at all" (Gemini answers 429 with a FreeTier limit of 0
                // for image models), and those need completely different actions from the user.
                val base = context.getString(R.string.error_rate_limit)
                if (detail.isNullOrBlank()) base else "$base ($detail)"
            }
            402 -> context.getString(R.string.error_insufficient_quota)
            in 500..599 -> context.getString(R.string.error_server_error)
            else -> detail ?: context.getString(R.string.error_unknown)
        }
    }

    private fun mapByMessage(context: Context, throwable: Throwable): String {
        val msg = throwable.message?.lowercase() ?: return context.getString(R.string.error_unknown)
        return when {
            msg.contains("api key not set") -> context.getString(R.string.error_api_key_missing)
            msg.contains("empty response") -> context.getString(R.string.error_empty_response_detail)
            msg.contains("conversation not found") -> context.getString(R.string.error_conversation_not_found)
            msg.contains("unknown provider") -> context.getString(R.string.error_provider_not_found)
            msg.contains("no image model selected") -> context.getString(R.string.error_no_image_model)
            msg.contains("image generation unsupported") -> context.getString(R.string.error_image_unsupported)
            msg.contains("api error") -> {
                val detail = throwable.message
                    ?.substringAfter("API error: ")
                    ?.takeIf { it.isNotBlank() }
                detail ?: context.getString(R.string.error_unknown)
            }
            else -> context.getString(R.string.error_unknown)
        }
    }

    private val MODEL_GONE_MARKERS = listOf(
        "model_not_found",
        "does not exist",
        "not found",
        "decommissioned",
        "deprecated",
        "no longer available",
        "unknown model",
        "invalid model",
        "not a valid model",
        "unsupported model",
        "no endpoints found",
        "no allowed providers"
    )
}