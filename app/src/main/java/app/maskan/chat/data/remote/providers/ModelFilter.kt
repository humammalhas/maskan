package app.maskan.chat.data.remote.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a fetch of a provider's catalogue yielded: the chat model ids, plus the subset that
 * accepts image input where the provider actually says so. An empty [visionIds] means "this
 * provider publishes no capability data", NOT "no model here sees images" - callers fall back to
 * the provider-level flag in that case rather than guessing per model.
 */
data class FetchedModels(
    val ids: List<String> = emptyList(),
    val visionIds: Set<String> = emptySet(),
    val freeIds: Set<String> = emptySet()
)

/**
 * Shared handling for model lists fetched from a provider's /models endpoint.
 *
 * Two jobs:
 *  - [idsFrom] copes with the fact that "OpenAI-compatible" is not one response shape. OpenAI,
 *    Groq, DeepSeek, Mistral, Venice and OpenRouter wrap the list in {"data": [...]}, Together AI
 *    returns a bare JSON array, and some local servers use {"models": [...]} or plain strings.
 *    Deserializing into a fixed DTO fails on the odd ones out, which is exactly what Together did.
 *  - [chatModelsOnly] drops entries that cannot serve a chat completion. The marker list is
 *    deliberately narrow: a false positive hides a model the user is paying for, and
 *    vision/multimodal chat models must survive it.
 */
object ModelFilter {

    private val NON_CHAT_MARKERS = listOf(
        // Safety classifiers answer every prompt with a verdict ("User Safety: safe"), so they
        // look like a working chat model until you talk to one. OpenRouter lists several in its
        // FREE tier, which is exactly where a user hunting zero-cost models will land.
        "content-safety", "safety", "shield", "classifier", "reward-model", "nemoguard",
        "embed", "embedding", "rerank", "moderation", "guard",
        "whisper", "tts", "speech", "transcribe", "audio-preview",
        "dall-e", "imagen", "veo", "sora", "stable-diffusion", "flux",
        "image-generation", "-image-", "upscal", "bge-", "text-similarity",
        "babbage", "davinci", "curie"
    )

    private val NON_CHAT_TYPES = setOf(
        "image", "embedding", "embeddings", "moderation", "rerank",
        "audio", "video", "transcribe", "tts", "speech"
    )

    fun idsFrom(element: JsonElement): List<String> {
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["data"] ?: element["models"]) as? JsonArray
            else -> null
        } ?: return emptyList()

        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> {
                    // Together and Venice tag each entry with a "type"; drop the ones that can
                    // never answer a chat turn. An unknown or absent type is kept - better an
                    // extra row in the list than a missing model the user pays for.
                    val type = (item["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()
                    if (type != null && type in NON_CHAT_TYPES) {
                        null
                    } else {
                        (item["id"] ?: item["name"] ?: item["model"])
                            ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    }
                }
                else -> null
            }
        }
    }

    /**
     * Ids the provider marks as accepting image input. Two publishers give us this honestly:
     * OpenRouter (architecture.input_modalities) and Venice (model_spec.capabilities.supportsVision).
     * Everything else returns nothing here and is decided by the provider-level flag.
     */
    fun visionIdsFrom(element: JsonElement): Set<String> {
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["data"] ?: element["models"]) as? JsonArray
            else -> null
        } ?: return emptySet()

        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null

            val takesImages = (obj["architecture"] as? JsonObject)
                ?.get("input_modalities")
                ?.let { it as? JsonArray }
                ?.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
                ?: ((obj["model_spec"] as? JsonObject)
                    ?.get("capabilities") as? JsonObject)
                    ?.get("supportsVision")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull == "true" }

            if (takesImages == true) id else null
        }.toSet()
    }

    /**
     * Ollama /api/tags entries carry details.families; a vision model lists "clip" or "mllama"
     * alongside its text family. Matching is loose because the OpenAI-compatible id and the tag
     * name can differ by a ":latest" suffix.
     */
    fun ollamaVisionIds(element: JsonElement, knownIds: List<String>): Set<String> {
        val models = (element as? JsonObject)?.get("models") as? JsonArray ?: return emptySet()
        val visionNames = models.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val families = (obj["details"] as? JsonObject)?.get("families") as? JsonArray
            val seesImages = families?.any {
                val family = (it as? JsonPrimitive)?.contentOrNull?.lowercase()
                family == "clip" || family == "mllama"
            } ?: false
            if (seesImages) (obj["name"] as? JsonPrimitive)?.contentOrNull else null
        }

        return knownIds.filter { id ->
            visionNames.any { name ->
                name.equals(id, ignoreCase = true) ||
                    name.substringBefore(":").equals(id.substringBefore(":"), ignoreCase = true)
            }
        }.toSet()
    }

    /**
     * Ids the provider prices at zero. OpenRouter publishes per-model pricing and hosts a whole
     * free tier (the ":free" variants), which matters because a paid model on an account with no
     * credits answers 402 - nothing to do with the key or the model.
     */
    fun freeIdsFrom(element: JsonElement): Set<String> {
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["data"] ?: element["models"]) as? JsonArray
            else -> null
        } ?: return emptySet()

        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val pricing = obj["pricing"] as? JsonObject ?: return@mapNotNull null
            val prompt = (pricing["prompt"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            val completion = (pricing["completion"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            if (prompt == 0.0 && completion == 0.0) id else null
        }.toSet()
    }

    fun chatModelsOnly(ids: List<String>): List<String> =
        ids.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { id ->
                val lower = id.lowercase()
                // A trailing "-image" is how Gemini names its image-GENERATION models
                // (gemini-2.5-flash-image); they cannot answer a chat turn. Note this is the
                // opposite of a vision model, which takes images IN and must be kept.
                if (lower.endsWith("-image") || lower.endsWith("-image-preview")) return@filter false
                NON_CHAT_MARKERS.none { marker -> lower.contains(marker) }
            }
            .distinct()
            .sorted()
            .toList()
}
