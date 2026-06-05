package app.maskan.chat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.maskan.chat.R

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Header(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class UnorderedListItem(val text: String) : MdBlock()
    data class OrderedListItem(val number: String, val text: String) : MdBlock()
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
    val blocks = remember(text) { parseBlocks(text) }

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
