package org.febyher.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.febyher.context.CodeContext
import org.febyher.context.ContextBuilder
import org.febyher.context.ProjectContext
import org.febyher.chat.LLMMessage
import org.febyher.llm.LLMService
import org.febyher.llm.LLMServiceManager
import org.febyher.llm.StreamCallback
import org.febyher.notification.NotificationService
import org.febyher.patch.DiffParser
import org.febyher.patch.DiffPreview
import org.febyher.patch.FileDiff
import org.febyher.patch.PatchApplier
import org.febyher.patch.PatchResult
import org.febyher.settings.CopilotSettings
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 会话状态
 */
sealed class AgentState {
    object Idle : AgentState()
    object Planning : AgentState()
    object Coding : AgentState()
    object Reviewing : AgentState()
    data class Error(val message: String) : AgentState()
}

/**
 * Agent 会话
 */
class AgentSession {
    private val messages = ConcurrentLinkedQueue<LLMMessage>()
    private val state = AtomicBoolean(false) // false = idle, true = processing
    
    fun addMessage(role: String, content: String) {
        messages.add(LLMMessage(role, content))
    }
    
    fun getMessages(): List<LLMMessage> = messages.toList()
    
    fun clear() = messages.clear()
    
    fun isProcessing(): Boolean = state.get()
    
    fun setProcessing(processing: Boolean) = state.set(processing)
}

/**
 * Agent 调度器
 * 管理 AI Agent 的执行流程
 */
class AgentOrchestrator(private val project: Project) {
    
    private val session = AgentSession()
    private val llmServiceManager = LLMServiceManager.getInstance(project)
    
    /**
     * 执行用户请求
     */
    fun execute(
        userRequest: String,
        context: ProjectContext? = null,
        onStateChange: (AgentState) -> Unit = {},
        onPlanGenerated: (AgentPlan) -> Unit = {},
        onDiffGenerated: (List<FileDiff>) -> Unit = {},
        onComplete: (AgentResponse) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (session.isProcessing()) {
            onError("当前正在处理其他请求")
            return
        }
        
        session.setProcessing(true)
        session.addMessage("user", userRequest)
        
        // 阶段1：规划
        onStateChange(AgentState.Planning)
        
        val planPrompt = buildPlanPrompt(userRequest, context)
        val llmService = llmServiceManager.getService()
        
        val responseBuilder = StringBuilder()
        
        llmService.chatStream(
            messages = buildMessages(planPrompt),
            callback = object : StreamCallback {
                override fun onDelta(delta: String) {
                    responseBuilder.append(delta)
                }
                
                override fun onComplete(fullResponse: String) {
                    handlePlanResponse(
                        fullResponse, 
                        userRequest, 
                        context,
                        onStateChange,
                        onPlanGenerated,
                        onDiffGenerated,
                        onComplete,
                        onError
                    )
                }
                
                override fun onError(error: String) {
                    session.setProcessing(false)
                    onStateChange(AgentState.Error(error))
                    onError(error)
                }
            }
        )
    }
    
