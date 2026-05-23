package app.maskan.chat.util

import android.content.Context
import app.maskan.chat.R
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object ErrorMapper {

    fun mapToUserMessage(context: Context, throwable: Throwable): String {
        return when (throwable) {
            is HttpException -> mapHttpError(context, throwable)
            is UnknownHostException -> context.getString(R.string.error_dns_failed)
            is ConnectException -> context.getString(R.string.error_connection_refused)
            is SocketTimeoutException -> context.getString(R.string.error_timeout)
            is SSLException -> context.getString(R.string.error_ssl)
            is java.io.IOException -> context.getString(R.string.error_no_internet)
            else -> mapByMessage(context, throwable)
        }
    }

    private fun mapHttpError(context: Context, e: HttpException): String {
        return when (e.code()) {
            401 -> context.getString(R.string.error_auth_invalid)
            403 -> context.getString(R.string.error_auth_invalid)
            404 -> context.getString(R.string.error_model_not_found)
            413 -> context.getString(R.string.error_request_too_large)
            429 -> context.getString(R.string.error_rate_limit)
            402 -> context.getString(R.string.error_insufficient_quota)
            in 500..599 -> context.getString(R.string.error_server_error)
            else -> context.getString(R.string.error_unknown)
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
