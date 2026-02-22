package org.febyher.llm.aurod

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.febyher.chat.LLMMessage
import org.febyher.context.CodeContext
import org.febyher.llm.LLMService
import org.febyher.llm.ProviderConfig
import org.febyher.llm.StreamCallback
import org.febyher.settings.CopilotSettings

/**
 * Aurod AI LLM 服务实现
 * 基于 Aurod API（https://ai.aurod.cn），提供与现有 LLMService 接口的兼容
 * 同时提供完整的会话管理、模型管理等扩展功能
 */
class AurodLLMService(config: ProviderConfig) : LLMService {

    private val logger = Logger.getInstance(AurodLLMService::class.java)
    private val apiClient: AurodApiClient

    // 当前会话 ID（由外部通过 AurodSessionManager 管理）
    var currentSessionId: Long = 0
    var currentModel: String = config.model

    init {
        apiClient = AurodApiClient(
            authToken = config.apiKey,  // Aurod 使用 token 认证而非 Bearer API Key
            uid = 0
        )
        logger.info("[AurodLLMService] 初始化完成, model=${config.model}")
    }

    /**
     * 获取底层 API 客户端（供 AurodSessionManager 使用）
     */
    fun getApiClient(): AurodApiClient = apiClient

    /**
     * 确保 apiClient 使用最新的认证信息（cookie、uid 可能在登录后更新）
     */
    private fun ensureAuth() {
        val settings = CopilotSettings.getInstance()
        val aurodConfig = settings.aurodConfig
        logger.info("[AurodLLMService] ensureAuth 检查, authToken存在=${aurodConfig.authToken.isNotBlank()}, uid=${aurodConfig.uid}")
        if (aurodConfig.authToken.isNotBlank()) {
            apiClient.setAuth(aurodConfig.authToken, aurodConfig.cookie, aurodConfig.uid)
            logger.info("[AurodLLMService] 认证信息已设置到apiClient")
        } else {
            logger.warn("[AurodLLMService] authToken为空，无法设置认证")
        }
    }

    // ==================== LLMService 接口实现 ====================

    override fun chat(messages: List<LLMMessage>): String {
        ensureAuth()

        if (!apiClient.isAuthenticated) {
            return "未登录 Aurod AI\n\n请前往 Settings > Tools > Febyher AI 配置 Aurod 账号并登录。"
        }

        if (currentSessionId == 0L) {
            // 自动创建会话而非阻断
            return try {
                val session = apiClient.createSession(currentModel.ifBlank { "gpt-5-chat" })
                currentSessionId = session.sessionId
                logger.info("[AurodLLMService] 自动创建会话: id=${session.sessionId}")
                val userMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""
                apiClient.chatComplete(userMessage, currentSessionId, currentModel)
            } catch (e: Exception) {
                logger.error("[AurodLLMService] 自动创建会话失败", e)
                "自动创建会话失败: ${e.message}\n\n请手动创建 Aurod 会话后重试。"
            }
        }

        return try {
            val userMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""
            apiClient.chatComplete(userMessage, currentSessionId, currentModel)
        } catch (e: Exception) {
            logger.error("[AurodLLMService] 聊天失败", e)
            "请求失败: ${e.message}\n\n请检查网络连接和 Aurod 登录状态。"
        }
    }

    override fun chatWithContext(userMessage: String, context: CodeContext?): String {
        val messages = buildMessagesWithContext(userMessage, context)
        return chat(messages)
    }

    override fun chatStream(messages: List<LLMMessage>, callback: StreamCallback) {
        logger.info("[AurodLLMService] chatStream 调用开始, currentSessionId=$currentSessionId, currentModel=$currentModel")
        ensureAuth()

        if (!apiClient.isAuthenticated) {
            logger.warn("[AurodLLMService] API客户端未认证")
            val errorMsg = "未登录 Aurod AI\n\n请前往 Settings > Tools > Febyher AI 配置 Aurod 账号并登录。"
            callback.onError(errorMsg)
            return
        }

        if (currentSessionId == 0L) {
            // 自动创建会话而非阻断
            logger.warn("[AurodLLMService] currentSessionId=0，尝试自动创建会话")
            try {
                val session = apiClient.createSession(currentModel.ifBlank { "gpt-5-chat" })
                currentSessionId = session.sessionId
                logger.info("[AurodLLMService] 流式聊天自动创建会话: id=${session.sessionId}")
            } catch (e: Exception) {
                logger.error("[AurodLLMService] 自动创建会话失败", e)
                val errorMsg = "自动创建会话失败: ${safeGetMessage(e)}\n\n请手动创建 Aurod 会话后重试。"
                callback.onError(errorMsg)
                return
            }
        }

        try {
            val userMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""
            logger.info("[AurodLLMService] 准备调用 apiClient.chatStream, sessionId=$currentSessionId, model=$currentModel, message length=${userMessage.length}")

            apiClient.chatStream(
                text = userMessage,
                sessionId = currentSessionId,
                model = currentModel,
                onDelta = { delta -> 
                    logger.debug("[AurodLLMService] 收到delta: ${delta.take(50)}")
                    callback.onDelta(delta) 
                },
                onComplete = { fullResponse -> 
                    logger.info("[AurodLLMService] 流式响应完成, length=${fullResponse.length}")
                    callback.onComplete(fullResponse) 
                },
                onError = { error -> 
                    logger.error("[AurodLLMService] 流式响应错误: $error")
                    callback.onError(error) 
                }
            )
        } catch (e: Exception) {
            logger.error("[AurodLLMService] 流式聊天失败", e)
            val errorMsg = "请求失败: ${safeGetMessage(e)}\n\n请检查网络连接和 Aurod 登录状态。"
            callback.onError(errorMsg)
        }
    }

