package app.maskan.chat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.maskan.chat.R

/**
 * Where a column's text sits. Markdown writes left and right (`:---`, `---:`); this stores them
 * as START and END and lets the layout direction decide which side that is, so a table written
 * by an English model reads correctly in an Arabic conversation instead of inside out.
 */
private enum class MdAlign { START, CENTER, END }

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Table(
        val header: List<String>,
        val alignments: List<MdAlign>,
        val rows: List<List<String>>
    ) : MdBlock()
    data class Header(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class UnorderedListItem(val text: String) : MdBlock()
    data class OrderedListItem(val number: String, val text: String) : MdBlock()
}

/**
 * `|---|:--:|---:|` - the row that turns the line above it into a table header.
 *
 * A pipe is required, not just dashes: `---` on its own is a horizontal rule, and a table
 * parser that swallows one would eat the paragraph above it.
 */
private val DELIMITER_ROW = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

private fun isDelimiterRow(line: String): Boolean =
    line.contains('|') && line.contains('-') && DELIMITER_ROW.matches(line)

/** A table row starts here: a line with a pipe, and a delimiter row directly under it. */
private fun startsTable(lines: List<String>, index: Int): Boolean =
    lines[index].contains('|') &&
        index + 1 < lines.size &&
        isDelimiterRow(lines[index + 1])

/**
 * Split one row into its cells. A pipe the writer escaped (`\\|`) is content, not a boundary -
 * which is how a table of shell commands or regexes survives being rendered.
 */
private fun splitRow(line: String): List<String> {
    val body = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < body.length) {
        val ch = body[i]
        when {
            ch == '\\' && i + 1 < body.length && body[i + 1] == '|' -> {
                current.append('|')
                i += 2
            }
            ch == '|' -> {
                cells.add(current.toString().trim())
                current.clear()
                i++
            }
            else -> {
                current.append(ch)
                i++
            }
        }
    }
    cells.add(current.toString().trim())
    return cells
}

private fun alignmentOf(cell: String): MdAlign {
    val spec = cell.trim()
    val left = spec.startsWith(":")
    val right = spec.endsWith(":")
    return when {
        left && right -> MdAlign.CENTER
        right -> MdAlign.END
        else -> MdAlign.START
    }
}

private fun parseBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        if (line.startsWith("```")) {
            val lang = line.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        if (startsTable(lines, i)) {
            val header = splitRow(line)
            val alignments = splitRow(lines[i + 1]).map { alignmentOf(it) }
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size && lines[j].isNotBlank() && lines[j].contains('|')) {
                rows.add(splitRow(lines[j]))
                j++
            }
            blocks.add(MdBlock.Table(header, alignments, rows))
            i = j
            continue
        }

        val headerMatch = Regex("^(#{1,3})\\s+(.+)").matchEntire(line)
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            blocks.add(MdBlock.Header(level, headerMatch.groupValues[2]))
            i++
            continue
        }

        val ulMatch = Regex("^\\s*[-*]\\s+(.+)").matchEntire(line)
        if (ulMatch != null) {
            blocks.add(MdBlock.UnorderedListItem(ulMatch.groupValues[1]))
            i++
            continue
        }

        val olMatch = Regex("^\\s*(\\d+)[.)]+\\s+(.+)").matchEntire(line)
        if (olMatch != null) {
            blocks.add(MdBlock.OrderedListItem(olMatch.groupValues[1], olMatch.groupValues[2]))
            i++
            continue
        }

        if (line.isBlank()) {
            i++
            continue
        }

        val paraLines = mutableListOf(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            if (next.isBlank() || next.startsWith("```") || next.matches(Regex("^#{1,3}\\s+.+"))
                || next.matches(Regex("^\\s*[-*]\\s+.+")) || next.matches(Regex("^\\s*\\d+[.)]+\\s+.+"))
                || startsTable(lines, i)
            ) break
            paraLines.add(next)
            i++
        }
        blocks.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
    }
    return blocks
}

fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = text.length

        while (i < len) {
            val ch = text[i]

            if (ch == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = androidx.compose.ui.graphics.Color(0x20808080)
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            if (ch == '*' || ch == '_') {
                if (i + 1 < len && text[i + 1] == ch) {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(parseInlineMarkdown(text.substring(i + 2, end)))
                        }
                        i = end + 2
                        continue
                    }
                } else {
                    val end = text.indexOf(ch, i + 1)
                    if (end > i + 1 && (end + 1 >= len || !text[end + 1].isLetterOrDigit())) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(parseInlineMarkdown(text.substring(i + 1, end)))
                        }
                        i = end + 1
                        continue
                    }
                }
            }

            append(ch)
            i++
        }
    }
}

@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseBlocks(latexForDisplay(text)) }

    Column {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MdBlock.Header -> {
                    if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = parseInlineMarkdown(block.text),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                is MdBlock.CodeBlock -> {
                    if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                    CodeBlockView(code = block.code, language = block.language)
                    if (index < blocks.size - 1) Spacer(modifier = Modifier.height(4.dp))
                }
                is MdBlock.Table -> {
                    if (index > 0) Spacer(modifier = Modifier.height(6.dp))
                    TableView(table = block)
                    if (index < blocks.size - 1) Spacer(modifier = Modifier.height(6.dp))
                }
                is MdBlock.UnorderedListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(16.dp)
                        )
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is MdBlock.OrderedListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * A pipe table, with every column exactly as wide as its widest cell.
 *
 * Done with a Layout rather than a Column of weighted Rows for two reasons. The table lives
 * inside a horizontalScroll, where the available width is infinite and weight() has nothing to
 * divide; and column widths have to agree across rows, which weights inside separate Rows
 * cannot do. Placing with placeRelative() then mirrors the whole table in Arabic at no cost -
 * column one is on the right, and `:---` means "the side the language starts on".
 *
 * Each cell is measured against a ceiling so one long sentence wraps inside its column instead
 * of producing a table three screens wide that has to be scrolled to read every row.
 */
@Composable
private fun TableView(table: MdBlock.Table) {
    val columns = table.header.size
    if (columns == 0) return
    val rows = listOf(table.header) + table.rows
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val maxCellWidth = with(density) { 220.dp.roundToPx() }
    val dividerHeight = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)

    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEachIndexed { rowIndex, row ->
                    repeat(columns) { column ->
                        Text(
                            text = parseInlineMarkdown(row.getOrNull(column).orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else null,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                // The rule under the header. A child of its own, measured AFTER the cells, so
                // it can be told the table's finished width - nothing is measured twice.
                Box(modifier = Modifier.background(dividerColor))
            }
        ) { measurables, _ ->
            val cells = measurables.dropLast(1).map { it.measure(Constraints(maxWidth = maxCellWidth)) }
            val columnWidths = IntArray(columns)
            val rowHeights = IntArray(rows.size)
            cells.forEachIndexed { index, placeable ->
                val column = index % columns
                val row = index / columns
                columnWidths[column] = maxOf(columnWidths[column], placeable.width)
                rowHeights[row] = maxOf(rowHeights[row], placeable.height)
            }

            val columnX = IntArray(columns)
            var x = 0
            for (column in 0 until columns) {
                columnX[column] = x
                x += columnWidths[column]
            }
            val totalWidth = x
            val rowY = IntArray(rows.size)
            var y = 0
            for (row in rows.indices) {
                rowY[row] = y
                y += rowHeights[row]
            }
            val totalHeight = y

            val divider = measurables.last()
                .measure(Constraints.fixed(totalWidth.coerceAtLeast(1), dividerHeight))

            layout(totalWidth, totalHeight) {
                cells.forEachIndexed { index, placeable ->
                    val column = index % columns
                    val row = index / columns
                    val slack = columnWidths[column] - placeable.width
                    val offset = when (table.alignments.getOrNull(column) ?: MdAlign.START) {
                        MdAlign.START -> 0
                        MdAlign.CENTER -> slack / 2
                        MdAlign.END -> slack
                    }
                    placeable.placeRelative(columnX[column] + offset, rowY[row])
                }
                divider.placeRelative(0, (rowHeights[0] - dividerHeight).coerceAtLeast(0))
            }
        }
    }
}

@Composable
private fun CodeBlockView(code: String, language: String) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.code_copied)
    val copyText = stringResource(R.string.copy_code)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (language.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(
                            text = copyText,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, top = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(
                            text = copyText,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                )
            }
        }
    }
}
