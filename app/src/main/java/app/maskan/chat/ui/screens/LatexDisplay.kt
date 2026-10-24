package app.maskan.chat.ui.screens

/**
 * LaTeX a model wrote, turned into text a person can read: `\frac{3}{7} \times 84` becomes
 * `3/7 × 84`. DISPLAY ONLY - the saved message, the copy button and the next request all keep the
 * model's own words, so nothing a user pastes elsewhere loses its LaTeX.
 *
 * This is not a typesetter. It knows the commands arithmetic answers actually use (DeepSeek and
 * gpt-oss write `\[ … \]`, `\frac`, `\times`, `\text{ dollars}` and `\boxed{36}` for a question
 * about the price of pens) and leaves every command it does not know exactly as written, so the
 * worst case is what the bubble showed before.
 *
 * Code is never touched: a fenced block or an inline `code` span is copied through byte for byte,
 * because LaTeX in code is source someone wants to read.
 *
 * Math delimiters are stripped: `\[ \]`, `\( \)`, `$$ $$`, and `$ $` only when what is between
 * them holds a backslash - "pens cost $5 and $10" is money, not math. Inside a math region, braces
 * that only group are dropped and `^2` / `_1` become ² / ₁ where Unicode has the glyph. Outside one,
 * only the named commands are converted, and only where the backslash cannot be a path separator:
 * `C:\Users\me\int` keeps its `\int`.
 */
fun latexForDisplay(text: String): String {
    if (!text.contains('\\') && !text.contains("$$")) return text
    val out = StringBuilder(text.length)
    var i = 0
    var proseStart = 0
    while (i < text.length) {
        val atLineStart = i == 0 || text[i - 1] == '\n'
        val codeEnd = when {
            atLineStart && text.startsWith("```", i) -> {
                val close = text.indexOf("\n```", i + 3)
                if (close < 0) text.length else (text.indexOf('\n', close + 4).takeIf { it >= 0 } ?: text.length)
            }
            text[i] == '`' -> {
                val close = text.indexOf('`', i + 1)
                val lineEnd = text.indexOf('\n', i + 1).let { if (it < 0) text.length else it }
                if (close in (i + 1) until lineEnd) close + 1 else -1
            }
            else -> -1
        }
        if (codeEnd > i) {
            out.append(convertProse(text.substring(proseStart, i)))
            out.append(text, i, codeEnd)
            i = codeEnd
            proseStart = i
        } else {
            i++
        }
    }
    out.append(convertProse(text.substring(proseStart)))
    return out.toString()
}

/** Prose: find the math regions, convert inside them as math and between them as text. */
private fun convertProse(prose: String): String {
    if (prose.isEmpty()) return prose
    val out = StringBuilder(prose.length)
    var i = 0
    var textStart = 0
    while (i < prose.length) {
        val region = mathRegionAt(prose, i)
        if (region != null) {
            out.append(convertCommands(prose.substring(textStart, i), math = false))
            out.append(convertCommands(prose.substring(region.first, region.second), math = true))
            i = region.third
            textStart = i
        } else {
            i++
        }
    }
    out.append(convertCommands(prose.substring(textStart), math = false))
    return out.toString()
}

/** A math region opening at [i]: (content start, content end, index after the closer), or null. */
private fun mathRegionAt(s: String, i: Int): Triple<Int, Int, Int>? {
    fun closedBy(open: String, close: String): Triple<Int, Int, Int>? {
        if (!s.startsWith(open, i)) return null
        val end = s.indexOf(close, i + open.length)
        return if (end < 0) null else Triple(i + open.length, end, end + close.length)
    }
    closedBy("\\[", "\\]")?.let { return it }
    closedBy("\\(", "\\)")?.let { return it }
    closedBy("$$", "$$")?.let { return it }
    if (s[i] == '$' && (i == 0 || s[i - 1] != '\\')) {
        var end = s.indexOf('$', i + 1)
        while (end > 0 && s[end - 1] == '\\') end = s.indexOf('$', end + 1)
        if (end > i + 1) {
            val inner = s.substring(i + 1, end)
            // TeX's own rule: `$x$` hugs its content, so "$5 and $10" never opens.
            val hugs = !inner.first().isWhitespace() && !inner.last().isWhitespace()
            if (hugs && inner.contains('\\') && !inner.contains('\n')) return Triple(i + 1, end, end + 1)
        }
    }
    return null
}

