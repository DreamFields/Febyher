package org.febyher.llm

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import org.febyher.chat.LLMMessage
import org.febyher.context.CodeContext
import org.febyher.settings.AIProvider
import org.febyher.settings.CopilotSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

/**
 * 流式响应回调接口
 */
interface StreamCallback {
    /**
     * 收到增量内容时调用
     */
    fun onDelta(delta: String)
    
    /**
     * 流式响应完成时调用
     */
    fun onComplete(fullResponse: String)
    
    /**
     * 发生错误时调用
     */
    fun onError(error: String)
}

/**
 * LLM服务接口
 */
interface LLMService {
    /**
     * 同步聊天（非流式）
     */
    fun chat(messages: List<LLMMessage>): String
    
    /**
     * 带上下文的同步聊天
     */
    fun chatWithContext(userMessage: String, context: CodeContext?): String
    
    /**
     * 流式聊天
     */
    fun chatStream(messages: List<LLMMessage>, callback: StreamCallback)
    
    /**
     * 带上下文的流式聊天
     */
    fun chatStreamWithContext(userMessage: String, context: CodeContext?, callback: StreamCallback)
}

/**
 * Provider配置信息
 */
data class ProviderConfig(
    val apiKey: String,
    val apiUrl: String,
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096
)

/**
 * LLM服务抽象基类 - 封装通用的HTTP请求逻辑
 */
abstract class BaseLLMService(protected val config: ProviderConfig) : LLMService {
    
    protected val logger = Logger.getInstance(this::class.java)
    
    // 子类需要实现的抽象方法
    protected abstract fun getSystemPrompt(): String
    protected abstract fun getProviderName(): String
    
    // 可选覆盖：自定义请求参数
    protected open fun getExtraRequestParams(): Map<String, Any> = emptyMap()
    
    // ========== 同步方法 ==========
    
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
    
