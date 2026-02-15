package com.example.mychat.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MarkdownBlock {
    data class TextBlock(val content: AnnotatedString, val style: TextStyle) : MarkdownBlock()
    data class Code(val code: String, val language: String? = null) : MarkdownBlock()
    data class ListItem(val content: AnnotatedString, val level: Int) : MarkdownBlock()
    object Divider : MarkdownBlock()
}

@Composable
fun MarkdownContent(text: String, contentColor: Color) {
    val typography = MaterialTheme.typography
    val blocks = parseMarkdownBlocks(text, typography)
    
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.TextBlock -> {
                    Text(
                        text = block.content,
                        color = contentColor,
                        style = block.style,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is MarkdownBlock.Code -> {
                    CodeBlock(block.code, block.language)
                }
                is MarkdownBlock.ListItem -> {
                    Row(modifier = Modifier.padding(start = (block.level * 16).dp, top = 2.dp, bottom = 2.dp)) {
                        Text("• ", color = contentColor, fontWeight = FontWeight.Bold)
                        Text(
                            text = block.content,
                            color = contentColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlock(code: String, language: String?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = Color(0xFF1E1E1E), 
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language?.uppercase() ?: "CODE",
                    color = Color(0xFF858585),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Text(
                text = highlightCode(code.trim()),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

fun highlightCode(code: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        
        val keywords = Regex("\\b(val|var|fun|class|interface|object|if|else|for|while|return|import|package|def|print|from|as|in|is|try|catch|finally|throw|String|Int|Boolean|Float|Long|Double|List|Map)\\b")
        val strings = Regex("\".*?\"|'.*?'")
        val comments = Regex("//.*|#.*")
        val numbers = Regex("\\b\\d+\\b")

        val allMatches = (keywords.findAll(code) + strings.findAll(code) + comments.findAll(code) + numbers.findAll(code))
            .sortedBy { it.range.first }
            .toList()

        for (match in allMatches) {
            if (match.range.first > cursor) {
                withStyle(style = SpanStyle(color = Color(0xFFD4D4D4))) {
                    append(code.substring(cursor, match.range.first))
                }
            }

            val style = when {
                match.value.startsWith("//") || match.value.startsWith("#") -> SpanStyle(color = Color(0xFF6A9955))
                match.value.startsWith("\"") || match.value.startsWith("'") -> SpanStyle(color = Color(0xFFCE9178))
                match.value.all { it.isDigit() } -> SpanStyle(color = Color(0xFFB5CEA8))
                else -> SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)
            }

            withStyle(style = style) {
                append(match.value)
            }
            cursor = match.range.last + 1
        }

        if (cursor < code.length) {
            withStyle(style = SpanStyle(color = Color(0xFFD4D4D4))) {
                append(code.substring(cursor))
            }
        }
    }
}

fun parseMarkdownBlocks(text: String, typography: Typography): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var inCodeBlock = false
    var currentCode = StringBuilder()
    var currentLanguage: String? = null
    
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        
        if (line.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.Code(currentCode.toString(), currentLanguage))
                currentCode = StringBuilder()
                inCodeBlock = false
            } else {
                inCodeBlock = true
                currentLanguage = line.removePrefix("```").trim()
            }
        } else if (inCodeBlock) {
            currentCode.append(line).append("\n")
        } else {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("# ") -> {
                    blocks.add(MarkdownBlock.TextBlock(parseInlineStyles(trimmed.substring(2)), typography.headlineLarge.copy(fontWeight = FontWeight.Black)))
                }
                trimmed.startsWith("## ") -> {
                    blocks.add(MarkdownBlock.TextBlock(parseInlineStyles(trimmed.substring(3)), typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold)))
                }
                trimmed.startsWith("### ") -> {
                    blocks.add(MarkdownBlock.TextBlock(parseInlineStyles(trimmed.substring(4)), typography.titleLarge.copy(fontWeight = FontWeight.Bold)))
                }
                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                    blocks.add(MarkdownBlock.ListItem(parseInlineStyles(trimmed.substring(2)), 0))
                }
                line.isNotBlank() -> {
                    blocks.add(MarkdownBlock.TextBlock(parseInlineStyles(line), typography.bodyLarge))
                }
                else -> {
                    blocks.add(MarkdownBlock.Divider)
                }
            }
        }
        i++
    }
    
    if (inCodeBlock) {
        blocks.add(MarkdownBlock.Code(currentCode.toString(), currentLanguage))
    }
    
    return blocks
}

fun parseInlineStyles(text: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
        val italicRegex = Regex("\\*(.*?)\\*")
        val codeRegex = Regex("`(.*?)`")
        
        val matches = (boldRegex.findAll(text) + italicRegex.findAll(text) + codeRegex.findAll(text))
            .sortedBy { it.range.first }
            .toList()

        for (match in matches) {
            // Skip if this match starts before the current cursor (overlapping)
            if (match.range.first < cursor) continue

            // Append text before match
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            
            val value = match.value
            when {
                value.startsWith("**") -> {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                }
                value.startsWith("*") -> {
                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(match.groupValues[1])
                    }
                }
                value.startsWith("`") -> {
                    withStyle(style = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.Black.copy(alpha = 0.15f),
                        color = Color(0xFFEF5350)
                    )) {
                        append(match.groupValues[1])
                    }
                }
            }
            cursor = match.range.last + 1
        }
        
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
