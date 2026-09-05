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
        @Query("type") type: String? = null,
        // OpenRouter-only: video models are absent from the default catalogue and appear
        // only when asked for by output modality (verified 2026-09-05: 0 of 431 default
        // entries output video; ?output_modalities=video returns 28).
        @Query("output_modalities") outputModalities: String? = null
    ): JsonElement

    // Ollama-only. Its OpenAI-compatible /v1/models says nothing about capabilities, but the
    // native tag list reports each model's families - a "clip" or "mllama" family is how a
    // vision model shows up. Any other server answers 404 here and the caller ignores it.
    @GET("api/tags")
    suspend fun listOllamaTags(): JsonElement

    // Maskan's own local proxy (and any server that copies it) describes itself here: which
    // ids are image models, which are video models, whether it expands prompts. /v1/models
    // cannot say any of that. Every other server answers 404 and the caller falls back.
    @GET("health")
    suspend fun health(
        @Header("Authorization") authorization: String
    ): JsonElement

    // Balance endpoints. Only two of the twelve providers expose one; both are read-only.
    // OpenRouter: {"data":{"total_credits":..,"total_usage":..}} relative to its /api/ base.
    @GET("v1/credits")
    suspend fun openRouterCredits(@Header("Authorization") authorization: String): JsonElement

    // DeepSeek: {"balance_infos":[{"currency":"USD","total_balance":"..."}]} - note the path has
    // no v1 prefix on purpose; that is where DeepSeek serves it.
    @GET("user/balance")
    suspend fun deepSeekBalance(@Header("Authorization") authorization: String): JsonElement

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

    // OpenRouter's image path. It has NO /v1/images/generations: you ask at the ordinary chat
    // endpoint with modalities: ["image","text"] and the picture comes back as a data: URL inside
    // choices[0].message.images. Raw JSON both ways - the request needs a field no chat DTO has,
    // and the response shape fits none of them either.
    @POST("v1/chat/completions")
    suspend fun createChatCompletionRaw(
        @Header("Authorization") authorization: String,
        @Body request: JsonElement
    ): JsonElement

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
