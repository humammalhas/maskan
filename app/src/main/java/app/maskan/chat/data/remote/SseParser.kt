package app.maskan.chat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.ResponseBody
import retrofit2.Call
import java.io.BufferedReader

/**
 * A failed API call that still carries its HTTP status code.
 *
 * The streaming path reads the response itself (Call.execute + SSE), so a non-2xx never
 * becomes a retrofit HttpException the way the blocking path's does. Throwing a plain
 * Exception here lost the status code, so ErrorMapper's 403/404 handling never ran for an
 * actual chat send and the user saw the raw JSON error body. Keep the code AND the
 * provider's own message: both are needed to tell "this model is gone" apart from
 * "your key is bad" or "you are out of credit".
 */
open class ApiHttpException(
    val code: Int,
    val providerMessage: String?,
    val rawBody: String? = null
) : Exception("API error ($code): ${providerMessage ?: rawBody ?: "Unknown error"}")

/**
 * One piece of a streamed reply: the answer, or the model's reasoning, which some providers send
 * in a field of its own (plan item 2). Thinking is shown while the chat is open and goes nowhere
 * else - not into the saved reply, not into the next request.
 */
sealed class StreamPiece {
    data class Answer(val text: String) : StreamPiece()
    data class Thinking(val text: String) : StreamPiece()
}

fun parseSSEStream(call: Call<ResponseBody>, extractToken: (String) -> String?): Flow<String> =
    parseSSEPieces(call) { data -> extractToken(data)?.let { listOf(StreamPiece.Answer(it)) } }
        .map { (it as StreamPiece.Answer).text }

/** [parseSSEStream] for a chunk that can carry reasoning beside the answer: thinking first, as it arrives. */
fun parseSSEPieces(call: Call<ResponseBody>, extract: (String) -> List<StreamPiece>?): Flow<StreamPiece> = flow {
    val response = call.execute()
    if (!response.isSuccessful) {
        val errorBody = response.errorBody()?.string()
        throw ApiHttpException(
            code = response.code(),
            providerMessage = extractProviderMessage(errorBody),
            rawBody = errorBody
        )
    }
    val body = response.body() ?: throw Exception("Empty response body")
    body.use { responseBody ->
        val reader: BufferedReader = responseBody.byteStream().bufferedReader()
        reader.useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                val pieces = try {
                    extract(data)
                } catch (_: Exception) {
                    continue
                } ?: continue
                for (piece in pieces) {
                    val text = when (piece) {
                        is StreamPiece.Answer -> piece.text
                        is StreamPiece.Thinking -> piece.text
                    }
                    if (text.isNotEmpty()) emit(piece)
                }
            }
        }
    }
}.flowOn(Dispatchers.IO)

/**
 * Pull the human-readable reason out of an error body. Providers disagree on the shape
 * ({"error":{"message":...}}, {"message":...}, {"detail":...}), so try each. Error bodies
 * never contain the API key, so this is safe to surface to the user.
 */
internal fun extractProviderMessage(body: String?): String? = try {
    val obj = body?.takeIf { it.isNotBlank() }
        ?.let { Json.parseToJsonElement(it) as? JsonObject }
    val error = obj?.get("error")
    val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        ?: (error as? JsonPrimitive)?.contentOrNull
        ?: (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        ?: (obj?.get("detail") as? JsonPrimitive)?.contentOrNull
    message?.trim()?.takeIf { it.isNotBlank() }?.take(200)
} catch (_: Throwable) {
    null
}