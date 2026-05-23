package app.maskan.chat.data.remote

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface OpenAiCompatibleService {

    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    @Streaming
    @POST("v1/chat/completions")
    fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Call<ResponseBody>
}