    /**
     * 处理规划响应
     */
    private fun handlePlanResponse(
        response: String,
        userRequest: String,
        context: ProjectContext?,
        onStateChange: (AgentState) -> Unit,
        onPlanGenerated: (AgentPlan) -> Unit,
        onDiffGenerated: (List<FileDiff>) -> Unit,
        onComplete: (AgentResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val agentResponse = StructuredOutputParser.parse(response)
        session.addMessage("assistant", response)
        
        // 检查是否有计划
        val plan = StructuredOutputParser.extractPlan(response)
        if (plan != null) {
            onPlanGenerated(plan)
        }
        
        // 检查是否有代码变更
        val diffs = agentResponse.parseDiffs()
        
        if (diffs.isNotEmpty()) {
            onStateChange(AgentState.Coding)
            onDiffGenerated(diffs)
        }
        
        session.setProcessing(false)
        onStateChange(AgentState.Idle)
        onComplete(agentResponse)
    }
    
    /**
     * 仅生成计划（不执行代码修改）
     */
    fun planOnly(
        userRequest: String,
        context: ProjectContext? = null,
        onComplete: (AgentPlan?) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (session.isProcessing()) {
            onError("当前正在处理其他请求")
            return
        }
        
        session.setProcessing(true)
        
        val prompt = """
            请分析以下请求并给出执行计划，但不要生成具体的代码修改：
            
            $userRequest
            
            ${if (context != null) "上下文信息：\n${context.toContextSummary()}" else ""}
            
            只需要输出：
            1. 分析步骤
            2. 需要修改的文件列表
            3. 预期结果
        """.trimIndent()
        
        val llmService = llmServiceManager.getService()
        val responseBuilder = StringBuilder()
        
        llmService.chatStream(
            messages = buildMessages(prompt),
            callback = object : StreamCallback {
                override fun onDelta(delta: String) {
                    responseBuilder.append(delta)
                }
                
                override fun onComplete(fullResponse: String) {
                    session.setProcessing(false)
                    val plan = StructuredOutputParser.extractPlan(fullResponse)
                    onComplete(plan)
                }
                
                override fun onError(error: String) {
                    session.setProcessing(false)
                    onError(error)
                }
            }
        )
    }
    
    /**
     * 应用代码变更（带预览）
     */
    fun applyChanges(
        diffs: List<FileDiff>,
        previewFirst: Boolean = true,
        onComplete: (List<PatchResult>) -> Unit
    ) {
        if (previewFirst) {
            // 显示预览
            DiffPreview.showDiffChain(project, diffs) {
                // 用户确认后应用
                val results = PatchApplier.apply(project, diffs)
                reportResults(results)
                onComplete(results)
            }
        } else {
            // 直接应用
            val results = PatchApplier.apply(project, diffs)
            reportResults(results)
            onComplete(results)
        }
    }
    
    /**
     * 报告应用结果
     */
    private fun reportResults(results: List<PatchResult>) {
        val successes = results.filterIsInstance<PatchResult.Success>()
        val errors = results.filterIsInstance<PatchResult.Error>()
        val conflicts = results.filterIsInstance<PatchResult.Conflict>()
        
        if (successes.isNotEmpty()) {
            val totalAdd = successes.sumOf { it.additions }
            val totalDel = successes.sumOf { it.deletions }
            NotificationService.success(
                project, 
                "变更已应用", 
                "成功修改 ${successes.size} 个文件 (+$totalAdd/-$totalDel)"
            )
        }
        
        if (errors.isNotEmpty()) {
            NotificationService.error(
                project,
                "部分变更失败",
                errors.joinToString { it.message }
            )
        }
        
        if (conflicts.isNotEmpty()) {
            NotificationService.warning(
                project,
                "存在冲突",
                "${conflicts.size} 个变更存在冲突，需要手动处理"
            )
        }
    }
    
    /**
     * 构建规划提示词
     */
    private fun buildPlanPrompt(request: String, context: ProjectContext?): String {
        val contextSection = if (context != null) {
            """
                === 项目上下文 ===
                ${context.toContextSummary()}
                
                === 文件内容 ===
                ${context.toFullContext()}
            """.trimIndent()
        } else {
            ""
        }
        
        return PromptTemplates.taskPrompt(request, contextSection)
    }
    
    /**
     * 构建 LLM 消息列表
     */
    private fun buildMessages(prompt: String): List<LLMMessage> {
        val messages = mutableListOf<LLMMessage>()
        
        // 系统消息
        messages.add(LLMMessage("system", PromptTemplates.STRUCTURED_OUTPUT_SYSTEM))
        
        // 历史消息
        messages.addAll(session.getMessages().takeLast(10)) // 保留最近10条历史
        
        // 当前提示
        messages.add(LLMMessage("user", prompt))
        
        return messages
    }
    
    /**
     * 获取当前会话状态
     */
    fun getState(): AgentState {
        return if (session.isProcessing()) AgentState.Planning else AgentState.Idle
    }
    
    /**
     * 取消当前操作
     */
    fun cancel() {
        session.setProcessing(false)
        session.clear()
    }
    
    /**
     * 清空会话
     */
    fun clearSession() {
        session.clear()
    }
    
    companion object {
        /**
         * 获取项目实例
         */
        fun getInstance(project: Project): AgentOrchestrator {
            return AgentOrchestrator(project)
        }
    }
}
