package org.febyher.llm

import com.intellij.openapi.diagnostic.Logger
import org.febyher.chat.LLMMessage
import org.febyher.context.CodeContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * LLM 服务抽象基类 - 封装通用 HTTP/SSE 请求与解析逻辑
 */
abstract class BaseLLMService(protected val config: ProviderConfig) : LLMService {

    protected val logger = Logger.getInstance(this::class.java)

    protected abstract fun getSystemPrompt(): String
    protected abstract fun getProviderName(): String
    protected open fun getExtraRequestParams(): Map<String, Any> = emptyMap()

    override fun chat(messages: List<LLMMessage>): String {
        if (config.apiKey.isBlank()) {
            return "API Key未配置\n\n请前往 Settings > Tools > Febyher AI 配置你的 API Key。"
        }
        return try {
            callAPI(messages, stream = false)
        } catch (e: Exception) {
            logger.error("API request failed", e)
            "请求失败: ${e.message}\n\n请检查网络连接和API Key是否有效。"
        }
    }

    override fun chatWithContext(userMessage: String, context: CodeContext?): String {
        val messages = buildMessagesWithContext(userMessage, context)
        return chat(messages)
    }

    override fun chatStream(messages: List<LLMMessage>, callback: StreamCallback) {
        if (config.apiKey.isBlank()) {
            callback.onError("API Key未配置\n\n请前往 Settings > Tools > Febyher AI 配置你的 API Key。")
            return
        }
        try {
            callAPIStream(messages, callback)
        } catch (e: Exception) {
            logger.error("Stream API request failed", e)
            callback.onError("请求失败: ${e.message}\n\n请检查网络连接和API Key是否有效。")
        }
    }

    override fun chatStreamWithContext(userMessage: String, context: CodeContext?, callback: StreamCallback) {
        chatStream(buildMessagesWithContext(userMessage, context), callback)
    }

    protected open fun buildSystemPrompt(context: CodeContext?): String = getSystemPrompt()

    private fun buildMessagesWithContext(userMessage: String, context: CodeContext?): List<LLMMessage> {
        val messages = mutableListOf<LLMMessage>()
        messages.add(LLMMessage("system", buildSystemPrompt(context)))
        val userMessageParts = mutableListOf<String>()
        context?.let {
            val contextPrompt = buildSafeContextPrompt(it)
            if (contextPrompt.isNotBlank()) {
                userMessageParts.add(contextPrompt)
                userMessageParts.add("")
            }
        }
        userMessageParts.add("### 用户问题")
        userMessageParts.add(userMessage)
        messages.add(LLMMessage("user", userMessageParts.joinToString("\n")))
        return messages
    }

    private fun buildSafeContextPrompt(context: CodeContext): String {
        val parts = mutableListOf<String>()
        parts.add("### 当前代码上下文")
        context.fileName?.let { parts.add("- 当前文件: ${sanitizeString(it)}") }
        context.language?.let { parts.add("- 语言: ${sanitizeString(it)}") }
        context.caretLine?.let { parts.add("- 光标位置: 第 $it 行") }
        if (!context.selectedCode.isNullOrBlank()) {
            parts.add("")
            parts.add("### 选中的代码")
            parts.add("```${context.language ?: ""}")
            val maxCodeLength = 2000
            val code = if (context.selectedCode.length > maxCodeLength) {
                context.selectedCode.take(maxCodeLength) + "\n... (代码已截断)"
            } else context.selectedCode
            parts.add(sanitizeString(code))
            parts.add("```")
        }
        return parts.joinToString("\n")
    }

    private fun sanitizeString(str: String): String = str.map { c -> if (c.isISOControl()) ' ' else c }.joinToString("")

    private fun callAPI(messages: List<LLMMessage>, stream: Boolean): String {
        val connection = URI(config.apiUrl).toURL().openConnection() as HttpURLConnection
        try {
            setupConnection(connection, stream)
            sendRequest(connection, messages, stream)
            val responseCode = connection.responseCode
            logger.info("[${getProviderName()}] API response code: $responseCode")
            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            } else {
                val err = connection.errorStream ?: connection.inputStream
                BufferedReader(InputStreamReader(err, StandardCharsets.UTF_8)).use { it.readText() }
            }
            return if (responseCode == HttpURLConnection.HTTP_OK) {
                parseResponse(response)
            } else {
                val errorMsg = parseErrorResponse(response)
                logger.error("[${getProviderName()}] API Error (HTTP $responseCode): $errorMsg")
                "API错误 (HTTP $responseCode): $errorMsg"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun callAPIStream(messages: List<LLMMessage>, callback: StreamCallback) {
        val connection = URI(config.apiUrl).toURL().openConnection() as HttpURLConnection
        try {
            setupConnection(connection, stream = true)
            sendRequest(connection, messages, stream = true)
            val responseCode = connection.responseCode
            logger.info("[${getProviderName()}] Stream API response code: $responseCode")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val err = connection.errorStream ?: connection.inputStream
                val errorResponse = BufferedReader(InputStreamReader(err, StandardCharsets.UTF_8)).use { it.readText() }
                callback.onError("API错误 (HTTP $responseCode): ${parseErrorResponse(errorResponse)}")
                return
            }
            val fullResponse = StringBuilder()
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lineText = line!!
                    if (lineText.isBlank()) continue
                    if (lineText.startsWith("data: ")) {
                        val data = lineText.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        val delta = parseStreamDelta(data)
                        if (delta.isNotEmpty()) {
                            fullResponse.append(delta)
                            callback.onDelta(delta)
                        }
                    }
                }
            }
            callback.onComplete(fullResponse.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun setupConnection(connection: HttpURLConnection, stream: Boolean) {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.useCaches = false
        connection.connectTimeout = 60000
        connection.readTimeout = 180000
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
    }

    private fun sendRequest(connection: HttpURLConnection, messages: List<LLMMessage>, stream: Boolean) {
        val requestBody = buildRequestBody(messages, stream)
        logger.info("[${getProviderName()}] Request body length: ${requestBody.length}")
        connection.outputStream.use {
            it.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            it.flush()
        }
    }

    private fun buildRequestBody(messages: List<LLMMessage>, stream: Boolean): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"model\": \"").append(escapeJson(config.model)).append("\", ")
        sb.append("\"messages\": [")
        messages.forEachIndexed { index, message ->
            if (index > 0) sb.append(", ")
            sb.append("{")
            sb.append("\"role\": \"").append(escapeJson(message.role)).append("\", ")
            sb.append("\"content\": \"").append(escapeJson(message.content)).append("\"")
            sb.append("}")
        }
        sb.append("], ")
        sb.append("\"temperature\": ").append(config.temperature).append(", ")
        sb.append("\"max_tokens\": ").append(config.maxTokens)
        getExtraRequestParams().forEach { (key, value) ->
            sb.append(", \"").append(key).append("\": ")
            when (value) {
                is String -> sb.append("\"").append(value).append("\"")
                is Number, is Boolean -> sb.append(value)
                else -> sb.append("\"").append(value.toString()).append("\"")
            }
        }
        sb.append(", \"stream\": ").append(stream)
        sb.append("}")
        return sb.toString()
    }

