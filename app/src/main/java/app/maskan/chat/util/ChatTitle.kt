package app.maskan.chat.util

/**
 * The request that names a chat, and what is done to the answer before it becomes the name.
 *
 * Pure, so the replies measured on the device (2.7 session 6) can be fed to a test. Measured on
 * Qwen2.5 1.5B, 126 runs, en/ar/th: no reply ever began "The title is:". What it did instead was
 * answer in the wrong language - half of the Arabic exchanges came back with an English, Chinese
 * or Spanish title - copy the greeting it had just given ("Hello! How can I assist you today?"),
 * and once emit Thai as UTF-8 bytes read as Latin-1. DeepSeek, given the same request, made none
 * of these. Naming the language took Qwen's wrong-language titles from 9 in 18 to 0 in 18 for
 * Arabic. Everything here is model-agnostic on purpose: it must be re-measured when the on-device
 * model changes (Qwen3, plan item 14), and it protects cloud models too.
 */
object ChatTitle {

    enum class Script { ARABIC, THAI, LATIN, CJK, OTHER }

    /**
     * The instruction for the title request. The language is named only where the letters say
     * what it is - Arabic or Thai script - and is otherwise left as "the language of the
     * exchange", which every model measured follows for English.
     */
    fun instruction(question: String): String {
        val language = when (scriptOf(question)) {
            Script.ARABIC -> "Arabic"
            Script.THAI -> "Thai"
            else -> "THE SAME LANGUAGE as the exchange"
        }
        return "You name conversations. Read the exchange and reply with a title of " +
            "3 to 5 words for it, written in " + language + ". Reply " +
            "with the title alone: no quotation marks, no full stop at the end, no " +
            "explanation, no translation, nothing else."
    }

    /**
     * The first line of the answer, with the decorations models add to a title stripped off, or
     * null if what came back is not a title for this chat.
     *
     * Null is always safe: the chat keeps the name it had (its first message) and the next turn
     * may try again. A chat list of first messages is worse than one of good titles and much
     * better than one of greetings, paragraphs and mojibake.
     */
    fun clean(raw: String, question: String): String? {
        var line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        line = stripLeadIn(line)
        line = line.trim { it in QUOTES }
            .replace('_', ' ')
            .trimEnd('.', ARABIC_COMMA, ':', ARABIC_SEMICOLON)
            .trim()
        if (line.isEmpty() || line.length > MAX_LENGTH) return null
        // A title does not exclaim. Every "!" measured was the model's own greeting copied in.
        if ('!' in line) return null
        // Nor does it ask, unless the question did: "What is the capital of Jordan?" as the
        // name of a chat that asked it is fine; "How can I help you today?" for "hi" is the reply.
        if (line.last() in QUESTION_MARKS && question.trim().lastOrNull() !in QUESTION_MARKS) return null
        if (looksMisdecoded(line)) return null
        // In the script the person wrote in. An Arabic chat named in English is the measured
        // failure; a Latin question named in Chinese is the same failure the other way round.
        // A question with no letters at all ("2+2", a pasted number) says nothing about script.
        val asked = scriptOf(question)
        if (asked != Script.OTHER && scriptOf(line) != asked) return null
        return line
    }

    /**
     * What a chat is called when nothing has named it: the start of its first message.
     *
     * Trimmed first, so the name a sent message derives and the one generateTitle recognises as
     * derived are the same string. Before, a first message typed with a trailing newline kept it
     * in the title, generateTitle took that title for a rename, and the chat was never named.
     */
    fun fallback(text: String): String =
        text.trim().take(FALLBACK_LENGTH).let { if (it.length == FALLBACK_LENGTH) "$it..." else it }

    /** The script most of the letters in [text] are written in. */
    fun scriptOf(text: String): Script {
        val counts = IntArray(Script.values().size)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            counts[scriptOfCodePoint(cp).ordinal]++
        }
        if (counts.all { it == 0 }) return Script.OTHER
        return Script.values()[counts.indices.maxByOrNull { counts[it] }!!]
    }

    private fun scriptOfCodePoint(cp: Int): Script = when (cp) {
        in 0x0600..0x06FF, in 0x0750..0x077F, in 0x08A0..0x08FF,
        in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> Script.ARABIC
        in 0x0E00..0x0E7F -> Script.THAI
        in 0x0041..0x024F -> Script.LATIN
        in 0x3040..0x30FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xAC00..0xD7AF -> Script.CJK
        else -> Script.OTHER
    }

    /**
     * "The title for this conversation is: greeting inquiry" -> "greeting inquiry". The shape
     * the plan reported (2.6.1); never seen in the 2.7 measurements, kept because it costs
     * nothing and the next model may do it. Only a prefix that holds the word for title and ENDS
     * in it or in "is" counts, so "Python: Basics" and "Title IX: an overview" are left alone.
     */
    private fun stripLeadIn(line: String): String {
        val colon = line.indexOfFirst { it == ':' || it == FULLWIDTH_COLON }
        if (colon <= 0 || colon > LEAD_IN_MAX) return line
        val before = line.substring(0, colon).trim()
        if (!LEAD_IN.containsMatchIn(before)) return line
        return line.substring(colon + 1).trim()
    }

    /**
     * UTF-8 read as Latin-1: a Latin-1 letter in the range UTF-8 lead bytes decode to, followed
     * by one in the continuation range (U+0080-U+00BF) - "CafÃ©", "การชà¹..." - or the
     * replacement character. No real title in any language has that pair.
     */
    private fun looksMisdecoded(line: String): Boolean {
        if (REPLACEMENT_CHAR in line) return true
        for (i in 0 until line.length - 1) {
            if (line[i].code in 0xC2..0xEF && line[i + 1].code in 0x80..0xBF) return true
        }
        return false
    }

    private const val MAX_LENGTH = 60
    private const val FALLBACK_LENGTH = 50
    private const val LEAD_IN_MAX = 40
    private val ARABIC_COMMA = 0x060C.toChar()
    private val ARABIC_SEMICOLON = 0x061B.toChar()
    private val FULLWIDTH_COLON = 0xFF1A.toChar()
    private val REPLACEMENT_CHAR = 0xFFFD.toChar()
    private val QUESTION_MARKS = setOf('?', 0x061F.toChar(), 0xFF1F.toChar())
    // Straight, curly and guillemet quotes: asking a model not to quote a title works about
    // four times in five.
    private val QUOTES = listOf(0x22, 0x27, 0x201C, 0x201D, 0x2018, 0x2019, 0x00AB, 0x00BB)
        .map { it.toChar() }.toSet()
    private val LEAD_IN = Regex(
        "(title|العنوان|عنوان|ชื่อเรื่อง|หัวข้อ|ชื่อ)(.*(\\bis|هو|คือ))?\\s*$",
        RegexOption.IGNORE_CASE
    )
}
