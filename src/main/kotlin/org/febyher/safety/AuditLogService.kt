package org.febyher.safety

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.text.SimpleDateFormat
import java.util.*

/**
 * 审计日志条目
 */
data class AuditLogEntry(
    var timestamp: Long = System.currentTimeMillis(),
    var action: String = "",          // "llm_request", "patch_apply", "file_write"
    var provider: String = "",        // AI 提供商
    var model: String = "",           // 使用的模型
    var inputTokens: Int = 0,         // 输入 token 数
    var outputTokens: Int = 0,        // 输出 token 数
    var filesInvolved: String = "",   // 涉及的文件列表（逗号分隔）
    var success: Boolean = true,      // 是否成功
    var errorMessage: String = "",    // 错误信息
    var patchHash: String = "",       // 补丁哈希（用于追踪）
    var duration: Long = 0,           // 执行时长（毫秒）
    var userRequest: String = ""      // 用户请求摘要
) {
    /**
     * 格式化时间戳
     */
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(Date(timestamp))
    }
    
    /**
     * 生成摘要
     */
    fun getSummary(): String {
        return "[${getFormattedTime()}] $action - ${if (success) "成功" else "失败: $errorMessage"}"
    }
}

/**
 * 审计日志状态
 */
data class AuditLogState(
    var entries: MutableList<AuditLogEntry> = mutableListOf(),
    var maxEntries: Int = 1000,  // 最大日志条数
    var totalRequests: Long = 0,
    var totalTokens: Long = 0
)

/**
 * 审计日志服务
 * 记录所有 AI 操作以便追踪和审查
 */
@State(
    name = "org.febyher.safety.AuditLogService",
    storages = [Storage("FebyherAuditLog.xml")]
)
@Service(Service.Level.APP)
class AuditLogService : PersistentStateComponent<AuditLogState> {
    
    private var myState = AuditLogState()
    
    companion object {
        @JvmStatic
        fun getInstance(): AuditLogService {
            return com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(AuditLogService::class.java)
        }
    }
    
    override fun getState(): AuditLogState = myState
    
    override fun loadState(state: AuditLogState) {
        XmlSerializerUtil.copyBean(state, myState)
    }
    
    /**
     * 记录 LLM 请求
     */
    fun logLLMRequest(
        provider: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        success: Boolean,
        errorMessage: String = "",
        duration: Long = 0
    ) {
        addEntry(AuditLogEntry(
            action = "llm_request",
            provider = provider,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            success = success,
            errorMessage = errorMessage,
            duration = duration
        ))
        
        myState.totalRequests++
        myState.totalTokens += inputTokens + outputTokens
    }
    
    /**
     * 记录补丁应用
     */
    fun logPatchApply(
        files: List<String>,
        success: Boolean,
        patchHash: String,
        errorMessage: String = ""
    ) {
        addEntry(AuditLogEntry(
            action = "patch_apply",
            filesInvolved = files.joinToString(","),
            success = success,
            errorMessage = errorMessage,
            patchHash = patchHash
        ))
    }
    
    /**
     * 记录文件写入
     */
    fun logFileWrite(
        filePath: String,
        success: Boolean,
        errorMessage: String = ""
    ) {
        addEntry(AuditLogEntry(
            action = "file_write",
            filesInvolved = filePath,
            success = success,
            errorMessage = errorMessage
        ))
    }
    
    /**
     * 记录完整操作
     */
    fun logOperation(entry: AuditLogEntry) {
        addEntry(entry)
    }
    
    /**
     * 添加日志条目
     */
    private fun addEntry(entry: AuditLogEntry) {
        // 检查是否超出最大条数
        while (myState.entries.size >= myState.maxEntries) {
            myState.entries.removeAt(0)
        }
        
        myState.entries.add(entry)
    }
    
    /**
     * 获取最近的日志
     */
    fun getRecentLogs(count: Int = 50): List<AuditLogEntry> {
        return myState.entries.takeLast(count)
    }
    
    /**
     * 获取所有日志
     */
    fun getAllLogs(): List<AuditLogEntry> = myState.entries.toList()
    
    /**
     * 按日期范围查询日志
     */
    fun getLogsByDateRange(start: Long, end: Long): List<AuditLogEntry> {
        return myState.entries.filter { it.timestamp in start..end }
    }
    
    /**
     * 按操作类型查询日志
     */
    fun getLogsByAction(action: String): List<AuditLogEntry> {
        return myState.entries.filter { it.action == action }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): AuditStatistics {
        val requests = myState.entries.count { it.action == "llm_request" }
        val patches = myState.entries.count { it.action == "patch_apply" }
        val filesWritten = myState.entries.count { it.action == "file_write" }
        val failures = myState.entries.count { !it.success }
        
        return AuditStatistics(
            totalRequests = requests.toLong(),
            totalPatches = patches.toLong(),
            totalFilesWritten = filesWritten.toLong(),
            totalFailures = failures.toLong(),
            totalTokens = myState.totalTokens
        )
    }
    
    /**
     * 清空日志
     */
    fun clearLogs() {
        myState.entries.clear()
        myState.totalRequests = 0
        myState.totalTokens = 0
    }
    
    /**
     * 导出日志为文本
     */
    fun exportLogs(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Febyher AI 审计日志 ===")
        sb.appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        sb.appendLine("总条目数: ${myState.entries.size}")
        sb.appendLine()
        
        for (entry in myState.entries) {
            sb.appendLine("---")
            sb.appendLine("时间: ${entry.getFormattedTime()}")
            sb.appendLine("操作: ${entry.action}")
            sb.appendLine("状态: ${if (entry.success) "成功" else "失败"}")
            
            if (entry.provider.isNotEmpty()) {
                sb.appendLine("提供商: ${entry.provider}")
            }
            if (entry.model.isNotEmpty()) {
                sb.appendLine("模型: ${entry.model}")
            }
            if (entry.inputTokens > 0 || entry.outputTokens > 0) {
                sb.appendLine("Tokens: 输入=${entry.inputTokens}, 输出=${entry.outputTokens}")
            }
            if (entry.filesInvolved.isNotEmpty()) {
                sb.appendLine("涉及文件: ${entry.filesInvolved}")
            }
            if (entry.errorMessage.isNotEmpty()) {
                sb.appendLine("错误: ${entry.errorMessage}")
            }
            if (entry.duration > 0) {
                sb.appendLine("耗时: ${entry.duration}ms")
            }
        }
        
        return sb.toString()
    }
}

/**
 * 审计统计信息
 */
data class AuditStatistics(
    val totalRequests: Long,
    val totalPatches: Long,
    val totalFilesWritten: Long,
    val totalFailures: Long,
    val totalTokens: Long
)
