package app.maskan.chat.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val stream: Boolean = false
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

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