    /**
     * 安全获取异常消息（避免编码问题）
     */
    private fun safeGetMessage(e: Exception): String {
        return try {
            e.message ?: e.javaClass.simpleName
        } catch (ex: Exception) {
            "Unknown error (${e.javaClass.simpleName})"
        }
    }

    override fun chatStreamWithContext(userMessage: String, context: CodeContext?, callback: StreamCallback) {
        val messages = buildMessagesWithContext(userMessage, context)
        chatStream(messages, callback)
    }

    // ==================== 内部方法 ====================

    private fun buildMessagesWithContext(userMessage: String, context: CodeContext?): List<LLMMessage> {
        val messages = mutableListOf<LLMMessage>()

        val contextParts = mutableListOf<String>()
        context?.let {
            it.fileName?.let { name -> contextParts.add("当前文件: $name") }
            it.language?.let { lang -> contextParts.add("语言: $lang") }
            it.caretLine?.let { line -> contextParts.add("光标位置: 第 $line 行") }
            if (!it.selectedCode.isNullOrBlank()) {
                val code = if (it.selectedCode.length > 2000) {
                    it.selectedCode.take(2000) + "\n... (代码已截断)"
                } else {
                    it.selectedCode
                }
                contextParts.add("选中的代码:\n```${it.language ?: ""}\n$code\n```")
            }
        }

        val fullMessage = if (contextParts.isNotEmpty()) {
            "代码上下文:\n${contextParts.joinToString("\n")}\n\n用户问题:\n$userMessage"
        } else {
            userMessage
        }

        messages.add(LLMMessage("user", fullMessage))
        return messages
    }
}

/**
 * Aurod 会话管理器 - 项目级服务
 * 管理 Aurod 平台的会话、模型列表、聊天记录等功能
 */
@Service(Service.Level.PROJECT)
class AurodSessionManager(private val project: Project) {

    private val logger = Logger.getInstance(AurodSessionManager::class.java)

    // API 客户端（懒加载，从设置中获取认证信息）
    private var _apiClient: AurodApiClient? = null
    val apiClient: AurodApiClient
        get() {
            if (_apiClient == null) {
                val settings = CopilotSettings.getInstance()
                val aurodConfig = settings.aurodConfig
                _apiClient = AurodApiClient(
                    authToken = aurodConfig.authToken,
                    cookie = aurodConfig.cookie,
                    uid = aurodConfig.uid
                )
            }
            return _apiClient!!
        }

    // 当前选中的会话
    var currentSession: AurodSession? = null
        private set

    // 本地会话历史缓存
    private val sessionHistory = mutableListOf<AurodSession>()

    // 本地模型列表缓存
    private var cachedModels: AurodModelListResult? = null

    // ==================== 登录管理 ====================

    /**
     * 使用账号密码登录
     */
    fun login(account: String, password: String): AurodLoginResult {
        logger.info("[AurodSessionManager] 登录, account=$account")
        val result = apiClient.login(account, password)

        if (result.success) {
            // 保存认证信息到设置
            val settings = CopilotSettings.getInstance()
            settings.aurodConfig = AurodConfig(
                authToken = result.token,
                uid = result.uid,
                account = account,
                password = password
            )
        }
        return result
    }

    /**
     * 使用已有 token 自动登录
     */
    fun autoLogin(): Boolean {
        val settings = CopilotSettings.getInstance()
        val aurodConfig = settings.aurodConfig

        if (aurodConfig.authToken.isBlank()) {
            logger.info("[AurodSessionManager] 无保存的 token，需要手动登录")
            return false
        }

        apiClient.setAuth(aurodConfig.authToken, aurodConfig.cookie, aurodConfig.uid)
        return if (apiClient.validateToken()) {
            logger.info("[AurodSessionManager] 自动登录成功")
            true
        } else {
            logger.warn("[AurodSessionManager] Token 已过期，尝试重新登录")
            if (aurodConfig.account.isNotBlank() && aurodConfig.password.isNotBlank()) {
                try {
                    val result = login(aurodConfig.account, aurodConfig.password)
                    result.success
                } catch (e: Exception) {
                    logger.error("[AurodSessionManager] 重新登录失败: ${e.message}")
                    false
                }
            } else {
                false
            }
        }
    }

