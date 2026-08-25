package app.maskan.chat.data.remote

import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming

interface OpenAiCompatibleService {

    // Returns raw JSON on purpose: "OpenAI-compatible" is not one response shape. Most providers
    // wrap the list in {"data": [...]}, Together returns a bare array, some local servers use
    // {"models": [...]}. A fixed DTO throws on the odd ones out.
    //
    // dedicated: Together-only filter. Its catalogue lists hundreds of models that are only
    // callable from a dedicated endpoint; passing false narrows it to what an ordinary key can
    // actually call. Omitted (null) for every other provider.
    @GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String,
        @Query("dedicated") dedicated: Boolean? = null
    ): JsonElement

    // Ollama-only. Its OpenAI-compatible /v1/models says nothing about capabilities, but the
    // native tag list reports each model's families - a "clip" or "mllama" family is how a
    // vision model shows up. Any other server answers 404 here and the caller ignores it.
    @GET("api/tags")
    suspend fun listOllamaTags(): JsonElement

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