    protected open fun parseStreamDelta(json: String): String {
        return try {
            val deltaIndex = json.indexOf(""""delta"""")
            if (deltaIndex == -1) return ""
            val contentIndex = json.indexOf(""""content"""", deltaIndex)
            if (contentIndex == -1) return ""
            val colonIndex = json.indexOf(':', contentIndex)
            if (colonIndex == -1) return ""
            val quoteStart = json.indexOf('"', colonIndex)
            if (quoteStart == -1) return ""
            val quoteEnd = findMatchingQuote(json, quoteStart + 1)
            if (quoteEnd == -1) return ""
            unescapeJsonString(json.substring(quoteStart + 1, quoteEnd))
        } catch (e: Exception) {
            logger.warn("Failed to parse stream delta: ${e.message}")
            ""
        }
    }

    private fun findMatchingQuote(json: String, start: Int): Int {
        var i = start
        while (i < json.length) {
            if (json[i] == '\\' && i + 1 < json.length) i += 2
            else if (json[i] == '"') return i
            else i++
        }
        return -1
    }

    private fun unescapeJsonString(str: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < str.length) {
            if (str[i] == '\\' && i + 1 < str.length) {
                when (str[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < str.length) {
                            try { sb.append(str.substring(i + 2, i + 6).toInt(16).toChar()) } catch (_: NumberFormatException) { sb.append("\\u${str.substring(i + 2, i + 6)}") }
                            i += 6
                        } else { sb.append(str[i + 1]); i += 2 }
                    }
                    else -> { sb.append(str[i + 1]); i += 2 }
                }
            } else {
                sb.append(str[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun parseResponse(json: String): String {
        return try {
            val choicesStart = json.indexOf(""""choices"""")
            if (choicesStart == -1) return "无法解析响应: 未找到choices字段"
            var contentStart = json.indexOf(""""content": """, choicesStart)
            if (contentStart == -1) contentStart = json.indexOf(""""content":""", choicesStart)
            if (contentStart == -1) return "无法解析响应: 未找到content字段"
            val valueStart = json.indexOf('"', contentStart + 10) + 1
            if (valueStart <= 0) return "无法解析响应: content格式错误"
            val sb = StringBuilder()
            var i = valueStart
            while (i < json.length) {
                val c = json[i]
                if (c == '\\' && i + 1 < json.length) {
                    when (json[i + 1]) {
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'u' -> {
                            if (i + 5 < json.length) {
                                try { sb.append(json.substring(i + 2, i + 6).toInt(16).toChar()) } catch (_: NumberFormatException) { sb.append("\\u${json.substring(i + 2, i + 6)}") }
                                i += 6
                            } else { sb.append(c); i++ }
                        }
                        else -> { sb.append(json[i + 1]); i += 2 }
                    }
                } else if (c == '"') break
                else { sb.append(c); i++ }
            }
            sb.toString()
        } catch (e: Exception) {
            logger.error("Failed to parse response: ${e.message}", e)
            "解析响应失败: ${e.message}\n\n原始响应:\n${json.take(500)}"
        }
    }

    protected open fun parseErrorResponse(json: String): String {
        return try {
            val errorIndex = json.indexOf(""""error"""")
            if (errorIndex != -1) {
                val msgIndex = json.indexOf(""""message"""", errorIndex)
                if (msgIndex != -1) {
                    val colonIndex = json.indexOf(':', msgIndex)
                    if (colonIndex != -1) {
                        val quoteStart = json.indexOf('"', colonIndex)
                        if (quoteStart != -1) {
                            val quoteEnd = json.indexOf('"', quoteStart + 1)
                            if (quoteEnd != -1) return json.substring(quoteStart + 1, quoteEnd)
                        }
                    }
                }
            }
            json.take(500)
        } catch (_: Exception) {
            json.take(500)
        }
    }

    protected open fun escapeJson(str: String): String {
        val sb = StringBuilder()
        for (char in str) {
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (char.code in 0..31 || char.code == 127) sb.append(String.format("\\u%04x", char.code))
                    else sb.append(char)
                }
            }
        }
        return sb.toString()
    }
}
