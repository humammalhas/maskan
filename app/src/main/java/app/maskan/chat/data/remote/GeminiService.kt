package app.maskan.chat.data.remote

import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface GeminiService {

    @GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String,
        @Query("pageSize") pageSize: Int = 200
    ): GeminiModelsResponse

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse

    // Image generation goes through the same generateContent endpoint but needs a request body
    // this app's GeminiRequest does not model (generationConfig.responseModalities), and returns
    // the picture in a part shape that has changed between API revisions. Raw JSON both ways, so
    // neither end is pinned to one revision - the same reason listModels returns raw JSON.
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContentRaw(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: JsonElement
    ): JsonElement

    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent")
    fun streamGenerateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Query("alt") alt: String = "sse",
        @Body request: GeminiRequest
    ): Call<ResponseBody>
}
