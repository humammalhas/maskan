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
                    // Only send a text part when there IS text. An image attached without a
                    // caption used to ship "text": "", which Venice rejects with
                    // 400 "Text content cannot be empty".
                    if (value.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(value.text))
                        })
                    }
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

// Response of GET /v1/models — used to auto-detect models installed on a
// local server (Ollama, LM Studio, any OpenAI-compatible endpoint).
@Serializable
data class ModelsResponse(
    val data: List<ModelInfo> = emptyList()
)

@Serializable
data class ModelInfo(
    val id: String = ""
)

/**
 * POST /v1/images/generations.
 *
 * [responseFormat] differs by provider: OpenAI (and everything modelled on it) calls the base64
 * option "b64_json", Together calls it "base64". Either way we ask for BYTES rather than a URL,
 * so the image arrives in the response we already made and needs no second fetch.
 *
 * The global Json sets encodeDefaults = true, so n and response_format are always sent.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int = 1,
    /**
     * Null means "do not send this field at all", which matters: OpenAI's gpt-image-* models
     * REJECT response_format (they always return base64) while dall-e-3 accepts it, and Together
     * spells the same option "base64". encodeDefaults = true is on globally for Anthropic's sake,
     * so a null would otherwise serialize as "response_format": null and be rejected - exactly
     * the bug that broke every Anthropic chat in v2.4.5.
     */
    @SerialName("response_format")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val responseFormat: String? = null,
    /**
     * The photo to EDIT, as a data: URI, for edit models on a local server (flux2-edit). Same
     * never-encode-null rule as above: cloud providers must not see an unknown field.
     */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val image: String? = null,
    /** "WxH". Only the local path sends it; cloud size vocabularies differ and are left alone. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val size: String? = null
)