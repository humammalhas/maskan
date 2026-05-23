package app.maskan.chat.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit service interface for the DeepSeek API.
 * The app communicates exclusively with api.deepseek.com over HTTPS.
 *
 * Base URL: https://api.deepseek.com
 * Endpoint: v1/chat/completions
 * Authentication: Bearer token via Authorization header (set via OkHttp interceptor).
 */
interface DeepSeekApiService {

    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

