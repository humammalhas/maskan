package app.maskan.chat.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    // Two Anthropic requirements, both learned from 400s:
    //  - the system prompt must be content blocks, not a bare string;
    //  - the field must be ABSENT when there is no system prompt. The global Json config sets
    //    encodeDefaults = true (Anthropic needs max_tokens), which with explicit nulls would
    //    emit "system": null and earn a 400 "system: Input should be a valid array".
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val system: List<AnthropicSystemBlock>? = null,
    val messages: List<AnthropicMessage>,
    val stream: Boolean = false
)

@Serializable
data class AnthropicSystemBlock(
    val type: String = "text",
    val text: String
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: @Serializable(with = AnthropicContentSerializer::class) AnthropicMessageContent
)

sealed class AnthropicMessageContent {
    data class Text(val text: String) : AnthropicMessageContent()
    data class WithImage(
        val text: String,
        val imageBase64: String,
        val mimeType: String
    ) : AnthropicMessageContent()
}

object AnthropicContentSerializer : KSerializer<AnthropicMessageContent> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: AnthropicMessageContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is AnthropicMessageContent.Text ->
                jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is AnthropicMessageContent.WithImage -> {
                val array = buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image"))
                        put("source", buildJsonObject {
                            put("type", JsonPrimitive("base64"))
                            put("media_type", JsonPrimitive(value.mimeType))
                            put("data", JsonPrimitive(value.imageBase64))
                        })
                    })
                    // Skip the text block for an uncaptioned image - an empty string is not a
                    // valid content block.
                    if (value.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(value.text))
                        })
                    }
                }
                jsonEncoder.encodeJsonElement(array)
            }
        }
    }

    override fun deserialize(decoder: Decoder): AnthropicMessageContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> AnthropicMessageContent.Text(element.content)
            else -> AnthropicMessageContent.Text("")
        }
    }
}

@Serializable
data class AnthropicResponse(
    val id: String? = null,
    val type: String,
    val role: String? = null,
    val content: List<AnthropicContentBlock>? = null,
    val model: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicError? = null
)

@Serializable
data class AnthropicError(
    val type: String? = null,
    val message: String? = null
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int
)

@Serializable
data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicStreamDelta? = null
)

@Serializable
data class AnthropicStreamDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null
)

// GET /v1/models - lets the app discover Anthropic's current models instead of relying on the
// bundled list, which goes stale every time a new Claude snapshot ships.
@Serializable
data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean = false
)

@Serializable
data class AnthropicModelInfo(
    val id: String = "",
    @SerialName("display_name")
    val displayName: String? = null
)