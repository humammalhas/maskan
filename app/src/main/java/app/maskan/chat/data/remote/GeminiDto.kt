package app.maskan.chat.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("system_instruction")
    val systemInstruction: GeminiSystemInstruction? = null
)

@Serializable
data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiSystemInstruction(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@Serializable
data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

@Serializable
data class GeminiStreamChunk(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)
