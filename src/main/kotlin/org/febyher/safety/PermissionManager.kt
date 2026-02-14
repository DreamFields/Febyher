package org.febyher.safety

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 权限级别
 */
enum class PermissionLevel {
    READ_ONLY,      // 只读分析
    PATCH_GENERATE, // 生成补丁（不落地）
    PATCH_APPLY,    // 应用补丁
    COMMAND_EXECUTE // 执行命令
}

/**
 * 安全策略配置
 */
data class SafetyPolicy(
    var allowFileWrite: Boolean = true,
    var allowCommandExecution: Boolean = false,
    var requireConfirmationForWrite: Boolean = true,
    var requireConfirmationForCommand: Boolean = true,
    var maxFileSizeKB: Int = 500,           // 最大文件大小限制
    var maxContextTokens: Int = 32000,       // 最大上下文 token 数
    var deniedPaths: MutableList<String> = mutableListOf(),   // 禁止访问的路径模式
    var allowedPaths: MutableList<String> = mutableListOf(),  // 允许访问的路径模式
    var deniedExtensions: MutableList<String> = mutableListOf() // 禁止的文件扩展名
)

/**
 * 权限检查结果
 */
sealed class PermissionResult {
    object Granted : PermissionResult()
    data class Denied(val reason: String) : PermissionResult()
    data class RequiresConfirmation(val message: String) : PermissionResult()
}

/**
 * 权限管理器
 * 管理操作权限和安全策略
 */
@State(
    name = "org.febyher.safety.PermissionManager",
    storages = [Storage("FebyherSecurity.xml")]
)
@Service(Service.Level.APP)
class PermissionManager : PersistentStateComponent<SafetyPolicy> {
    
    private var myPolicy = SafetyPolicy().apply {
        // 默认禁止的路径
        deniedPaths.addAll(listOf(
            ".env",
            ".env.*",
            "*.pem",
            "*.key",
            "*.p12",
            "*.jks",
            "secrets/",
            "credentials/",
            ".git/",
            ".idea/"
        ))
        
        // 默认禁止的扩展名
        deniedExtensions.addAll(listOf(
            "env",
            "pem",
            "key",
            "p12",
            "jks",
            "keystore"
        ))
    }
    
    companion object {
        @JvmStatic
        fun getInstance(): PermissionManager {
            return com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(PermissionManager::class.java)
        }
    }
    
    override fun getState(): SafetyPolicy = myPolicy
    
    override fun loadState(state: SafetyPolicy) {
        XmlSerializerUtil.copyBean(state, myPolicy)
    }
    
    /**
     * 检查文件读取权限
     */
    fun checkReadPermission(file: VirtualFile): PermissionResult {
        // 检查文件大小
        if (file.length > myPolicy.maxFileSizeKB * 1024) {
            return PermissionResult.Denied("文件过大: ${file.name} (${file.length / 1024}KB > ${myPolicy.maxFileSizeKB}KB)")
        }
        
        // 检查禁止的路径
        for (pattern in myPolicy.deniedPaths) {
            if (matchesPattern(file.path, pattern)) {
                return PermissionResult.Denied("禁止访问: ${file.path} 匹配拒绝模式 $pattern")
            }
        }
        
        // 检查禁止的扩展名
        val extension = file.extension?.lowercase()
        if (extension in myPolicy.deniedExtensions) {
            return PermissionResult.Denied("禁止访问的文件类型: .$extension")
        }
        
        return PermissionResult.Granted
    }
    
    /**
     * 检查文件写入权限
     */
    fun checkWritePermission(file: VirtualFile): PermissionResult {
        if (!myPolicy.allowFileWrite) {
            return PermissionResult.Denied("文件写入功能已禁用")
        }
        
        // 基础读取权限检查
        val readResult = checkReadPermission(file)
        if (readResult is PermissionResult.Denied) {
            return readResult
        }
        
        // 检查是否需要确认
        if (myPolicy.requireConfirmationForWrite) {
            return PermissionResult.RequiresConfirmation("确认要修改文件 ${file.name} 吗？")
        }
        
        return PermissionResult.Granted
    }
    
    /**
     * 检查补丁应用权限
     */
    fun checkPatchApplyPermission(files: List<VirtualFile>): PermissionResult {
        if (!myPolicy.allowFileWrite) {
            return PermissionResult.Denied("补丁应用功能已禁用")
        }
        
        // 检查所有文件
        for (file in files) {
            val result = checkWritePermission(file)
            if (result is PermissionResult.Denied) {
                return result
            }
        }
        
        // 检查是否需要确认
        if (myPolicy.requireConfirmationForWrite) {
            return PermissionResult.RequiresConfirmation("确认要修改 ${files.size} 个文件吗？")
        }
        
        return PermissionResult.Granted
    }
    
