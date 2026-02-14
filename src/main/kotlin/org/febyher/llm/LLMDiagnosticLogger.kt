package org.febyher.llm

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.management.ManagementFactory
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * LLM诊断日志记录器 - 用于排查API调用问题
 * 输出结构化JSON日志，包含完整的请求/响应信息
 */
object LLMDiagnosticLogger {
    
    private val logger = Logger.getInstance(LLMDiagnosticLogger::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    private val jsonDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    
    // 日志文件路径
    private var logFilePath: String? = null
    
    /**
     * 设置日志文件路径
     */
    fun setLogFilePath(path: String) {
        logFilePath = path
    }
    
    /**
     * 诊断上下文 - 记录单次API调用的所有信息
     */
    data class DiagnosticContext(
        val callId: String = UUID.randomUUID().toString(),
        val provider: String,
        val model: String,
        val timestamp: String = dateFormat.format(Date()),
        val startTime: Long = System.currentTimeMillis(),
        val startTimeNanos: Long = System.nanoTime()
    )
    
    /**
     * 请求信息
     */
    data class RequestInfo(
        val url: String,
        val method: String = "POST",
        val headers: Map<String, String>,
        val body: String,
        val bodySizeBytes: Int,
        val model: String,
        val temperature: Double,
        val maxTokens: Int,
        val stream: Boolean,
        val extraParams: Map<String, Any> = emptyMap()
    )
    
    /**
     * 响应信息
     */
    data class ResponseInfo(
        val httpStatus: Int,
        val httpStatusText: String,
        val headers: Map<String, List<String>>,
        val body: String,
        val bodySizeBytes: Int,
        val contentType: String?,
        val isError: Boolean
    )
    
    /**
     * 性能指标
     */
    data class PerformanceMetrics(
        val totalDurationMs: Long,
        val connectionTimeMs: Long?,
        val firstByteTimeMs: Long?,
        val downloadTimeMs: Long?,
        val networkLatencyMs: Long?
    )
    
    /**
     * 系统资源信息
     */
    data class SystemResources(
        val jvmHeapUsedMB: Long,
        val jvmHeapMaxMB: Long,
        val jvmHeapUsagePercent: Double,
        val systemCpuLoad: Double?,
        val processCpuLoad: Double?,
        val availableProcessors: Int,
        val threadCount: Int
    )
    
    /**
     * 完整的诊断报告
     */
    data class DiagnosticReport(
        val callId: String,
        val provider: String,
        val timestamp: String,
        val request: RequestInfo,
        val response: ResponseInfo?,
        val performance: PerformanceMetrics?,
        val systemResources: SystemResources,
        val error: DiagnosticError?,
        val rawResponsePreview: String?,
        val parsedContent: String?,
        val isSuccessful: Boolean
    )
    
    /**
     * 错误信息
     */
    data class DiagnosticError(
        val type: String,
        val message: String,
        val stackTrace: String?,
        val cause: String?
    )
    
    /**
     * 创建诊断上下文
     */
    fun createContext(provider: String, model: String): DiagnosticContext {
        return DiagnosticContext(
            provider = provider,
            model = model
        )
    }
    
    /**
     * 获取系统资源信息
     */
    fun getSystemResources(): SystemResources {
        val runtime = Runtime.getRuntime()
        val memoryMXBean = ManagementFactory.getMemoryMXBean()
        val threadMXBean = ManagementFactory.getThreadMXBean()
        val osMXBean = ManagementFactory.getOperatingSystemMXBean()
        
        val heapUsage = memoryMXBean.heapMemoryUsage
        val heapUsed = heapUsage.used / (1024 * 1024)
        val heapMax = heapUsage.max / (1024 * 1024)
        
        return SystemResources(
            jvmHeapUsedMB = heapUsed,
            jvmHeapMaxMB = heapMax,
            jvmHeapUsagePercent = if (heapMax > 0) (heapUsed.toDouble() / heapMax) * 100 else 0.0,
            systemCpuLoad = try { 
                (osMXBean as? com.sun.management.OperatingSystemMXBean)?.systemCpuLoad 
            } catch (e: Exception) { null },
            processCpuLoad = try {
                (osMXBean as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad
            } catch (e: Exception) { null },
            availableProcessors = osMXBean.availableProcessors,
            threadCount = threadMXBean.threadCount
        )
    }
    
    /**
     * 从HTTP连接提取响应头
     */
    fun extractResponseHeaders(connection: HttpURLConnection): Map<String, List<String>> {
        val headers = mutableMapOf<String, List<String>>()
        try {
            for ((key, value) in connection.headerFields) {
                if (key != null) {
                    headers[key] = value ?: emptyList()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract response headers: ${e.message}")
        }
        return headers
    }
    
    /**
     * 构建请求头映射
     */
    fun buildRequestHeaders(apiKey: String, stream: Boolean): Map<String, String> {
        return mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "Authorization" to "Bearer ${maskApiKey(apiKey)}",
            "Accept" to if (stream) "text/event-stream" else "application/json"
        )
    }
    
    /**
     * 计算性能指标
     */
    fun calculatePerformance(
        context: DiagnosticContext,
        connectionStartTime: Long?,
        firstByteTime: Long?,
        endTime: Long = System.currentTimeMillis()
    ): PerformanceMetrics {
        val totalDuration = endTime - context.startTime
        
        return PerformanceMetrics(
            totalDurationMs = totalDuration,
            connectionTimeMs = connectionStartTime?.let { it - context.startTime },
            firstByteTimeMs = firstByteTime?.let { it - context.startTime },
            downloadTimeMs = firstByteTime?.let { endTime - it },
            networkLatencyMs = connectionStartTime?.let { connStart ->
                firstByteTime?.let { firstByte -> firstByte - connStart }
            }
        )
    }
    
    /**
     * 生成完整的诊断报告
     */
    fun generateReport(
        context: DiagnosticContext,
        request: RequestInfo,
        response: ResponseInfo?,
        performance: PerformanceMetrics?,
        error: Throwable?,
        rawResponse: String?,
        parsedContent: String?
    ): DiagnosticReport {
        val diagnosticError = error?.let {
            DiagnosticError(
                type = it::class.java.simpleName,
                message = it.message ?: "Unknown error",
                stackTrace = it.stackTraceToString().take(2000),
                cause = it.cause?.message
            )
        }
        
        return DiagnosticReport(
            callId = context.callId,
            provider = context.provider,
            timestamp = context.timestamp,
            request = request,
            response = response,
            performance = performance,
            systemResources = getSystemResources(),
            error = diagnosticError,
            rawResponsePreview = rawResponse?.take(1000),
            parsedContent = parsedContent?.take(500),
            isSuccessful = response?.httpStatus == 200 && error == null && !parsedContent.isNullOrBlank()
        )
    }
    
    /**
     * 将诊断报告转换为JSON字符串
     */
    fun toJson(report: DiagnosticReport): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        
        // 基本信息
        sb.appendLine("  \"callId\": \"${escapeJson(report.callId)}\",")
        sb.appendLine("  \"provider\": \"${escapeJson(report.provider)}\",")
        sb.appendLine("  \"timestamp\": \"${escapeJson(report.timestamp)}\",")
        sb.appendLine("  \"isSuccessful\": ${report.isSuccessful},")
        
        // 请求信息
        sb.appendLine("  \"request\": {")
        sb.appendLine("    \"url\": \"${escapeJson(report.request.url)}\",")
        sb.appendLine("    \"method\": \"${escapeJson(report.request.method)}\",")
        sb.appendLine("    \"model\": \"${escapeJson(report.request.model)}\",")
        sb.appendLine("    \"temperature\": ${report.request.temperature},")
        sb.appendLine("    \"maxTokens\": ${report.request.maxTokens},")
        sb.appendLine("    \"stream\": ${report.request.stream},")
        sb.appendLine("    \"bodySizeBytes\": ${report.request.bodySizeBytes},")
        sb.appendLine("    \"headers\": {")
        report.request.headers.forEach { (key, value) ->
            sb.appendLine("      \"${escapeJson(key)}\": \"${escapeJson(value)}\",")
        }
        if (report.request.headers.isNotEmpty()) {
            sb.deleteCharAt(sb.length - 3) // 移除最后一个逗号
        }
        sb.appendLine("    },")
        sb.appendLine("    \"extraParams\": ${mapToJson(report.request.extraParams)},")
        sb.appendLine("    \"bodyPreview\": \"${escapeJson(report.request.body.take(500))}\"")
        sb.appendLine("  },")
        
        // 响应信息
        report.response?.let { resp ->
            sb.appendLine("  \"response\": {")
            sb.appendLine("    \"httpStatus\": ${resp.httpStatus},")
            sb.appendLine("    \"httpStatusText\": \"${escapeJson(resp.httpStatusText)}\",")
            sb.appendLine("    \"contentType\": \"${escapeJson(resp.contentType ?: "unknown")}\",")
            sb.appendLine("    \"bodySizeBytes\": ${resp.bodySizeBytes},")
            sb.appendLine("    \"isError\": ${resp.isError},")
            sb.appendLine("    \"headers\": {")
            resp.headers.forEach { (key, values) ->
                sb.appendLine("      \"${escapeJson(key)}\": [${values.joinToString(", ") { "\"${escapeJson(it)}\"" }}],")
            }
            if (resp.headers.isNotEmpty()) {
                sb.deleteCharAt(sb.length - 3)
            }
            sb.appendLine("    },")
            sb.appendLine("    \"bodyPreview\": \"${escapeJson(resp.body.take(1000))}\"")
            sb.appendLine("  },")
        } ?: run {
            sb.appendLine("  \"response\": null,")
        }
        
        // 性能指标
        report.performance?.let { perf ->
            sb.appendLine("  \"performance\": {")
            sb.appendLine("    \"totalDurationMs\": ${perf.totalDurationMs},")
            sb.appendLine("    \"connectionTimeMs\": ${perf.connectionTimeMs ?: "null"},")
            sb.appendLine("    \"firstByteTimeMs\": ${perf.firstByteTimeMs ?: "null"},")
            sb.appendLine("    \"downloadTimeMs\": ${perf.downloadTimeMs ?: "null"},")
            sb.appendLine("    \"networkLatencyMs\": ${perf.networkLatencyMs ?: "null"}")
            sb.appendLine("  },")
        } ?: run {
            sb.appendLine("  \"performance\": null,")
        }
        
        // 系统资源
        sb.appendLine("  \"systemResources\": {")
        sb.appendLine("    \"jvmHeapUsedMB\": ${report.systemResources.jvmHeapUsedMB},")
        sb.appendLine("    \"jvmHeapMaxMB\": ${report.systemResources.jvmHeapMaxMB},")
        sb.appendLine("    \"jvmHeapUsagePercent\": ${String.format("%.2f", report.systemResources.jvmHeapUsagePercent)},")
        sb.appendLine("    \"systemCpuLoad\": ${report.systemResources.systemCpuLoad ?: "null"},")
        sb.appendLine("    \"processCpuLoad\": ${report.systemResources.processCpuLoad ?: "null"},")
        sb.appendLine("    \"availableProcessors\": ${report.systemResources.availableProcessors},")
        sb.appendLine("    \"threadCount\": ${report.systemResources.threadCount}")
        sb.appendLine("  },")
        
        // 错误信息
        report.error?.let { err ->
            sb.appendLine("  \"error\": {")
            sb.appendLine("    \"type\": \"${escapeJson(err.type)}\",")
            sb.appendLine("    \"message\": \"${escapeJson(err.message)}\",")
            sb.appendLine("    \"cause\": ${if (err.cause != null) "\"${escapeJson(err.cause)}\"" else "null"}")
            sb.appendLine("  },")
        } ?: run {
            sb.appendLine("  \"error\": null,")
        }
        
        // 解析结果
        sb.appendLine("  \"rawResponsePreview\": ${if (report.rawResponsePreview != null) "\"${escapeJson(report.rawResponsePreview)}\"" else "null"},")
        sb.appendLine("  \"parsedContent\": ${if (report.parsedContent != null) "\"${escapeJson(report.parsedContent)}\"" else "null"}")
        
        sb.append("}")
        return sb.toString()
    }
    