    val isLoggedIn: Boolean get() = apiClient.isAuthenticated

    // ==================== 1. 新建会话 ====================

    /**
     * 创建新会话
     */
    fun createSession(model: String = "gpt-5-chat"): AurodSession {
        val session = apiClient.createSession(model)
        currentSession = session
        addToHistory(session)
        return session
    }

    // ==================== 2. 启动对话 ====================

    /**
     * 在当前会话中发送消息（流式）
     */
    fun chat(
        text: String,
        model: String? = null,
        onDelta: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val session = currentSession ?: throw AurodApiException("未选择会话，请先创建或选择一个会话")

        apiClient.chatStream(
            text = text,
            sessionId = session.sessionId,
            model = model ?: session.model,
            onDelta = onDelta,
            onComplete = onComplete,
            onError = onError
        )
    }

    /**
     * 在当前会话中发送消息（同步）
     */
    fun chatComplete(text: String, model: String? = null): String {
        val session = currentSession ?: throw AurodApiException("未选择会话")
        return apiClient.chatComplete(text, session.sessionId, model ?: session.model)
    }

    // ==================== 3. 本地会话历史 ====================

    /**
     * 获取本地会话历史
     */
    fun getLocalSessionHistory(): List<AurodSession> = sessionHistory.toList()

    /**
     * 添加会话到本地历史
     */
    private fun addToHistory(session: AurodSession) {
        if (sessionHistory.none { it.sessionId == session.sessionId }) {
            sessionHistory.add(session)
        }
    }

    /**
     * 清空本地历史
     */
    fun clearLocalHistory() {
        sessionHistory.clear()
    }

    // ==================== 4. 已有会话选择 ====================

    /**
     * 选择已有会话
     */
    fun selectSession(session: AurodSession) {
        currentSession = session
        addToHistory(session)
        logger.info("[AurodSessionManager] 选择会话: id=${session.sessionId}, name=${session.sessionName}")
    }

    /**
     * 通过 ID 选择会话
     */
    fun selectSessionById(sessionId: Long): AurodSession? {
        val session = sessionHistory.find { it.sessionId == sessionId }
        if (session != null) {
            currentSession = session
            logger.info("[AurodSessionManager] 选择会话: id=$sessionId")
        }
        return session
    }

    // ==================== 5. 远程会话列表 ====================

    /**
     * 获取远程会话列表
     */
    fun getRemoteSessionList(page: Int = 1, size: Int = 30): AurodSessionListResult {
        return apiClient.getSessionList(page, size)
    }

    /**
     * 同步远程会话到本地
     */
    fun syncRemoteSessions(): List<AurodSession> {
        val result = getRemoteSessionList()
        sessionHistory.clear()
        sessionHistory.addAll(result.sessions)
        return result.sessions
    }

    // ==================== 6. 聊天记录查看 ====================

    /**
     * 获取聊天记录
     */
    fun getChatRecords(sessionId: Long? = null, page: Int = 1, size: Int = 10): AurodChatRecordResult {
        val targetId = sessionId ?: currentSession?.sessionId
            ?: throw AurodApiException("未指定会话 ID 且当前无选中会话")
        return apiClient.getChatRecords(targetId, page, size)
    }

    // ==================== 7. 本地模型列表 ====================

    /**
     * 获取缓存的模型列表
     */
    fun getCachedModels(): AurodModelListResult? = cachedModels

    /**
     * 获取模型列表（优先使用缓存）
     */
    fun getModels(forceRefresh: Boolean = false): AurodModelListResult {
        if (!forceRefresh && cachedModels != null) {
            return cachedModels!!
        }
        return refreshModels()
    }

    // ==================== 8. 模型列表更新 ====================

    /**
     * 从服务器刷新模型列表
     */
    fun refreshModels(): AurodModelListResult {
        val result = apiClient.getModels()
        cachedModels = result
        return result
    }

    // ==================== 9. 会话删除 ====================

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: Long): Boolean {
        val success = apiClient.deleteSession(sessionId)
        if (success) {
            sessionHistory.removeAll { it.sessionId == sessionId }
            if (currentSession?.sessionId == sessionId) {
                currentSession = null
            }
        }
        return success
    }

    // ==================== 会话模型切换 ====================

    /**
     * 切换当前会话的模型
     */
    fun switchModel(model: String): Boolean {
        val session = currentSession ?: throw AurodApiException("未选择会话")
        val success = apiClient.updateSession(session.sessionId, model, apiClient.uid)
        if (success) {
            currentSession = session.copy(model = model)
        }
        return success
    }

    /**
     * 刷新 API 客户端（配置变更后调用）
     */
    fun refresh() {
        _apiClient = null
        currentSession = null
    }

    companion object {
        fun getInstance(project: Project): AurodSessionManager {
            return project.getService(AurodSessionManager::class.java)
        }
    }
}

/**
 * Aurod 配置数据
 */
data class AurodConfig(
    var authToken: String = "",
    var cookie: String = "",
    var uid: Long = 0,
    var account: String = "",
    var password: String = ""
)