    /**
     * 检查命令执行权限
     */
    fun checkCommandPermission(command: String): PermissionResult {
        if (!myPolicy.allowCommandExecution) {
            return PermissionResult.Denied("命令执行功能已禁用")
        }
        
        // 检查危险命令
        val dangerousCommands = listOf("rm", "del", "format", "mkfs", "dd", "shutdown", "reboot")
        for (dangerous in dangerousCommands) {
            if (command.lowercase().contains(dangerous)) {
                return PermissionResult.Denied("禁止执行危险命令: $dangerous")
            }
        }
        
        // 检查是否需要确认
        if (myPolicy.requireConfirmationForCommand) {
            return PermissionResult.RequiresConfirmation("确认要执行命令: $command")
        }
        
        return PermissionResult.Granted
    }
    
    /**
     * 检查上下文大小
     */
    fun checkContextSize(estimatedTokens: Int): PermissionResult {
        if (estimatedTokens > myPolicy.maxContextTokens) {
            return PermissionResult.Denied("上下文过大: $estimatedTokens tokens > ${myPolicy.maxContextTokens}")
        }
        
        return PermissionResult.Granted
    }
    
    /**
     * 路径模式匹配
     */
    private fun matchesPattern(path: String, pattern: String): Boolean {
        val normalizedPath = path.replace("\\", "/").lowercase()
        val normalizedPattern = pattern.replace("\\", "/").lowercase()
        
        // 简单的通配符匹配
        if (normalizedPattern.contains("*")) {
            val regex = normalizedPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .toRegex()
            return regex.matches(normalizedPath)
        }
        
        return normalizedPath.contains(normalizedPattern)
    }
    
    /**
     * 添加禁止路径
     */
    fun addDeniedPath(pattern: String) {
        if (pattern !in myPolicy.deniedPaths) {
            myPolicy.deniedPaths.add(pattern)
        }
    }
    
    /**
     * 移除禁止路径
     */
    fun removeDeniedPath(pattern: String) {
        myPolicy.deniedPaths.remove(pattern)
    }
    
    /**
     * 添加允许路径
     */
    fun addAllowedPath(pattern: String) {
        if (pattern !in myPolicy.allowedPaths) {
            myPolicy.allowedPaths.add(pattern)
        }
    }
    
    /**
     * 设置最大上下文大小
     */
    fun setMaxContextTokens(max: Int) {
        myPolicy.maxContextTokens = max
    }
    
    /**
     * 启用/禁用文件写入
     */
    fun setFileWriteEnabled(enabled: Boolean) {
        myPolicy.allowFileWrite = enabled
    }
    
    /**
     * 启用/禁用命令执行
     */
    fun setCommandExecutionEnabled(enabled: Boolean) {
        myPolicy.allowCommandExecution = enabled
    }
    
    /**
     * 敏感信息检测
     */
    fun containsSensitiveInfo(content: String): Boolean {
        val sensitivePatterns = listOf(
            "api[_-]?key",
            "secret[_-]?key",
            "password",
            "passwd",
            "token",
            "bearer",
            "auth[_-]?token",
            "private[_-]?key",
            "access[_-]?key",
            "credential"
        )
        
        val lowerContent = content.lowercase()
        for (pattern in sensitivePatterns) {
            if (lowerContent.contains(pattern.toRegex())) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 脱敏处理
     */
    fun sanitizeContent(content: String): String {
        var sanitized = content
        
        // 脱敏 API Key 模式
        val apiKeyPattern = Regex("(api[_-]?key\\s*[=:]\\s*)['\"]?([^'\"\\s]+)['\"]?", RegexOption.IGNORE_CASE)
        sanitized = sanitized.replace(apiKeyPattern) { match ->
            "${match.groupValues[1]}***REDACTED***"
        }
        
        // 脱敏密码模式
        val passwordPattern = Regex("(password|passwd)\\s*[=:]\\s*['\"]?([^'\"\\s]+)['\"]?", RegexOption.IGNORE_CASE)
        sanitized = sanitized.replace(passwordPattern) { match ->
            "${match.groupValues[1]}=***REDACTED***"
        }
        
        // 脱敏 Token 模式
        val tokenPattern = Regex("(token|bearer)\\s*[=:]?\\s*['\"]?([a-zA-Z0-9_-]{20,})['\"]?", RegexOption.IGNORE_CASE)
        sanitized = sanitized.replace(tokenPattern) { match ->
            "${match.groupValues[1]} ***REDACTED***"
        }
        
        return sanitized
    }
}
