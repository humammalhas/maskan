package app.maskan.chat.data.remote.providers

import android.util.Base64
import app.maskan.chat.data.remote.ChatCompletionChunk
import app.maskan.chat.data.remote.ChatCompletionRequest
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.OpenAiCompatibleService
import app.maskan.chat.data.remote.parseSSEStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class LocalProvider(
    private val config: ProviderConfig,
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : AiProvider {

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val nameAr: String = config.nameAr
    override val defaultBaseUrl: String = config.baseUrl
    override val supportsCustomBaseUrl: Boolean = true
    override val supportsVision: Boolean = config.supportsVision
    override val availableModels: List<String> = config.models
    override val defaultModel: String = config.defaultModel
    override val keyAcquisitionUrl: String = config.keyAcquisitionUrl
    override val pricingInfo: String = config.pricingInfo

    private val serviceCache = LinkedHashMap<String, OpenAiCompatibleService>(8, 0.75f, false)

    private fun getService(baseUrl: String): OpenAiCompatibleService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        serviceCache[normalizedUrl]?.let { return it }
        if (serviceCache.size >= 5) {
            serviceCache.remove(serviceCache.keys.first())
        }
        return serviceCache.getOrPut(normalizedUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OpenAiCompatibleService::class.java)
        }
    }

    private fun resolveUrl(baseUrl: String?): String {
        val effectiveUrl = baseUrl ?: defaultBaseUrl
        if (effectiveUrl.isBlank()) {
            throw Exception("No server URL configured. Please enter your $displayName server URL in Settings.")
        }
        return effectiveUrl
    }

    private fun buildMessages(
        messages: List<Message>,
        imageData: ByteArray?,
        imageMimeType: String?
    ): List<Message> {
        if (imageData == null || imageMimeType == null) return messages
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex == -1) return messages
        val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
        return messages.toMutableList().apply {
            val original = this[lastUserIndex]
            this[lastUserIndex] = Message(
                role = original.role,
                content = MessageContent.WithImage(
                    text = original.content.textContent(),
                    imageBase64 = base64,
                    mimeType = imageMimeType
                )
            )
        }
    }

    override suspend fun sendMessage(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): String {
        val service = getService(resolveUrl(baseUrl))
        val request = ChatCompletionRequest(
            model = model,
            messages = buildMessages(messages, imageData, imageMimeType)
        )

        val response = service.createChatCompletion(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else "",
            request = request
        )

        return response.choices.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from $displayName")
    }

    override fun sendMessageStreaming(
        apiKey: String,
        model: String,
        messages: List<Message>,
        baseUrl: String?,
        imageData: ByteArray?,
        imageMimeType: String?
    ): Flow<String> {
        val service = getService(resolveUrl(baseUrl))
        val request = ChatCompletionRequest(
            model = model,
            messages = buildMessages(messages, imageData, imageMimeType),
            stream = true
        )
        val call = service.createChatCompletionStream(
            authorization = if (apiKey.isNotBlank()) "Bearer $apiKey" else "",
            request = request
        )
        return parseSSEStream(call) { data ->
            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
            chunk.choices.firstOrNull()?.delta?.content
        }
    }
}