    /**
     * 记录诊断报告
     */
    fun logReport(report: DiagnosticReport) {
        val json = toJson(report)
        
        // 输出到IDE日志
        logger.info("=== LLM Diagnostic Report ===")
        logger.info(json)
        
        // 输出到文件（如果配置了）
        logFilePath?.let { path ->
            try {
                val file = File(path)
                file.appendText("\n// === ${report.timestamp} ===\n$json\n")
            } catch (e: Exception) {
                logger.warn("Failed to write diagnostic log to file: ${e.message}")
            }
        }
        
        // 如果是失败的情况，打印更详细的信息
        if (!report.isSuccessful) {
            logger.error("=== LLM API Call FAILED ===")
            logger.error("Call ID: ${report.callId}")
            logger.error("Provider: ${report.provider}")
            logger.error("HTTP Status: ${report.response?.httpStatus}")
            logger.error("Error: ${report.error}")
            logger.error("Response Body Preview: ${report.rawResponsePreview}")
            logger.error("Request Body Preview: ${report.request.body.take(500)}")
        }
    }
    
    /**
     * 快速诊断空响应问题
     */
    fun diagnoseEmptyResponse(report: DiagnosticReport): List<String> {
        val issues = mutableListOf<String>()
        
        // 检查HTTP状态
        if (report.response?.httpStatus != 200) {
            issues.add("HTTP状态码异常: ${report.response?.httpStatus} ${report.response?.httpStatusText}")
        }
        
        // 检查响应体
        if (report.response?.body.isNullOrBlank()) {
            issues.add("响应体为空")
        }
        
        // 检查解析结果
        if (report.parsedContent.isNullOrBlank() && report.response?.body?.isNotBlank() == true) {
            issues.add("响应体存在但解析失败，可能是格式不兼容")
        }
        
        // 检查API Key
        if (report.request.headers["Authorization"]?.contains("null") == true) {
            issues.add("API Key未设置或格式错误")
        }
        
        // 检查网络延迟
        report.performance?.let { perf ->
            if (perf.networkLatencyMs != null && perf.networkLatencyMs > 30000) {
                issues.add("网络延迟过高: ${perf.networkLatencyMs}ms")
            }
            if (perf.totalDurationMs > 60000) {
                issues.add("总耗时过长: ${perf.totalDurationMs}ms")
            }
        }
        
        // 检查错误信息
        report.error?.let { err ->
            issues.add("发生错误: ${err.type} - ${err.message}")
        }
        
        // 检查模型
        if (report.request.model.isBlank()) {
            issues.add("模型名称为空")
        }
        
        return issues
    }
    
    // ========== 辅助方法 ==========
    
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    private fun maskApiKey(apiKey: String): String {
        if (apiKey.length <= 8) return "***"
        return apiKey.take(4) + "..." + apiKey.takeLast(4)
    }
    
    private fun mapToJson(map: Map<String, Any>): String {
        val sb = StringBuilder("{")
        map.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(", ")
            sb.append("\"${escapeJson(key)}\": ")
            when (value) {
                is String -> sb.append("\"${escapeJson(value)}\"")
                is Number, is Boolean -> sb.append(value)
                is Map<*, *> -> sb.append(mapToJson(value as Map<String, Any>))
                else -> sb.append("\"${escapeJson(value.toString())}\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }
}
