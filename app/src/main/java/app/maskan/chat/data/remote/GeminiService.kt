package app.maskan.chat.data.remote

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface GeminiService {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse

    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent")
    fun streamGenerateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Query("alt") alt: String = "sse",
        @Body request: GeminiRequest
    ): Call<ResponseBody>
}