    // ========== 流式方法 ==========
    
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
        val messages = buildMessagesWithContext(userMessage, context)
        chatStream(messages, callback)
    }
    
    // ========== 内部方法 ==========
    
    protected open fun buildSystemPrompt(context: CodeContext?): String {
        return getSystemPrompt()
    }
    
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
            } else {
                context.selectedCode
            }
            parts.add(sanitizeString(code))
            parts.add("```")
        }
        
        return parts.joinToString("\n")
    }
    
    private fun sanitizeString(str: String): String {
        return str.map { char ->
            when {
                char.isISOControl() -> ' '
                else -> char
            }
        }.joinToString("")
    }
    
    /**
     * 同步API调用
     */
    private fun callAPI(messages: List<LLMMessage>, stream: Boolean): String {
        val url = URI(config.apiUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            setupConnection(connection, stream)
            sendRequest(connection, messages, stream)
            
            val responseCode = connection.responseCode
            logger.info("[${getProviderName()}] API response code: $responseCode")
            
            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                BufferedReader(InputStreamReader(errorStream, StandardCharsets.UTF_8)).use { it.readText() }
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
    
    /**
     * 流式API调用
     */
    private fun callAPIStream(messages: List<LLMMessage>, callback: StreamCallback) {
        val url = URI(config.apiUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            setupConnection(connection, stream = true)
            sendRequest(connection, messages, stream = true)
            
            val responseCode = connection.responseCode
            logger.info("[${getProviderName()}] Stream API response code: $responseCode")
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errorResponse = BufferedReader(InputStreamReader(errorStream, StandardCharsets.UTF_8)).use { it.readText() }
                val errorMsg = parseErrorResponse(errorResponse)
                callback.onError("API错误 (HTTP $responseCode): $errorMsg")
                return
            }
            
            // 处理SSE流
            val fullResponse = StringBuilder()
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lineText = line!!
                    
                    // 跳过空行
                    if (lineText.isBlank()) continue
                    
                    // 处理 SSE 数据行
                    if (lineText.startsWith("data: ")) {
                        val data = lineText.removePrefix("data: ").trim()
                        
                        // 检查结束标记
                        if (data == "[DONE]") {
                            break
                        }
                        
                        // 解析增量内容
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
    
    /**
     * 设置HTTP连接
     */
    private fun setupConnection(connection: HttpURLConnection, stream: Boolean) {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.useCaches = false
        connection.connectTimeout = 60000
        connection.readTimeout = 180000  // 流式需要更长超时
        
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
    }
    
    /**
     * 发送请求
     */
    private fun sendRequest(connection: HttpURLConnection, messages: List<LLMMessage>, stream: Boolean) {
        val requestBody = buildRequestBody(messages, stream)
        logger.info("[${getProviderName()}] Request body length: ${requestBody.length}")
        
        connection.outputStream.use { outputStream ->
            outputStream.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            outputStream.flush()
        }
    }
    
    /**
     * 构建JSON请求体
     */
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
        
        // 添加额外的请求参数
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
    
    /**
     * 解析流式增量内容
     * SSE格式: data: {"choices":[{"delta":{"content":"xxx"}}]}
     */
    protected open fun parseStreamDelta(json: String): String {
        return try {
            // 查找 delta.content
            val deltaIndex = json.indexOf(""""delta"""")
            if (deltaIndex == -1) return ""
            
            val contentIndex = json.indexOf(""""content"""", deltaIndex)
            if (contentIndex == -1) return ""
            
            // 找到冒号后的值
            val colonIndex = json.indexOf(':', contentIndex)
            if (colonIndex == -1) return ""
            
            // 查找引号包围的内容
            val quoteStart = json.indexOf('"', colonIndex)
            if (quoteStart == -1) return ""
            
            val quoteEnd = findMatchingQuote(json, quoteStart + 1)
            if (quoteEnd == -1) return ""
            
            val rawContent = json.substring(quoteStart + 1, quoteEnd)
            unescapeJsonString(rawContent)
        } catch (e: Exception) {
            logger.warn("Failed to parse stream delta: ${e.message}")
            ""
        }
    }
    
    /**
     * 找到匹配的结束引号（处理转义）
     */
    private fun findMatchingQuote(json: String, start: Int): Int {
        var i = start
        while (i < json.length) {
            if (json[i] == '\\' && i + 1 < json.length) {
                i += 2  // 跳过转义字符
            } else if (json[i] == '"') {
                return i
            } else {
                i++
            }
        }
        return -1
    }
    
    /**
     * 反转义JSON字符串
     */
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
                            val hex = str.substring(i + 2, i + 6)
                            try { sb.append(hex.toInt(16).toChar()) } catch (e: NumberFormatException) { sb.append("\\u$hex") }
                            i += 6
                        } else { sb.append(str[i]); i++ }
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
    
    /**
     * 解析API响应
     */
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
                                val hex = json.substring(i + 2, i + 6)
                                try { sb.append(hex.toInt(16).toChar()) } catch (e: NumberFormatException) { sb.append("\\u$hex") }
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
        } catch (e: Exception) {
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
                    if (char.code in 0..31 || char.code == 127) {
                        sb.append(String.format("\\u%04x", char.code))
                    } else {
                        sb.append(char)
                    }
                }
            }
        }
        return sb.toString()
    }
}

/**
 * Moonshot (Kimi) LLM服务实现
 */
class MoonshotLLMService(config: ProviderConfig) : BaseLLMService(config) {
    
    override fun getProviderName(): String = "Moonshot"
    
    override fun getSystemPrompt(): String {
        return "你是Kimi，由月之暗面科技有限公司开发的专业AI编程助手。" +
               "你可以帮助用户：解释代码的工作原理、帮助调试和修复bug、提供代码优化建议、" +
               "生成高质量的代码片段、回答编程相关的问题。" +
               "回复时使用Markdown格式，代码块使用对应的语言标记。保持回答简洁、准确、有帮助。"
    }
}

/**
 * DeepSeek LLM服务实现
 */
class DeepSeekLLMService(config: ProviderConfig) : BaseLLMService(config) {
    
    override fun getProviderName(): String = "DeepSeek"
    
    override fun getSystemPrompt(): String {
        return "你是DeepSeek AI，由DeepSeek公司开发的专业编程助手。" +
               "你可以帮助用户：解释代码的工作原理、帮助调试和修复bug、提供代码优化建议、" +
               "生成高质量的代码片段、回答编程相关的问题。" +
               "回复时使用Markdown格式，代码块使用对应的语言标记。保持回答简洁、准确、有帮助。"
    }
}

/**
 * NVIDIA NIM LLM服务实现 - 带完整诊断日志
 */
class NvidiaLLMService(config: ProviderConfig) : BaseLLMService(config) {
    
    private val diagnosticLogger = LLMDiagnosticLogger
    
    override fun getProviderName(): String = "NVIDIA NIM"
    
