package app.maskan.chat.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One page a provider's own web search read for an answer. Saved with the message. */
@Serializable
data class WebSource(val title: String, val url: String)

/**
 * Reading a provider's streamed reply when its own web search is on: the text, as before, plus
 * the pages it searched - which every provider sends in a different place.
 *
 * Each reader was written against a real streamed response captured on Humam's keys on
 * 2026-10-23 (app/src/test/resources/search/), and is tested against it. Sources come from the
 * provider's own data, never from links the model writes into its text: a model can forget to
 * cite, or cite a page it did not read.
 */
object SearchStream {

    data class Piece(val text: String?, val sources: List<WebSource> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Anthropic: text arrives as `text_delta`; each search's pages arrive whole in a
     * `content_block_start` whose block is a `web_search_tool_result`.
     */
    fun anthropic(data: String): Piece {
        val event = parse(data) ?: return Piece(null)
        return when (event.str("type")) {
            "content_block_delta" -> {
                val delta = event.obj("delta")
                Piece(if (delta?.str("type") == "text_delta") delta.str("text") else null)
            }
            "content_block_start" -> {
                val block = event.obj("content_block")
                if (block?.str("type") != "web_search_tool_result") return Piece(null)
                // A paragraph break where the results land: a model that says "I'll search" and
                // then answers writes two text blocks with nothing between them ("…news.I
                // searched"). The repository trims it off the front when nothing came before.
                Piece("\n\n", block.arr("content").mapNotNull { source(it, "title", "url") })
            }
            else -> Piece(null)
        }
    }

    /**
     * Gemini: text in `candidates[0].content.parts`; the pages in the LAST chunk's
     * `groundingMetadata.groundingChunks[].web`. Gemini's urls are its own redirect addresses
     * and its titles are bare domains - that is what it sends.
     */
    fun gemini(data: String): Piece {
        val chunk = parse(data) ?: return Piece(null)
        val candidate = chunk.arr("candidates").firstOrNull() as? JsonObject ?: return Piece(null)
        val text = candidate.obj("content")?.arr("parts")
            ?.mapNotNull { (it as? JsonObject)?.str("text") }
            ?.joinToString("")
            ?.ifEmpty { null }
        val sources = candidate.obj("groundingMetadata")?.arr("groundingChunks")
            ?.mapNotNull { (it as? JsonObject)?.obj("web")?.let { web -> source(web, "title", "uri") } }
            .orEmpty()
        return Piece(text, sources)
    }

    /**
     * The OpenAI-shaped stream, which three providers extend three ways:
     *  - OpenRouter: `choices[0].delta.annotations[]` of type `url_citation`;
     *  - Venice: top-level `venice_parameters.web_search_citations[]` (only when the request
     *    asked for `include_search_results_in_stream`);
     *  - Groq: `choices[0].delta.executed_tools[]` - only the `browser_search` calls. Its
     *    `browser.open` and `browser.find` steps list the pages it read INTO, titled
     *    "www.npr.org - viewing lines [0 - 42]", which are not sources.
     */
    fun openAiCompatible(data: String): Piece {
        val chunk = parse(data) ?: return Piece(null)
        val choice = chunk.arr("choices").firstOrNull() as? JsonObject
        val delta = choice?.obj("delta")
        val sources = ArrayList<WebSource>()

        delta?.arr("annotations")?.forEach { annotation ->
            val obj = annotation as? JsonObject ?: return@forEach
            if (obj.str("type") == "url_citation") {
                obj.obj("url_citation")?.let { source(it, "title", "url") }?.let(sources::add)
            }
        }
        chunk.obj("venice_parameters")?.arr("web_search_citations")?.forEach { citation ->
            source(citation, "title", "url")?.let(sources::add)
        }
        delta?.arr("executed_tools")?.forEach { tool ->
            val obj = tool as? JsonObject ?: return@forEach
            if (obj.str("type") != "browser_search") return@forEach
            obj.obj("search_results")?.arr("results")?.forEach { result ->
                source(result, "title", "url")?.let(sources::add)
            }
        }
        return Piece(delta?.str("content"), sources)
    }

    /**
     * OpenAI's Responses API: text as `response.output_text.delta`, each cited page as a
     * `response.output_text.annotation.added` of type `url_citation`.
     */
    fun openAiResponses(data: String): Piece {
        val event = parse(data) ?: return Piece(null)
        return when (event.str("type")) {
            "response.output_text.delta" -> Piece(event.str("delta"))
            "response.output_text.annotation.added" -> {
                val annotation = event.obj("annotation")
                if (annotation?.str("type") == "url_citation") {
                    Piece(null, listOfNotNull(source(annotation, "title", "url")))
                } else {
                    Piece(null)
                }
            }
            else -> Piece(null)
        }
    }

    /**
     * Mistral's Conversations API: text as `message.output.delta` (a string, or a chunk object
     * with type "text"); the pages in each `tool.execution.done`, whose `info.result` is a JSON
     * STRING of id -> {url, title, ...} - the search engine's own results. A streamed Mistral
     * reply carries no tool_reference entries (the non-streamed one does), so they come from here.
     */
    fun mistral(data: String): Piece {
        val event = parse(data) ?: return Piece(null)
        return when (event.str("type")) {
            "message.output.delta" -> when (val content = event["content"]) {
                is JsonPrimitive -> Piece(content.contentOrNull)
                is JsonObject -> when (content.str("type")) {
                    "text" -> Piece(content.str("text"))
                    "tool_reference" -> Piece(null, listOfNotNull(source(content, "title", "url")))
                    else -> Piece(null)
                }
                else -> Piece(null)
            }
            "tool.execution.done" -> {
                val result = event.obj("info")?.str("result") ?: return Piece(null)
                val pages = parse(result) ?: return Piece(null)
                Piece(null, pages.values.mapNotNull { source(it, "title", "url") })
            }
            else -> Piece(null)
        }
    }

    /** New sources after old ones, each url once, first title kept. */
    fun merge(existing: List<WebSource>, more: List<WebSource>): List<WebSource> {
        if (more.isEmpty()) return existing
        val seen = existing.mapTo(HashSet()) { it.url }
        return existing + more.filter { seen.add(it.url) }
    }

    private val listSerializer = ListSerializer(WebSource.serializer())

    fun encode(sources: List<WebSource>): String? =
        if (sources.isEmpty()) null else json.encodeToString(listSerializer, sources)

    /** Never throws: a row written by a later version, or damaged, simply shows no sources. */
    fun decode(stored: String?): List<WebSource> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, stored) }.getOrDefault(emptyList())
    }

    // -- JSON helpers -----------------------------------------------------------------------

    private fun parse(data: String): JsonObject? =
        runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()

    private fun source(element: JsonElement, titleKey: String, urlKey: String): WebSource? {
        val obj = element as? JsonObject ?: return null
        val url = obj.str(urlKey)?.takeIf { it.startsWith("http") } ?: return null
        val title = obj.str(titleKey)?.trim()?.ifEmpty { null } ?: url
        return WebSource(title = title, url = url)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
}
