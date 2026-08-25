package app.maskan.chat.util

import android.content.Context
import app.maskan.chat.R
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
            is HttpException -> mapHttpError(context, throwable)
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
     * Providers put the actual reason in the error body ("model X not found", "max_tokens too
     * large", "credit balance too low"). Showing that beats "an unknown error occurred", which
     * tells the user nothing and sends them guessing at their key. Error bodies never contain
     * the API key, so this is safe to surface.
     */
    private fun providerMessage(e: HttpException): String? = try {
        val body = e.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }
        val obj = body?.let { Json.parseToJsonElement(it) as? JsonObject }
        val message = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
        message?.trim()?.takeIf { it.isNotBlank() }?.take(200)
    } catch (t: Throwable) {
        null
    }

    private fun mapHttpError(context: Context, e: HttpException): String {
        val detail = providerMessage(e)
        return when (e.code()) {
            401 -> context.getString(R.string.error_auth_invalid)
            // 403 is NOT a bad key: providers use it for "your account/plan can't use this
            // model" (Together returns it for models that need a dedicated endpoint). Telling
            // the user their working key was rejected sends them chasing the wrong thing.
            403 -> context.getString(R.string.error_model_access_denied)
            404 -> detail ?: context.getString(R.string.error_model_not_found)
            413 -> context.getString(R.string.error_request_too_large)
            429 -> context.getString(R.string.error_rate_limit)
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
            msg.contains("api error") -> {
                val detail = throwable.message
                    ?.substringAfter("API error: ")
                    ?.takeIf { it.isNotBlank() }
                detail ?: context.getString(R.string.error_unknown)
            }
            else -> context.getString(R.string.error_unknown)
        }
    }
}
