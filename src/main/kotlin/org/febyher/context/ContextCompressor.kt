package org.febyher.context

enum class ContextCompressionMode {
    LIGHT,
    BALANCED,
    AGGRESSIVE
}

object ContextCompressor {

    fun compress(
        text: String,
        language: String,
        mode: ContextCompressionMode = ContextCompressionMode.BALANCED
    ): String {
        if (text.isBlank()) return text

        val config = when (mode) {
            ContextCompressionMode.LIGHT -> CompressionConfig(
                maxChars = 14_000,
                maxLines = 320,
                maxLineLength = 260,
                removeComments = false
            )
            ContextCompressionMode.BALANCED -> CompressionConfig(
                maxChars = 10_000,
                maxLines = 220,
                maxLineLength = 220,
                removeComments = true
            )
            ContextCompressionMode.AGGRESSIVE -> CompressionConfig(
                maxChars = 6_000,
                maxLines = 140,
                maxLineLength = 180,
                removeComments = true
            )
        }

        var normalized = normalize(text)
        if (config.removeComments) {
            normalized = removeCommentOnlyLines(normalized, language)
        }
        normalized = trimTrailingSpaces(normalized)
        normalized = collapseBlankLines(normalized)
        normalized = truncateLongLines(normalized, config.maxLineLength)
        normalized = keepHeadTailLines(normalized, config.maxLines)
        normalized = keepHeadTailChars(normalized, config.maxChars)
        return normalized.trim()
    }

    fun compactForPrompt(text: String, maxChars: Int = 2_000): String {
        if (text.length <= maxChars) return text
        return keepHeadTailChars(normalize(text), maxChars)
    }

    fun excerptAroundLine(
        text: String,
        line: Int,
        radius: Int = 30,
        maxChars: Int = 2_000
    ): String {
        if (text.isBlank()) return text
        val lines = normalize(text).lines()
        if (lines.isEmpty()) return ""

        val center = (line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - radius).coerceAtLeast(0)
        val end = (center + radius).coerceAtMost(lines.lastIndex)
        val excerpt = lines.subList(start, end + 1).joinToString("\n")
        return compactForPrompt(excerpt, maxChars)
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun trimTrailingSpaces(text: String): String =
        text.lines().joinToString("\n") { it.trimEnd() }

    private fun collapseBlankLines(text: String): String {
        val sb = StringBuilder()
        var blankCount = 0
        for (line in text.lines()) {
            if (line.isBlank()) {
                blankCount++
                if (blankCount > 1) continue
            } else {
                blankCount = 0
            }
            sb.appendLine(line)
        }
        return sb.toString()
    }

    private fun truncateLongLines(text: String, maxLen: Int): String {
        return text.lines().joinToString("\n") { line ->
            if (line.length <= maxLen) line else line.take(maxLen) + "  // ...truncated"
        }
    }

    private fun keepHeadTailLines(text: String, maxLines: Int): String {
        val lines = text.lines()
        if (lines.size <= maxLines) return text

        val headCount = maxLines / 2
        val tailCount = maxLines - headCount
        val head = lines.take(headCount)
        val tail = lines.takeLast(tailCount)
        return (head + "/* ...context lines omitted... */" + tail).joinToString("\n")
    }

    private fun keepHeadTailChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val headCount = maxChars / 2
        val tailCount = maxChars - headCount
        return buildString(maxChars + 48) {
            append(text.take(headCount))
            append("\n/* ...context truncated... */\n")
            append(text.takeLast(tailCount))
        }
    }

    private fun removeCommentOnlyLines(text: String, language: String): String {
        val lang = language.lowercase()
        val lineCommentPrefixes = when (lang) {
            "kotlin", "kts", "java", "javascript", "typescript", "tsx", "go", "rust", "cpp", "c", "header", "scss", "css" ->
                listOf("//")
            "python", "yaml", "yml", "bash", "sh", "properties" ->
                listOf("#")
            "sql" -> listOf("--")
            else -> emptyList()
        }

        val blockCommentStripped = if (
            lang in setOf("kotlin", "kts", "java", "javascript", "typescript", "tsx", "go", "rust", "cpp", "c", "header", "css", "scss")
        ) {
            text.replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        } else {
            text
        }

        if (lineCommentPrefixes.isEmpty()) return blockCommentStripped

        return blockCommentStripped.lines().filter { raw ->
            val line = raw.trimStart()
            line.isNotEmpty() && lineCommentPrefixes.none { prefix -> line.startsWith(prefix) }
        }.joinToString("\n")
    }

    private data class CompressionConfig(
        val maxChars: Int,
        val maxLines: Int,
        val maxLineLength: Int,
        val removeComments: Boolean
    )
}