private val SYMBOLS = mapOf(
    "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "infty" to "∞",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "implies" to "⇒",
    "Leftarrow" to "⇐", "iff" to "⇔", "Leftrightarrow" to "⇔", "therefore" to "∴",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "partial" to "∂", "nabla" to "∇",
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩",
    "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°", "circ" to "∘",
    "ldots" to "…", "dots" to "…", "cdots" to "⋯",
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "varepsilon" to "ε", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π",
    "Sigma" to "Σ", "Phi" to "Φ", "Omega" to "Ω",
    "quad" to " ", "qquad" to " ", "displaystyle" to "", "left" to "", "right" to "",
    "big" to "", "Big" to "", "bigg" to "", "Bigg" to ""
)

/** Binary operators and relations: TeX spaces them, so they get a space either side. */
private val SPACED = setOf(
    "times", "div", "cdot", "pm", "mp", "le", "leq", "ge", "geq", "ne", "neq", "approx", "equiv",
    "sim", "propto", "to", "rightarrow", "leftarrow", "Rightarrow", "implies", "Leftarrow", "iff",
    "Leftrightarrow", "in", "notin", "subset", "cup", "cap"
)

/** Commands whose one argument is kept as plain text. */
private val TEXT_COMMANDS = setOf(
    "text", "textrm", "textnormal", "mathrm", "mathit", "mathsf", "mathtt", "operatorname", "mbox"
)

/** Commands whose one argument is kept, in bold. */
private val BOLD_COMMANDS = setOf("boxed", "textbf", "mathbf", "bm")

private val SUPERSCRIPTS = "0123456789+-=()n".zip("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ").toMap()
private val SUBSCRIPTS = "0123456789+-=()".zip("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎").toMap()

private fun convertCommands(s: String, math: Boolean): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\\' && i + 1 < s.length && (math || !afterWord(s, i))) {
            val next = s[i + 1]
            if (next.isLetter()) {
                var j = i + 1
                while (j < s.length && s[j].isLetter()) j++
                val name = s.substring(i + 1, j)
                val consumed = command(name, s, j, math, out)
                if (consumed >= 0) {
                    i = consumed
                    continue
                }
                out.append(s, i, j)
                i = j
                // Unknown, so its arguments are copied as written: `\overbrace{x}`, not `\overbracex`.
                if (math) {
                    while (i < s.length && s[i] == '{') {
                        val (_, after) = argument(s, i)
                        if (after == i) break
                        out.append(s, i, after)
                        i = after
                    }
                }
                continue
            }
            if (next == '%' || next == '$') {
                out.append(next)
                i += 2
                continue
            }
            // Spacing and escapes only mean this inside math; in prose `\#` and `\_` are Markdown.
            if (math) when (next) {
                ',', ';', ':', ' ' -> { out.append(' '); i += 2; continue }
                '!' -> { i += 2; continue }
                '#', '&', '_', '{', '}' -> { out.append(next); i += 2; continue }
                '\\' -> { out.append('\n'); i += 2; continue }
            }
        }
        if (math) {
            when (ch) {
                '{', '}' -> { i++; continue }
                '&' -> { i++; continue }
                '^', '_' -> {
                    val (arg, after) = argument(s, i + 1)
                    if (arg != null) {
                        val inner = convertCommands(arg, math = true)
                        val glyphs = if (ch == '^') SUPERSCRIPTS else SUBSCRIPTS
                        when {
                            ch == '^' && (inner == "∘" || inner == "°") -> out.append('°')
                            inner.isNotEmpty() && inner.all { it in glyphs } ->
                                inner.forEach { out.append(glyphs.getValue(it)) }
                            else -> out.append(ch).append(if (inner.length == 1) inner else "($inner)")
                        }
                        i = after
                        continue
                    }
                }
            }
        }
        out.append(ch)
        i++
    }
    return if (math) {
        out.lines().joinToString("\n") { it.trim().replace(Regex(" {2,}"), " ") }.trim()
    } else {
        out.toString()
    }
}

