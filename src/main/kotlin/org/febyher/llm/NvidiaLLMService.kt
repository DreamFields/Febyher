package org.febyher.llm

import org.febyher.chat.LLMMessage
import java.net.HttpURLConnection
import java.net.URI

/**
 * NVIDIA NIM LLM 服务实现 - 带完整诊断日志
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
            connectionStartTime = System.currentTimeMillis()
            val connection = createConnection(stream = false)
            sendRequestBody(connection, requestBody)
            firstByteTime = System.currentTimeMillis()
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
        val performance = diagnosticLogger.calculatePerformance(context, connectionStartTime, firstByteTime)
        val report = diagnosticLogger.generateReport(
            context, requestInfo!!, responseInfo, performance, exception, rawResponse, result
        )
        diagnosticLogger.logReport(report)
        if (result.isBlank() || result.contains("API错误") || result.contains("请求失败")) {
            diagnosticLogger.diagnoseEmptyResponse(report).forEach { logger.error(" - $it") }
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
        val rawResponse = StringBuilder()
        var connectionStartTime: Long? = null
        var firstByteTime: Long? = null
        var exception: Throwable? = null
        val fullContent = StringBuilder()
        try {
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
            connectionStartTime = System.currentTimeMillis()
            val connection = createConnection(stream = true)
            sendRequestBody(connection, requestBody)
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
                val performance = diagnosticLogger.calculatePerformance(context, connectionStartTime, firstByteTime)
                diagnosticLogger.logReport(
                    diagnosticLogger.generateReport(context, requestInfo, responseInfo, performance, null, errorBody, null)
                )
                return
            }
            val responseHeaders = diagnosticLogger.extractResponseHeaders(connection)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lineText = line!!
                    if (lineText.isBlank()) continue
                    rawResponse.append(lineText).append("\n")
                    if (lineText.startsWith("data: ")) {
                        val data = lineText.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
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
        val performance = diagnosticLogger.calculatePerformance(context, connectionStartTime, firstByteTime)
        diagnosticLogger.logReport(
            diagnosticLogger.generateReport(
                context, requestInfo!!, responseInfo, performance, exception,
                rawResponse.toString(), fullContent.toString().ifBlank { null }
            )
        )
        if (fullContent.isBlank()) {
            val report = diagnosticLogger.generateReport(
                context, requestInfo, responseInfo, performance, exception,
                rawResponse.toString(), null
            )
            diagnosticLogger.diagnoseEmptyResponse(report).forEach { logger.error(" - $it") }
        }
    }

    private fun createConnection(stream: Boolean): HttpURLConnection {
        val connection = URI(config.apiUrl).toURL().openConnection() as HttpURLConnection
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
        connection.outputStream.use {
            it.write(body.toByteArray(Charsets.UTF_8))
            it.flush()
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

    private fun getHttpStatusText(code: Int): String = when (code) {
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