    override fun getSystemPrompt(): String {
        return "你是一个专业的AI编程助手，通过NVIDIA NIM服务运行。" +
               "你可以帮助用户：解释代码的工作原理、帮助调试和修复bug、提供代码优化建议、" +
               "生成高质量的代码片段、回答编程相关的问题。" +
               "回复时使用Markdown格式，代码块使用对应的语言标记。保持回答简洁、准确、有帮助。"
    }
    
    override fun getExtraRequestParams(): Map<String, Any> {
        return mapOf(
            "top_p" to 1.0,
            "chat_template_kwargs" to mapOf<String, Any>("thinking" to true)
        )
    }
    
    // ========== 重写带诊断的方法 ==========
    
    override fun chat(messages: List<LLMMessage>): String {
        if (config.apiKey.isBlank()) {
            return "API Key未配置\n\n请前往 Settings > Tools > Febyher AI 配置你的 API Key。"
        }
        
        val context = diagnosticLogger.createContext(getProviderName(), config.model)
        var requestInfo: LLMDiagnosticLogger.RequestInfo? = null
        var responseInfo: LLMDiagnosticLogger.ResponseInfo? = null
        var rawResponse: String? = null
        var connectionStartTime: Long? = null
        var firstByteTime: Long? = null
        var exception: Throwable? = null
        var result = ""
        
        try {
            // 构建请求
            val requestBody = buildRequestBodyWithDiagnostics(messages, stream = false)
            val requestHeaders = diagnosticLogger.buildRequestHeaders(config.apiKey, stream = false)
            
            requestInfo = LLMDiagnosticLogger.RequestInfo(
                url = config.apiUrl,
                headers = requestHeaders,
                body = requestBody,
                bodySizeBytes = requestBody.toByteArray(Charsets.UTF_8).size,
                model = config.model,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                stream = false,
                extraParams = getExtraRequestParams()
            )
            
            // 发送请求
            connectionStartTime = System.currentTimeMillis()
            val connection = createConnection(stream = false)
            sendRequestBody(connection, requestBody)
            firstByteTime = System.currentTimeMillis()
            
            // 获取响应
            val responseCode = connection.responseCode
            val responseHeaders = diagnosticLogger.extractResponseHeaders(connection)
            
            val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                (connection.errorStream ?: connection.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            
            rawResponse = responseBody
            
            responseInfo = LLMDiagnosticLogger.ResponseInfo(
                httpStatus = responseCode,
                httpStatusText = getHttpStatusText(responseCode),
                headers = responseHeaders,
                body = responseBody,
                bodySizeBytes = responseBody.toByteArray(Charsets.UTF_8).size,
                contentType = responseHeaders["Content-Type"]?.firstOrNull(),
                isError = responseCode != HttpURLConnection.HTTP_OK
            )
            
            result = if (responseCode == HttpURLConnection.HTTP_OK) {
                parseResponseWithDiagnostics(responseBody)
            } else {
                "API错误 (HTTP $responseCode): ${parseErrorResponse(responseBody)}"
            }
            
            connection.disconnect()
            
        } catch (e: Exception) {
            exception = e
            logger.error("NVIDIA API request failed", e)
            result = "请求失败: ${e.message}\n\n请检查网络连接和API Key是否有效。"
        }
        
        // 生成并记录诊断报告
        val performance = diagnosticLogger.calculatePerformance(context, connectionStartTime, firstByteTime)
        val report = diagnosticLogger.generateReport(
            context, requestInfo!!, responseInfo, performance, exception, rawResponse, result
        )
        diagnosticLogger.logReport(report)
        
        // 如果结果是空的，进行诊断
        if (result.isBlank() || result.contains("API错误") || result.contains("请求失败")) {
            val issues = diagnosticLogger.diagnoseEmptyResponse(report)
            if (issues.isNotEmpty()) {
                logger.error("=== 空响应问题诊断 ===")
                issues.forEach { logger.error(" - $it") }
            }
        }
        
        return result
    }
    
    override fun chatStream(messages: List<LLMMessage>, callback: StreamCallback) {
        if (config.apiKey.isBlank()) {
            callback.onError("API Key未配置\n\n请前往 Settings > Tools > Febyher AI 配置你的 API Key。")
            return
        }
        
        val context = diagnosticLogger.createContext(getProviderName(), config.model)
        var requestInfo: LLMDiagnosticLogger.RequestInfo? = null
        var responseInfo: LLMDiagnosticLogger.ResponseInfo? = null
        var rawResponse = StringBuilder()
        var connectionStartTime: Long? = null
        var firstByteTime: Long? = null
        var exception: Throwable? = null
        val fullContent = StringBuilder()
        
        try {
            // 构建请求
            val requestBody = buildRequestBodyWithDiagnostics(messages, stream = true)
            val requestHeaders = diagnosticLogger.buildRequestHeaders(config.apiKey, stream = true)
            
            requestInfo = LLMDiagnosticLogger.RequestInfo(
                url = config.apiUrl,
                headers = requestHeaders,
                body = requestBody,
                bodySizeBytes = requestBody.toByteArray(Charsets.UTF_8).size,
                model = config.model,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                stream = true,
                extraParams = getExtraRequestParams()
            )
            
            // 发送请求
            connectionStartTime = System.currentTimeMillis()
            val connection = createConnection(stream = true)
            sendRequestBody(connection, requestBody)
            
            // 获取响应
            val responseCode = connection.responseCode
            firstByteTime = System.currentTimeMillis()
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                
                responseInfo = LLMDiagnosticLogger.ResponseInfo(
                    httpStatus = responseCode,
                    httpStatusText = getHttpStatusText(responseCode),
                    headers = diagnosticLogger.extractResponseHeaders(connection),
                    body = errorBody,
                    bodySizeBytes = errorBody.toByteArray(Charsets.UTF_8).size,
                    contentType = "application/json",
                    isError = true
                )
                
                callback.onError("API错误 (HTTP $responseCode): ${parseErrorResponse(errorBody)}")
                connection.disconnect()
                
                // 记录诊断报告
                val performance = diagnosticLogger.calculatePerformance(context, connectionStartTime, firstByteTime)
                val report = diagnosticLogger.generateReport(
                    context, requestInfo, responseInfo, performance, null, errorBody, null
                )
                diagnosticLogger.logReport(report)
                return
            }
            
            // 处理SSE流
            val responseHeaders = diagnosticLogger.extractResponseHeaders(connection)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lineText = line!!
                    
                    if (lineText.isBlank()) continue
                    
                    rawResponse.append(lineText).append("\n")
                    
                    if (lineText.startsWith("data: ")) {
                        val data = lineText.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") {
                            break
                        }
                        
                        val delta = parseStreamDelta(data)
                        if (delta.isNotEmpty()) {
                            fullContent.append(delta)
                            callback.onDelta(delta)
                        }
                    }
                }
            }
            
            responseInfo = LLMDiagnosticLogger.ResponseInfo(
                httpStatus = responseCode,
                httpStatusText = getHttpStatusText(responseCode),
                headers = responseHeaders,
                body = rawResponse.toString(),
                bodySizeBytes = rawResponse.toString().toByteArray(Charsets.UTF_8).size,
                contentType = responseHeaders["Content-Type"]?.firstOrNull(),
                isError = false
            )
            
            callback.onComplete(fullContent.toString())
            connection.disconnect()
            
        } catch (e: Exception) {
            exception = e
            logger.error("NVIDIA Stream API request failed", e)
            callback.onError("请求失败: ${e.message}\n\n请检查网络连接和API Key是否有效。")
        }
        
        // 生成并记录诊断报告
        val performance = diagnosticLogger.calculatePerformance(context, connectionStartTime, firstByteTime)
        val report = diagnosticLogger.generateReport(
            context, requestInfo!!, responseInfo, performance, exception, 
            rawResponse.toString(), fullContent.toString().ifBlank { null }
        )
        diagnosticLogger.logReport(report)
        
        // 如果内容是空的，进行诊断
        if (fullContent.isBlank()) {
            val issues = diagnosticLogger.diagnoseEmptyResponse(report)
            if (issues.isNotEmpty()) {
                logger.error("=== 空响应问题诊断 ===")
                issues.forEach { logger.error(" - $it") }
            }
        }
    }
    
    // ========== 辅助方法 ==========
    
    private fun createConnection(stream: Boolean): HttpURLConnection {
        val url = URI(config.apiUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.useCaches = false
        connection.connectTimeout = 60000
        connection.readTimeout = 180000
        
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        
        return connection
    }
    
    private fun sendRequestBody(connection: HttpURLConnection, body: String) {
        connection.outputStream.use { outputStream ->
            outputStream.write(body.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        }
    }
    
    private fun buildRequestBodyWithDiagnostics(messages: List<LLMMessage>, stream: Boolean): String {
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
        sb.append(", \"top_p\": 1.0")
        
        // NVIDIA特有参数
        sb.append(", \"chat_template_kwargs\": {\"thinking\": true}")
        
        sb.append(", \"stream\": ").append(stream)
        sb.append("}")
        
        return sb.toString()
    }
    
    private fun parseResponseWithDiagnostics(json: String): String {
        return try {
            val choicesStart = json.indexOf(""""choices"""")
            if (choicesStart == -1) {
                logger.warn("Response missing 'choices' field. Raw: ${json.take(500)}")
                return "无法解析响应: 未找到choices字段"
            }
            
            var contentStart = json.indexOf(""""content": """, choicesStart)
            if (contentStart == -1) contentStart = json.indexOf(""""content":""", choicesStart)
            if (contentStart == -1) {
                logger.warn("Response missing 'content' field. Raw: ${json.take(500)}")
                return "无法解析响应: 未找到content字段"
            }
            
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
                        'u' -> {
                            if (i + 5 < json.length) {
                                val hex = json.substring(i + 2, i + 6)
                                try { sb.append(hex.toInt(16).toChar()) } catch (e: NumberFormatException) { sb.append("\\u$hex") }
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
    
    private fun getHttpStatusText(code: Int): String {
        return when (code) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Unknown"
        }
    }
}

/**
 * LLM服务工厂 - 根据Provider类型创建对应的服务实例
 */
object LLMServiceFactory {
    
    private val logger = Logger.getInstance(LLMServiceFactory::class.java)
    
    /**
     * 注册的Provider映射 - 可扩展
     */
    private val providerRegistry = mutableMapOf<AIProvider, (ProviderConfig) -> BaseLLMService>(
        AIProvider.MOONSHOT to { config -> MoonshotLLMService(config) },
        AIProvider.DEEPSEEK to { config -> DeepSeekLLMService(config) },
        AIProvider.NVIDIA to { config -> NvidiaLLMService(config) }
    )
    
    /**
     * 注册新的Provider
     */
    fun registerProvider(provider: AIProvider, factory: (ProviderConfig) -> BaseLLMService) {
        providerRegistry[provider] = factory
        logger.info("Registered LLM provider: $provider")
    }
    
    /**
     * 创建LLM服务实例
     */
    fun create(provider: AIProvider, config: ProviderConfig): LLMService {
        val factory = providerRegistry[provider]
            ?: throw IllegalArgumentException("Unsupported provider: $provider")
        return factory(config)
    }
    
    /**
     * 获取支持的Provider列表
     */
    fun getSupportedProviders(): Set<AIProvider> = providerRegistry.keys.toSet()
}

/**
 * LLM服务管理器 - 管理服务实例的生命周期
 */
@Service(Service.Level.PROJECT)
class LLMServiceManager {
    
    private var currentService: LLMService? = null
    private var currentProvider: AIProvider? = null
    
    /**
     * 获取当前LLM服务（懒加载，配置变化时自动重建）
     */
    fun getService(): LLMService {
        val settings = CopilotSettings.getInstance()
        val provider = settings.currentProvider
        
        // 如果provider变化，重新创建服务
        if (currentService == null || currentProvider != provider) {
            val config = ProviderConfig(
                apiKey = settings.getEffectiveApiKey(),
                apiUrl = settings.getEffectiveApiUrl(),
                model = settings.getEffectiveModel(),
                temperature = settings.getEffectiveTemperature(),
                maxTokens = settings.maxTokens
            )
            currentService = LLMServiceFactory.create(provider, config)
            currentProvider = provider
        }
        
        return currentService!!
    }
    
    /**
     * 强制刷新服务（配置变更后调用）
     */
    fun refresh() {
        currentService = null
        currentProvider = null
    }
    
    companion object {
        fun getInstance(project: com.intellij.openapi.project.Project): LLMServiceManager {
            return project.getService(LLMServiceManager::class.java)
        }
    }
}

/**
 * 便捷访问点（向后兼容）
 */
object LLMServiceProvider {
    
    private var projectRef: com.intellij.openapi.project.Project? = null
    
    fun init(project: com.intellij.openapi.project.Project) {
        projectRef = project
    }
    
    fun getService(): LLMService {
        val project = projectRef 
            ?: throw IllegalStateException("LLMServiceProvider not initialized. Call init(project) first.")
        return getInstance(project).getService()
    }
    
    private fun getInstance(project: com.intellij.openapi.project.Project): LLMServiceManager {
        return LLMServiceManager.getInstance(project)
    }
}
