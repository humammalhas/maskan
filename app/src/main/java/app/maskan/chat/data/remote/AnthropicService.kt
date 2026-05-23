package app.maskan.chat.data.remote

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface AnthropicService {

    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: AnthropicRequest
    ): AnthropicResponse

    @Streaming
    @POST("v1/messages")
    fun createMessageStream(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: AnthropicRequest
    ): Call<ResponseBody>
}