/**
 * A backslash glued to a word, a drive letter or another separator (`me\int`, `C:\pi`, `a/\b`) is
 * a path, not LaTeX, which is written `84 \times 3`, `3\times 4` or `(\frac`.
 */
private fun afterWord(s: String, i: Int): Boolean {
    if (i == 0) return false
    val before = s[i - 1]
    return before.isLetter() || before in ":\\/._~"
}

/**
 * Handle `\name` whose name ended at [at]. Appends the result to [out] and returns the index to
 * carry on from, or -1 for a command this does not know (the caller copies it through).
 */
private fun command(name: String, s: String, at: Int, math: Boolean, out: StringBuilder): Int {
    SYMBOLS[name]?.let { symbol ->
        // `\frac{84}{7}\times 3` is spaced by TeX, not by the writer: give the operator its spaces.
        if (math && name in SPACED) out.append(' ').append(symbol).append(' ') else out.append(symbol)
        return at
    }
    when (name) {
        "frac", "dfrac", "tfrac", "cfrac" -> {
            val (top, afterTop) = argument(s, at)
            val (bottom, afterBottom) = if (top != null) argument(s, afterTop) else (null to at)
            if (top == null || bottom == null) return -1
            out.append(group(convertCommands(top, math = true)))
                .append('/')
                .append(group(convertCommands(bottom, math = true)))
            return afterBottom
        }
        "sqrt" -> {
            var from = at
            var index: String? = null
            if (from < s.length && s[from] == '[') {
                val close = s.indexOf(']', from)
                if (close < 0) return -1
                index = s.substring(from + 1, close).trim()
                from = close + 1
            }
            val (arg, after) = argument(s, from)
            if (arg == null) return -1
            val root = when (index) { null, "2" -> "√"; "3" -> "∛"; "4" -> "∜"; else -> return -1 }
            out.append(root).append(group(convertCommands(arg, math = true)))
            return after
        }
        "begin", "end" -> {
            // \begin{aligned} … \end{aligned}: the rows survive as lines, the markers go.
            val (_, after) = argument(s, at)
            return if (math) after else -1
        }
    }
    if (name in TEXT_COMMANDS || name in BOLD_COMMANDS) {
        val (arg, after) = argument(s, at)
        if (arg == null) return -1
        val inner = if (name in TEXT_COMMANDS && name.startsWith("text")) {
            convertCommands(arg, math = false)
        } else {
            convertCommands(arg, math = true)
        }
        // Already inside `**…**` (models write `**\boxed{36}**`): a second pair would print stars.
        if (name in BOLD_COMMANDS && !out.endsWith("**")) out.append("**").append(inner.trim()).append("**")
        else out.append(inner)
        return after
    }
    return -1
}

/**
 * The argument starting at [from] (after optional spaces): a `{…}` group with nested braces, or a
 * single character. Returns (argument, index after it), or (null, from) when there is none.
 */
private fun argument(s: String, from: Int): Pair<String?, Int> {
    var i = from
    while (i < s.length && s[i] == ' ') i++
    if (i >= s.length) return null to from
    if (s[i] != '{') {
        if (s[i] == '\\' && i + 1 < s.length && s[i + 1].isLetter()) {
            var j = i + 1
            while (j < s.length && s[j].isLetter()) j++
            return s.substring(i, j) to j
        }
        return if (s[i].isLetterOrDigit()) s[i].toString() to i + 1 else null to from
    }
    var depth = 0
    var j = i
    while (j < s.length) {
        when (s[j]) {
            '\\' -> j++
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return s.substring(i + 1, j) to j + 1
            }
        }
        j++
    }
    return null to from
}

/** `3` stays `3`; `84 × 3` becomes `(84 × 3)`, so a fraction or root still reads unambiguously. */
private fun group(expr: String): String {
    val t = expr.trim()
    val simple = t.isNotEmpty() && t.all { it.isLetterOrDigit() || it == '.' || it == ',' }
    val wrapped = t.length >= 2 && t.first() == '(' && t.last() == ')'
    return if (simple || wrapped) t else "($t)"
}
