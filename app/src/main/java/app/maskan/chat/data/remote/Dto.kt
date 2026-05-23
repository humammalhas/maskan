package app.maskan.chat.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = MessageContentSerializer::class)
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class WithImage(
        val text: String,
        val imageBase64: String,
        val mimeType: String
    ) : MessageContent()

    fun textContent(): String = when (this) {
        is Text -> text
        is WithImage -> text
    }
}

object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is MessageContent.Text -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is MessageContent.WithImage -> {
                val array = buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(value.text))
                    })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put("image_url", buildJsonObject {
                            put("url", JsonPrimitive("data:${value.mimeType};base64,${value.imageBase64}"))
                        })
                    })
                }
                jsonEncoder.encodeJsonElement(array)
            }
        }
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> MessageContent.Text(element.content)
            is JsonArray -> {
                val textPart = element.firstOrNull {
                    it is kotlinx.serialization.json.JsonObject &&
                        (it["type"] as? JsonPrimitive)?.content == "text"
                }
                val text = (textPart as? kotlinx.serialization.json.JsonObject)
                    ?.get("text")?.jsonPrimitive?.content ?: ""
                MessageContent.Text(text)
            }
            else -> MessageContent.Text("")
        }
    }
}

@Serializable
data class Message(
    val role: String,
    val content: MessageContent
) {
    constructor(role: String, text: String) : this(role, MessageContent.Text(text))
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChoiceMessage,
    @SerialName("finish_reason")
    val finishReason: String
)

@Serializable
data class ChoiceMessage(
    val role: String,
    val content: String
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object")
    val obj: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

@Serializable
data class StreamChoice(
    val index: Int,
    val delta: StreamDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class StreamDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String = "",
    @SerialName("object")
    val obj: String = "",
    val choices: List<StreamChoice> = emptyList()
)
