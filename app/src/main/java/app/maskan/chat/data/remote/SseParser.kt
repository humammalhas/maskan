package app.maskan.chat.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody
import retrofit2.Call
import java.io.BufferedReader

fun parseSSEStream(call: Call<ResponseBody>, extractToken: (String) -> String?): Flow<String> = flow {
    val response = call.execute()
    if (!response.isSuccessful) {
        val errorBody = response.errorBody()?.string() ?: "Unknown error"
        throw Exception("API error (${response.code()}): $errorBody")
    }
    val body = response.body() ?: throw Exception("Empty response body")
    body.use { responseBody ->
        val reader: BufferedReader = responseBody.byteStream().bufferedReader()
        reader.useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                val token = try {
                    extractToken(data)
                } catch (_: Exception) {
                    continue
                } ?: continue
                if (token.isNotEmpty()) emit(token)
            }
        }
    }
}.flowOn(Dispatchers.IO)
