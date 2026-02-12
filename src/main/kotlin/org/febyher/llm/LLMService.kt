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

/**
 * LLM服务接口
 */
interface LLMService {
    fun chat(messages: List<LLMMessage>): String
    fun chatWithContext(userMessage: String, context: CodeContext?): String
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
    
    override fun chat(messages: List<LLMMessage>): String {
        if (config.apiKey.isBlank()) {
            return "API Key未配置\n\n请前往 Settings > Tools > Febyher AI 配置你的 API Key。"
        }
        
        return try {
            callAPI(messages)
        } catch (e: Exception) {
            logger.error("API request failed", e)
            "请求失败: ${e.message}\n\n请检查网络连接和API Key是否有效。"
        }
    }
    
    override fun chatWithContext(userMessage: String, context: CodeContext?): String {
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
        return chat(messages)
    }
    
    protected open fun buildSystemPrompt(context: CodeContext?): String {
        return getSystemPrompt()
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
     * 通用的HTTP API调用逻辑
     */
    private fun callAPI(messages: List<LLMMessage>): String {
        val url = URI(config.apiUrl).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.connectTimeout = 60000
            connection.readTimeout = 120000
            
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Accept", "application/json")
            
            val requestBody = buildRequestBody(messages)
            logger.info("[${getProviderName()}] Request body length: ${requestBody.length}")
            
            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody.toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
            }
            
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
     * 构建JSON请求体
     */
    private fun buildRequestBody(messages: List<LLMMessage>): String {
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
        
        sb.append(", \"stream\": false")
        sb.append("}")
        
        return sb.toString()
    }
    
    /**
     * 解析API响应
     */
    private fun parseResponse(json: String): String {
        return try {
            val choicesStart = json.indexOf(""""choices""")
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
    
    private fun parseErrorResponse(json: String): String {
        return try {
            val errorIndex = json.indexOf(""""error""")
            if (errorIndex != -1) {
                val msgIndex = json.indexOf(""""message""", errorIndex)
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
    
    private fun escapeJson(str: String): String {
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
 * LLM服务工厂 - 根据Provider类型创建对应的服务实例
 */
object LLMServiceFactory {
    
    private val logger = Logger.getInstance(LLMServiceFactory::class.java)
    
    /**
     * 注册的Provider映射 - 可扩展
     */
    private val providerRegistry = mutableMapOf<AIProvider, (ProviderConfig) -> BaseLLMService>(
        AIProvider.MOONSHOT to { config -> MoonshotLLMService(config) },
        AIProvider.DEEPSEEK to { config -> DeepSeekLLMService(config) }
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
