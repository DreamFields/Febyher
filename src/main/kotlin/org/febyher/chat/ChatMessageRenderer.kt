package org.febyher.chat

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 聊天消息渲染：Markdown/纯文本转 HTML、角色样式
 * 供 ChatPanel 消息气泡与流式内容使用
 */
object ChatMessageRenderer {

    @JvmStatic
    fun getMessageBgColor(role: MessageRole): JBColor = when (role) {
        MessageRole.USER -> JBColor(0xE3F2FD, 0x1E3A5F)
        MessageRole.ASSISTANT -> JBColor.namedColor("Panel.background", Color.WHITE)
        MessageRole.SYSTEM -> JBColor(0xFFF8E1, 0x4A3728)
    }

    @JvmStatic
    fun getMessageAccentColor(role: MessageRole): JBColor = when (role) {
        MessageRole.USER -> JBColor(0x1976D2, 0x64B5F6)
        MessageRole.ASSISTANT -> JBColor(0x388E3C, 0x81C784)
        MessageRole.SYSTEM -> JBColor(0xF57C00, 0xFFB74D)
    }

    @JvmStatic
    fun getMessageBorderColor(role: MessageRole): JBColor = when (role) {
        MessageRole.USER -> JBColor(0x90CAF9, 0x1565C0)
        MessageRole.ASSISTANT -> JBColor(0xE0E0E0, 0x555555)
        MessageRole.SYSTEM -> JBColor(0xFFE082, 0x6D4C41)
    }

    @JvmStatic
    fun getRoleDisplayName(role: MessageRole): String = when (role) {
        MessageRole.USER -> "用户"
        MessageRole.ASSISTANT -> "AI助手"
        MessageRole.SYSTEM -> "系统"
    }

    /**
     * Markdown 转 HTML（代码块 + 行内格式）
     */
    @JvmStatic
    fun convertMarkdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        var lastIndex = 0
        val codeBlockPattern = "```(\\w+)?\\n(.*?)\\n```".toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in codeBlockPattern.findAll(markdown)) {
            val beforeCode = markdown.substring(lastIndex, match.range.first)
            sb.append(processInlineMarkdown(beforeCode))
            val code = match.groupValues[2]
            val escapedCode = escapeHtmlForCode(code)
            sb.append("<pre style='background-color:#2D2D2D;color:#E0E0E0;padding:12px;margin-top:8px;margin-bottom:8px;font-family:Consolas,Monaco,monospace;font-size:12px;'><code>")
            sb.append(escapedCode)
            sb.append("</code></pre>")
            lastIndex = match.range.last + 1
        }
        if (lastIndex < markdown.length) {
            sb.append(processInlineMarkdown(markdown.substring(lastIndex)))
        }
        var html = sb.toString()
        if (html.isEmpty()) html = processInlineMarkdown(markdown)
        val textColor = if (JBColor.isBright()) "#000000" else "#E0E0E0"
        return "<html><body style='font-family:serif;line-height:1.6;font-size:13px;margin:0;padding:0;color:$textColor;'>$html</body></html>"
    }

    /**
     * 流式阶段使用的轻量 HTML（纯文本转 HTML，避免频繁正则）
     */
    @JvmStatic
    fun convertPlainTextToHtml(text: String): String {
        val escaped = escapeHtmlBasic(text).replace("\n", "<br>")
        val textColor = if (JBColor.isBright()) "#000000" else "#E0E0E0"
        return "<html><body style='font-family:serif;line-height:1.6;font-size:13px;margin:0;padding:0;color:$textColor;'>$escaped</body></html>"
    }

    private fun processInlineMarkdown(text: String): String {
        var html = text
        html = escapeHtmlBasic(html)
        html = processInlineCode(html)
        html = html.replace("\\*\\*(.+?)\\*\\*".toRegex(), "<b>$1</b>")
        html = html.replace("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)".toRegex(), "<i>$1</i>")
        html = html.replace("\n", "<br>")
        return html
    }

    private fun escapeHtmlBasic(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun processInlineCode(text: String): String {
        val sb = StringBuilder()
        var inCode = false
        var codeStart = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                if (!inCode) {
                    sb.append(text.substring(codeStart, i))
                    inCode = true
                    codeStart = i + 1
                } else {
                    val code = text.substring(codeStart, i)
                    sb.append("<code style='background-color:#2D2D2D;color:#E0E0E0;padding:3px 6px;font-family:Consolas,Monaco,monospace;font-size:12px;'>")
                    sb.append(escapeHtmlBasic(code))
                    sb.append("</code>")
                    inCode = false
                    codeStart = i + 1
                }
            }
            i++
        }
        if (codeStart < text.length) sb.append(text.substring(codeStart))
        return sb.toString()
    }

    private fun escapeHtmlForCode(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
