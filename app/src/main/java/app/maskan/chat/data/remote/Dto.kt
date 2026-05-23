package app.maskan.chat.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a single message in the chat completion request/response.
 * Matches the DeepSeek API format: {"role": "...", "content": "..."}
 */
@Serializable
data class Message(
    val role: String,
    val content: String
)

/**
 * Request body for POST /v1/chat/completions.
 * [model] can be "deepseek-chat" or "deepseek-coder".
 * [messages] is the conversation history.
 * [stream] is always false for MVP (no streaming).
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = false
)

/**
 * Represents a choice in the API response.
 */
@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    @SerialName("finish_reason")
    val finishReason: String
)

/**
 * Token usage statistics returned by the API.
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

/**
 * Response body from POST /v1/chat/completions.
 */
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

