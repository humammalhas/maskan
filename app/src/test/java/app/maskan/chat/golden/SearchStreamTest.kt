package app.maskan.chat.golden

import app.maskan.chat.data.remote.SearchStream
import app.maskan.chat.data.remote.WebSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The five providers' web-search streams, read back from REAL responses.
 *
 * Each file in src/test/resources/search is the exact body one provider streamed on Humam's key
 * on 2026-10-23, saved by the debug probe (`--es save`) - not a hand-made sample. The reading
 * below is the same as parseSSEStream's: `data: ` lines, trimmed, `[DONE]` ends it. What comes
 * out - the answer text and the sources - is pinned as a golden.
 */
class SearchStreamTest {

    private val dir = File("src/test/resources/search")

    @Test
    fun anthropic() {
        check("anthropic", SearchStream::anthropic)
    }

    @Test
    fun gemini() {
        check("gemini", SearchStream::gemini)
    }

    @Test
    fun openRouter() {
        check("openrouter", SearchStream::openAiCompatible)
    }

    @Test
    fun venice() {
        check("venice", SearchStream::openAiCompatible)
    }

    @Test
    fun groq() {
        val sources = check("groq", SearchStream::openAiCompatible)
        // Its page-reading steps are not sources; only what browser_search returned is.
        assertFalse(sources.any { "viewing lines" in it.title })
    }

    @Test
    fun mergeKeepsTheFirstOfEachUrl() {
        val a = WebSource("A", "https://a.example")
        val merged = SearchStream.merge(listOf(a), listOf(WebSource("A again", "https://a.example"), WebSource("B", "https://b.example")))
        assertEquals(listOf(a, WebSource("B", "https://b.example")), merged)
    }

    @Test
    fun aDamagedRowShowsNoSourcesRatherThanCrashing() {
        assertEquals(emptyList<WebSource>(), SearchStream.decode("{not json"))
        assertEquals(emptyList<WebSource>(), SearchStream.decode(null))
        val list = listOf(WebSource("عنوان", "https://x.example/ص"))
        assertEquals(list, SearchStream.decode(SearchStream.encode(list)))
    }

    private fun check(name: String, read: (String) -> SearchStream.Piece): List<WebSource> {
        val text = StringBuilder()
        var sources = emptyList<WebSource>()
        File(dir, "$name-stream.txt").readText().lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ").trim() }
            .takeWhile { it != "[DONE]" }
            .forEach { data ->
                val piece = read(data)
                piece.text?.let(text::append)
                sources = SearchStream.merge(sources, piece.sources)
            }
        assertTrue("$name: no answer text", text.isNotBlank())
        assertTrue("$name: no sources", sources.isNotEmpty())
        Golden.check(
            "search-$name",
            "text:\n$text\n\nsources:\n" + sources.joinToString("\n") { it.title + " | " + it.url } + "\n"
        )
        return sources
    }
}
