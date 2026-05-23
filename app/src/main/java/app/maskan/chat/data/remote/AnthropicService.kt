package app.maskan.chat.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AnthropicService {

    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: AnthropicRequest
    ): AnthropicResponse
}
