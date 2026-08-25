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
import retrofit2.http.Url

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
        @Query("dedicated") dedicated: Boolean? = null,
        // Venice-only: its default list is chat models ONLY - the 38 image models are
        // invisible unless asked for by type. Null for every other provider.
        @Query("type") type: String? = null
    ): JsonElement

    // Ollama-only. Its OpenAI-compatible /v1/models says nothing about capabilities, but the
    // native tag list reports each model's families - a "clip" or "mllama" family is how a
    // vision model shows up. Any other server answers 404 here and the caller ignores it.
    @GET("api/tags")
    suspend fun listOllamaTags(): JsonElement

    // Image generation. Returns raw JSON for the same reason listModels does: providers disagree
    // on where the bytes live ({"data":[{"b64_json":...}]}, {"data":[{"base64":...}]},
    // {"data":[{"url":...}]}, and some return the string directly).
    @POST("v1/images/generations")
    suspend fun createImage(
        @Header("Authorization") authorization: String,
        @Body request: ImageGenerationRequest
    ): JsonElement

    // Fallback for providers that answer with a URL instead of bytes. Reuses the same OkHttp
    // client (and therefore the same timeouts and logging redaction) rather than opening a
    // second one.
    @GET
    suspend fun downloadUrl(@Url url: String): ResponseBody

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
