package org.febyher.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import org.febyher.agent.AgentOrchestrator
import org.febyher.agent.AgentState
import org.febyher.context.CodeContext
import org.febyher.context.ContextBuilder
import org.febyher.context.ProjectContext
import org.febyher.llm.LLMServiceManager
import org.febyher.llm.StreamCallback
import org.febyher.llm.aurod.AurodLLMService
import org.febyher.llm.aurod.AurodSession
import org.febyher.llm.aurod.AurodSessionManager
import org.febyher.notification.NotificationService
import org.febyher.chat.session.LocalMessageDto
import org.febyher.settings.AIProvider
import org.febyher.settings.CopilotSettings
import org.febyher.settings.ProviderDefaultsRegistry
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

/**
 * 模型选择项（包含Provider信息）
 */
data class ModelItem(val provider: AIProvider, val modelName: String) {
    override fun toString(): String = "${provider.displayName} - $modelName"
}

/**
 * 聊天面板主组件
 */
class ChatPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable, ChatFacade {

    private val logger = Logger.getInstance(ChatPanel::class.java)
    private val chatSession = ChatSession()

    // UI组件
    private lateinit var messagesPanel: JPanel
    private lateinit var scrollPane: JBScrollPane
    private lateinit var inputTextArea: JBTextArea
    private lateinit var sendButton: JButton
    private lateinit var loadingLabel: JLabel
    private lateinit var modelComboBox: JComboBox<ModelItem>
    private var pendingSelectionContextPayload: String = ""
    private var pendingSelectionContextDisplay: String = ""

    // Aurod 会话栏（子组件）
    private lateinit var aurodSessionBar: AurodSessionBar

    // 流式响应状态
    @Volatile private var isStreaming = false
    private var streamingContentPane: JTextPane? = null
    private var streamingContent = StringBuilder()
    // 用于避免会话切换或并发流导致旧回调覆盖新 UI
    private val streamIdCounter = AtomicLong(0)
    @Volatile private var activeStreamId = 0L

    init {
        initUI()
        addWelcomeMessage()
    }

    private fun initUI() {
        // 消息显示区域
        messagesPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            border = JBUI.Borders.empty(10)
        }

        scrollPane = JBScrollPane(messagesPanel).apply {
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }

        // Aurod 会话栏（默认隐藏，选择 Aurod Provider 时显示）
        aurodSessionBar = AurodSessionBar(project, { onAurodNewSession() }, { onAurodSwitchSession() })
        aurodSessionBar.isVisible = false

        // 输入区域（内部会触发 onModelChanged，需要 aurodSessionBar 已就绪）
        val inputPanel = createInputPanel()

        // 主面板
        val mainPanel = JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            add(aurodSessionBar, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

        setContent(mainPanel)
    }

    /**
     * 更新 Aurod 会话栏显示
     */
    private fun updateAurodSessionBar() {
        val settings = CopilotSettings.getInstance()
        val isAurod = settings.currentProvider == AIProvider.AUROD
        aurodSessionBar.isVisible = isAurod
        if (isAurod) {
            val session = AurodSessionManager.getInstance(project).currentSession
            aurodSessionBar.updateLabel(session?.sessionName, session?.model)
        }
    }

    /**
     * Aurod 新建会话
     */
    private fun onAurodNewSession() {
        val manager = AurodSessionManager.getInstance(project)
        if (!ensureAurodLoggedIn(manager)) return

        // 获取模型列表用于选择
        val models = manager.getCachedModels()?.models?.map { it.value }
            ?: ProviderDefaultsRegistry.getAvailableModels(AIProvider.AUROD)

        val selectedModel = JOptionPane.showInputDialog(
            this,
            "选择模型创建新会话：",
            "新建 Aurod 会话",
            JOptionPane.QUESTION_MESSAGE,
            null,
            models.toTypedArray(),
            models.firstOrNull()
        ) as? String ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "创建 Aurod 会话...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val session = manager.createSession(selectedModel)
                    syncAurodSessionToService(session)
                    SwingUtilities.invokeLater {
                        clearChat()
                        updateAurodSessionBar()
                        NotificationService.success(project, "会话已创建",
                            "会话: ${session.sessionName}, 模型: ${session.model}")
                    }
                } catch (e: Exception) {
                    logger.error("[ChatPanel] 创建 Aurod 会话失败", e)
                    NotificationService.error(project, "创建会话失败", e.message ?: "未知错误")
                }
            }
        })
    }

    /**
     * Aurod 切换会话 — 从远程/本地列表选择，选择后加载聊天记录
     */
    private fun onAurodSwitchSession() {
        val manager = AurodSessionManager.getInstance(project)

        // 将登录检查和会话列表获取都放到后台线程，避免阻塞 EDT
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "获取会话列表...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // 在后台线程检查登录状态（避免在 EDT 中访问密码存储）
                    if (!ensureAurodLoggedIn(manager)) {
                        return
                    }

                    // 优先远程列表，同时同步到本地
                    val sessions = manager.syncRemoteSessions()
                    SwingUtilities.invokeLater {
                        if (sessions.isEmpty()) {
                            NotificationService.info(project, "无可用会话", "暂无会话，请先新建会话")
                            return@invokeLater
                        }
                        AurodSessionListDialog(project, "选择 Aurod 会话 (共 ${sessions.size} 个)", sessions) { selected ->
                            onAurodSessionSelected(selected)
                        }.show()
                    }
                } catch (e: Exception) {
                    logger.error("[ChatPanel] 获取会话列表失败", e)

                    // 提供更友好的错误提示
                    val errorMsg = when {
                        e is java.net.UnknownHostException ->
                            "无法连接到 Aurod 服务器 (${e.message})\n\n可能原因：\n1. 网络连接问题\n2. 需要配置代理\n3. DNS 解析失败\n\n已回退到本地缓存"
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            "连接超时\n\n请检查网络连接或稍后重试\n\n已回退到本地缓存"
                        else ->
                            "获取会话列表失败: ${e.message}\n\n已回退到本地缓存"
                    }

                    // 回退到本地历史
                    val local = manager.getLocalSessionHistory()
                    if (local.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            NotificationService.warning(project, "网络错误", errorMsg)
                            AurodSessionListDialog(project, "本地会话历史 (共 ${local.size} 个)", local) { selected ->
                                onAurodSessionSelected(selected)
                            }.show()
                        }
                    } else {
                        NotificationService.error(project, "无法连接到 Aurod", errorMsg + "\n\n本地也无缓存会话")
                    }
                }
            }
        })
    }

    /**
     * 选择 Aurod 会话后：同步到 manager、同步到 LLMService、加载聊天记录到面板
     * 同时更新模型选择框以反映会话的模型
     */
    private fun onAurodSessionSelected(session: AurodSession) {
        logger.info("[ChatPanel] 选择Aurod会话: id=${session.sessionId}, name=${session.sessionName}, model=${session.model}")

        val manager = AurodSessionManager.getInstance(project)
        manager.selectSession(session)
        syncAurodSessionToService(session)

        logger.info("[ChatPanel] 会话已同步到manager和LLMService")

        // 更新模型选择框以匹配会话的模型
        runOnEdt {
            // 在模型选择框中找到并选择该会话的模型
            val itemCount = modelComboBox.itemCount
            var found = false
            for (i in 0 until itemCount) {
                val item = modelComboBox.getItemAt(i)
                if (item.provider == AIProvider.AUROD && item.modelName == session.model) {
                    modelComboBox.selectedIndex = i
                    found = true
                    logger.info("[ChatPanel] 模型选择框已更新为: ${item.modelName}")
                    break
                }
            }
            if (!found) {
                logger.warn("[ChatPanel] 未在模型选择框中找到会话模型: ${session.model}")
            }
        }

        // 清空当前面板并加载该会话的聊天记录
        runOnEdt {
            clearChatSilent()
            updateAurodSessionBar()
            logger.info("[ChatPanel] 面板已清空，会话栏已更新")
        }

        // 后台加载聊天记录
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "加载聊天记录...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val records = manager.getChatRecords(session.sessionId, page = 1, size = 50)
                    SwingUtilities.invokeLater {
                        // 将远程聊天记录渲染到面板中
                        for (record in records.messages.reversed()) {
                            if (record.userText.isNotBlank()) {
                                addMessage(MessageRole.USER, record.userText)
                                chatSession.addMessage(MessageRole.USER, record.userText)
                            }
                            if (record.aiText.isNotBlank()) {
                                addMessage(MessageRole.ASSISTANT, record.aiText)
                                chatSession.addMessage(MessageRole.ASSISTANT, record.aiText)
                            }
                        }
                        if (records.messages.isEmpty()) {
                            addMessage(MessageRole.SYSTEM, "会话「${session.sessionName}」已就绪，输入消息开始对话。")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[ChatPanel] 加载聊天记录失败: ${e.message}")
                    SwingUtilities.invokeLater {
                        addMessage(MessageRole.SYSTEM, "会话「${session.sessionName}」已就绪（聊天记录加载失败: ${e.message}）")
                    }
                }
            }
        })
    }

    /**
     * 将 Aurod 会话信息同步到 AurodLLMService
     */
    private fun syncAurodSessionToService(session: AurodSession) {
        logger.info("[ChatPanel] syncAurodSessionToService 开始, sessionId=${session.sessionId}, model=${session.model}")
        val llmService = LLMServiceManager.getInstance(project).getService()
        if (llmService is AurodLLMService) {
            llmService.currentSessionId = session.sessionId
            llmService.currentModel = session.model
            logger.info("[ChatPanel] 会话信息已同步到 AurodLLMService: sessionId=${session.sessionId}, model=${session.model}")
        } else {
            logger.error("[ChatPanel] LLMService 不是 AurodLLMService 类型: ${llmService::class.java.name}")
        }
    }

    /**
     * 确保 Aurod 已登录
     */
    private fun ensureAurodLoggedIn(manager: AurodSessionManager): Boolean {
        if (manager.isLoggedIn) return true

        val settings = CopilotSettings.getInstance()
        val config = settings.aurodConfig
        if (config.authToken.isBlank()) {
            NotificationService.warning(project, "Aurod 未配置",
                "请先在 Settings > Tools > Febyher AI 中配置 Aurod 认证信息")
            return false
        }
        manager.apiClient.setAuth(config.authToken, config.cookie, config.uid)
        return manager.isLoggedIn
    }

    /**
     * 清空面板但不添加欢迎消息（用于切换会话时）
     */
    private fun clearChatSilent() {
        // 重置流式状态，避免旧会话的 UI 更新覆盖新会话
        invalidateActiveStream()
        isStreaming = false
        streamingContent.clear()
        streamingContentPane = null
        clearPendingSelectionContext()
        setLoading(false)

        messagesPanel.removeAll()
        chatSession.clear()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private fun beginNewStream(): Long {
        val id = streamIdCounter.incrementAndGet()
        activeStreamId = id
        return id
    }

    private fun isActiveStream(id: Long): Boolean = id == activeStreamId

    /**
     * 判断流式面板是否仍在当前消息列表中。
     * 避免旧流回调写入已被移除的组件。
     */
    private fun isPaneActive(pane: JTextPane): Boolean {
        return messagesPanel.isAncestorOf(pane) || pane.parent != null
    }

    private fun isAurodProvider(): Boolean {
        val settings = CopilotSettings.getInstance()
        if (settings.currentProvider == AIProvider.AUROD) return true
        return LLMServiceManager.getInstance(project).getService() is AurodLLMService
    }

    private fun resetHtmlDocument(pane: JEditorPane, reason: String) {
        val kit = HTMLEditorKit()
        val doc = kit.createDefaultDocument() as HTMLDocument
        pane.editorKit = kit
        pane.document = doc
        logger.debug("[ChatPanel][AurodRender] resetHtmlDocument reason=$reason pane=${pane.javaClass.simpleName} kit=${kit.javaClass.simpleName} doc=${doc.javaClass.simpleName}")
    }

    private fun invalidateActiveStream() {
        activeStreamId = streamIdCounter.incrementAndGet()
    }

    /**
     * 从外部（Aurod 管理面板）选择会话后的回调
     * 同步会话到 LLMService、更新会话栏、加载聊天记录
     */
    fun onAurodSessionSelectedFromExternal(session: AurodSession) {
        onAurodSessionSelected(session)
    }

    private fun createInputPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            background = JBColor.namedColor("Panel.background", Color.WHITE)

            // 输入文本区域
            inputTextArea = JBTextArea().apply {
                lineWrap = true
                wrapStyleWord = true
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 13)
                rows = 3
                border = JBUI.Borders.empty(8)
                background = JBColor(0xFAFAFA, 0x3C3C3C)

                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown) {
                            return
                        } else if (e.keyCode == KeyEvent.VK_ENTER) {
                            e.consume()
                            sendMessage()
                        }
                    }
                })
            }

            // 输入框滚动面板 - 更明显的边框
            val inputScrollPane = JBScrollPane(inputTextArea).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(0xBDBDBD, 0x616161), 2),
                    JBUI.Borders.empty(4)
                )
                minimumSize = Dimension(0, 60)
                preferredSize = Dimension(0, 80)
                background = JBColor(0xFAFAFA, 0x2D2D2D)
            }

            // 底部面板：左侧模型选择，右侧按钮
            val bottomPanel = createBottomPanel()

            add(inputScrollPane, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
    }

    // Agent 选择相关
    private lateinit var agentComboBox: JComboBox<String>
    private var currentAgent: String = "Chat"  // 默认使用普通聊天模式

    private fun createBottomPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            border = JBUI.Borders.emptyTop(8)

            // 左侧：Agent选择 + 模型选择
            val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                background = JBColor.namedColor("Panel.background", Color.WHITE)
                isOpaque = false

                // Agent 选择
                val agentLabel = JLabel("模式:").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                }
                add(agentLabel)

                agentComboBox = JComboBox<String>().apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                    preferredSize = Dimension(80, 26)
                    addItem("Chat")
                    addItem("Agent")
                    selectedIndex = 0
                    addActionListener { onAgentChanged() }
                }
                add(agentComboBox)

                add(Box.createHorizontalStrut(8))

                val modelLabel = JLabel("模型:").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                }
                add(modelLabel)

                modelComboBox = JComboBox<ModelItem>().apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                    preferredSize = Dimension(200, 26)
                    addActionListener { onModelChanged() }
                }
                add(modelComboBox)

                // 初始化模型列表
                updateModelComboBox()
            }

            // 右侧：按钮
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                background = JBColor.namedColor("Panel.background", Color.WHITE)
                isOpaque = false

                loadingLabel = JLabel("生成中...").apply {
                    isVisible = false
                    foreground = JBColor.GRAY
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                }

                val clearButton = JButton("清空").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                    addActionListener { clearChat() }
                }

                sendButton = JButton("发送").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                    addActionListener { sendMessage() }
                }

                add(loadingLabel)
                add(Box.createHorizontalStrut(10))
                add(clearButton)
                add(sendButton)
            }

            add(modelPanel, BorderLayout.WEST)
            add(buttonPanel, BorderLayout.EAST)
        }
    }

    private fun updateModelComboBox() {
        val settings = CopilotSettings.getInstance()
        val currentProvider = settings.currentProvider
        val currentModel = settings.getProviderConfig(currentProvider).model.ifBlank {
            ProviderDefaultsRegistry.getDefaults(currentProvider).defaultModel
        }

        modelComboBox.removeAllItems()

        // 添加所有Provider的所有模型
        for (provider in AIProvider.values()) {
            val isConfigured = settings.isProviderConfigured(provider)
            val models = ProviderDefaultsRegistry.getAvailableModels(provider)

            for (model in models) {
                val item = ModelItem(provider, model)
                modelComboBox.addItem(item)

                // 设置未配置的模型为灰色
                if (!isConfigured) {
                    // JComboBox不支持单独设置项目颜色，这里通过toString()显示
                }
            }
        }

        // 选择当前模型
        val itemCount = modelComboBox.itemCount
        for (i in 0 until itemCount) {
            val item = modelComboBox.getItemAt(i)
            if (item.provider == currentProvider && item.modelName == currentModel) {
                modelComboBox.selectedIndex = i
                break
            }
        }
    }

    private fun onAgentChanged() {
        currentAgent = agentComboBox.selectedItem as? String ?: "Chat"
        // Agent 模式暂时只做 UI 切换，实际逻辑在发送消息时判断
    }

    private fun onModelChanged() {
        val selectedItem = modelComboBox.selectedItem as? ModelItem ?: return
        val settings = CopilotSettings.getInstance()
        val provider = selectedItem.provider
        val modelName = selectedItem.modelName

        // 检查Provider是否已配置
        if (!settings.isProviderConfigured(provider)) {
            NotificationService.apiKeyNotConfiguredWithAction(project, provider.displayName) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Febyher AI")
            }
            updateModelComboBox()
            return
        }

        // 更新当前Provider
        if (settings.currentProvider != provider) {
            settings.currentProvider = provider
        }

        // 更新模型配置
        val config = settings.getProviderConfig(provider)
        config.model = modelName
        settings.setProviderConfig(provider, config)

        // 刷新LLM服务
        LLMServiceManager.getInstance(project).refresh()

        // Aurod: 更新会话栏可见性，如果有当前会话则同步模型到 LLMService
        updateAurodSessionBar()
        if (provider == AIProvider.AUROD) {
            val manager = AurodSessionManager.getInstance(project)
            val session = manager.currentSession
            if (session != null) {
                // 同步会话时使用当前选择的模型，而不是会话原有的模型
                val llmService = LLMServiceManager.getInstance(project).getService()
                if (llmService is AurodLLMService) {
                    llmService.currentSessionId = session.sessionId
                    llmService.currentModel = modelName  // 使用当前选择的模型
                }
            }
        }
    }

    private fun addWelcomeMessage() {
        val welcomeText = """
            欢迎使用 Febyher AI 助手！

            我可以帮助你：
            - 解释代码逻辑
            - 调试和修复bug
            - 优化代码性能
            - 生成代码片段
            - 回答编程问题

            选中编辑器中的代码，然后在这里提问，我会结合上下文回答你。

            快捷操作：
            - Enter 发送消息
            - Shift+Enter 换行
            - 点击"清空"重置对话
        """.trimIndent()

        addMessage(MessageRole.SYSTEM, welcomeText)
    }

    private fun sendMessage() {
        val inputText = inputTextArea.text.trim()
        if (inputText.isEmpty() || isStreaming) return

        val visibleUserMessage = extractUserInput(inputText)
        val userMessageForDisplay = if (visibleUserMessage.isNotBlank()) visibleUserMessage else inputText
        val userMessageForModel = buildMessageForModel(visibleUserMessage)

        val settings = CopilotSettings.getInstance()
        logger.info(
            "[ChatPanel] 发送消息开始, provider=${settings.currentProvider}, display length=${userMessageForDisplay.length}, model length=${userMessageForModel.length}"
        )

        // Aurod: 如果当前无会话，先自动创建再发送
        if (settings.currentProvider == AIProvider.AUROD) {
            val manager = AurodSessionManager.getInstance(project)
            logger.info("[ChatPanel] Aurod模式, isLoggedIn=${manager.isLoggedIn}, currentSession=${manager.currentSession?.sessionId}")

            if (!ensureAurodLoggedIn(manager)) {
                logger.warn("[ChatPanel] Aurod未登录，发送消息中止")
                return
            }

            if (manager.currentSession == null) {
                // 自动创建会话再发送
                logger.info("[ChatPanel] 当前无会话，触发自动创建")
                autoCreateAurodSessionAndSend(manager, userMessageForDisplay, userMessageForModel)
                return
            } else {
                // 确保 LLMService 同步了 sessionId 和当前选择的模型
                val llmService = LLMServiceManager.getInstance(project).getService()
                if (llmService is AurodLLMService) {
                    val sessionId = manager.currentSession!!.sessionId
                    val model = settings.getEffectiveModel()
                    llmService.currentSessionId = sessionId
                    llmService.currentModel = model
                    logger.info("[ChatPanel] 同步会话到LLMService: sessionId=$sessionId, model=$model")
                } else {
                    logger.error("[ChatPanel] LLMService 不是 AurodLLMService 类型: ${llmService::class.java.name}")
                }
            }
        }

        logger.info("[ChatPanel] 准备调用 doSendMessage")
        doSendMessage(userMessageForDisplay, userMessageForModel)
    }

    /**
     * 自动创建 Aurod 会话后发送消息
     */
    private fun autoCreateAurodSessionAndSend(
        manager: AurodSessionManager,
        displayMessage: String,
        modelMessage: String
    ) {
        val model = CopilotSettings.getInstance().getEffectiveModel()

        // 先显示用户消息
        addMessage(MessageRole.USER, displayMessage)
        chatSession.addMessage(MessageRole.USER, displayMessage)
        inputTextArea.text = ""

        setLoading(true)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "自动创建 Aurod 会话...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val session = manager.createSession(model)
                    syncAurodSessionToService(session)
                    SwingUtilities.invokeLater {
                        updateAurodSessionBar()
                        setLoading(false)
                        // 现在发送消息
                        clearPendingSelectionContext()
                        sendToAIStream(modelMessage)
                    }
                } catch (e: Exception) {
                    logger.error("[ChatPanel] 自动创建 Aurod 会话失败", e)
                    SwingUtilities.invokeLater {
                        setLoading(false)
                        addMessage(MessageRole.SYSTEM, "自动创建会话失败: ${e.message}\n请手动点击「新建会话」后重试。")
                    }
                }
            }
        })
    }

    /**
     * 实际执行发送消息（已确保会话就绪）
     */
    private fun doSendMessage(displayMessage: String, modelMessage: String) {
        logger.info(
            "[ChatPanel] doSendMessage 开始执行, display length=${displayMessage.length}, model length=${modelMessage.length}"
        )
        addMessage(MessageRole.USER, displayMessage)
        chatSession.addMessage(MessageRole.USER, displayMessage)
        inputTextArea.text = ""
        logger.info("[ChatPanel] 用户消息已添加")
        clearPendingSelectionContext()

        // 根据当前模式选择发送方式
        if (currentAgent == "Agent") {
            sendToAgent(modelMessage)
        } else {
            sendToAIStream(modelMessage)
        }
    }

    /**
     * Agent 模式发送请求
     */
    private fun sendToAgent(userMessage: String) {
        logger.info("[ChatPanel] Agent模式发送消息, length=${userMessage.length}")
        setLoading(true)
        isStreaming = true
        streamingContent.clear()
        val streamId = beginNewStream()

        // 预先创建AI消息气泡
        val isAurodRenderer = isAurodProvider()
        val (messageComponent, contentPane) = createStreamingMessageComponent(isAurodRenderer)
        streamingContentPane = contentPane
        val localContentPane = contentPane

        messagesPanel.add(messageComponent)
        messagesPanel.add(Box.createVerticalStrut(8))
        messagesPanel.revalidate()
        scrollToBottom()

        // 构建项目上下文
        val projectContext: ProjectContext? = try {
            ContextBuilder.create(project)
                .addEditorSelection()
                .build()
        } catch (e: Exception) {
            null
        }

        // 调用 AgentOrchestrator
        val orchestrator = AgentOrchestrator.getInstance(project)
        orchestrator.execute(
            userRequest = userMessage,
            context = projectContext,
            onStateChange = { state ->
                SwingUtilities.invokeLater {
                    when (state) {
                        is AgentState.Planning -> {
                            localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml("**正在规划...**")
                        }
                        is AgentState.Coding -> {
                            localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml("**正在生成代码...**")
                        }
                        is AgentState.Error -> {
                            localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml("**错误:** ${state.message}")
                        }
                        else -> {}
                    }
                }
            },
            onDiffGenerated = { diffs ->
                logger.info("[ChatPanel] Agent生成了 ${diffs.size} 个diff")
            },
            onComplete = { response ->
                SwingUtilities.invokeLater {
                    if (!isActiveStream(streamId)) return@invokeLater
                    val html = ChatMessageRenderer.convertMarkdownToHtml(response.rawContent.ifBlank { response.summary })
                    localContentPane.text = html
                    localContentPane.revalidate()
                    localContentPane.repaint()
                    messagesPanel.revalidate()
                    messagesPanel.repaint()
                    scrollToBottom()
                    chatSession.addMessage(MessageRole.ASSISTANT, response.rawContent.ifBlank { response.summary })
                    finishStreaming(streamId)
                }
            },
            onError = { error ->
                SwingUtilities.invokeLater {
                    if (!isActiveStream(streamId)) return@invokeLater
                    localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml("**错误:** $error")
                    finishStreaming(streamId)
                }
            }
        )
    }

    /**
     * 流式发送请求到AI
     */
    private fun sendToAIStream(userMessage: String) {
        logger.info("[ChatPanel] sendToAIStream 开始, message length=${userMessage.length}")
        setLoading(true)
        isStreaming = true
        streamingContent.clear()
        val streamId = beginNewStream()

        val isAurodRenderer = isAurodProvider()

        // 预先创建一个空的AI消息气泡用于流式更新
        val (messageComponent, contentPane) = createStreamingMessageComponent(isAurodRenderer)
        streamingContentPane = contentPane

        if (isAurodRenderer) {
            logger.debug("[ChatPanel][AurodRender] streaming bubble created pane=${contentPane.javaClass.simpleName} contentType=${contentPane.contentType}")
        }

        // 使用局部引用避免竞争条件：即使 streamingContentPane 被清空，局部引用仍然有效
        val localContentPane = contentPane
        val localStreamingContent = streamingContent

        val addMessageBubble = {
            messagesPanel.add(messageComponent)
            messagesPanel.add(Box.createVerticalStrut(8))
            messagesPanel.revalidate()
            scrollToBottom()
            logger.info("[ChatPanel] AI消息气泡已添加到面板")
        }
        if (SwingUtilities.isEventDispatchThread()) {
            addMessageBubble()
        } else {
            SwingUtilities.invokeLater(addMessageBubble)
        }

        logger.info("[ChatPanel] 准备启动后台任务调用LLM服务")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI 生成中...", true) {

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "正在连接 AI 服务..."
                indicator.fraction = 0.1

                try {
                    // 获取当前代码上下文
                    val context = try {
                        CodeContext.fromEditor(project)
                    } catch (e: Exception) {
                        null
                    }

                    indicator.text = "正在生成回复..."
                    indicator.fraction = 0.2

                    // UI 更新优化：使用定时器批量更新，避免频繁刷新 UI 造成卡顿
                    // 同时确保所有增量内容最终都会被显示
                    var lastUpdateTime = 0L
                    val updateIntervalMs = 50L  // 每 50ms 最多更新一次 UI
                    val updateLock = Object()
                    val contentLock = Any()

                    // 创建流式回调
                    val callback = object : StreamCallback {
                        override fun onDelta(delta: String) {
                            if (indicator.isCanceled) return
                            if (!isActiveStream(streamId)) return

                            logger.debug("[ChatPanel] Received delta: length=${delta.length}, preview=${delta.take(50)}")
                            val snapshot = synchronized(contentLock) {
                                localStreamingContent.append(delta)
                                localStreamingContent.toString()
                            }

                            val now = System.currentTimeMillis()
                            val shouldUpdate = synchronized(updateLock) {
                                val elapsed = now - lastUpdateTime
                                if (elapsed >= updateIntervalMs) {
                                    lastUpdateTime = now
                                    true
                                } else {
                                    false
                                }
                            }

                            // 立即更新或跳过（最终 onComplete 会确保显示完整内容）
                            if (shouldUpdate) {
                                SwingUtilities.invokeLater {
                                    if (!isPaneActive(localContentPane)) return@invokeLater
                                    // 使用局部引用 localContentPane，不依赖可能被清空的实例变量
                                    localContentPane.text = ChatMessageRenderer.convertPlainTextToHtml(snapshot)
                                    localContentPane.revalidate()
                                    localContentPane.repaint()
                                }
                            }
                        }

                        override fun onComplete(fullResponse: String) {
                            // 流式完成 — 最终渲染一次确保内容完整
                            logger.info("[ChatPanel] Stream complete: length=${fullResponse.length}")
                            ApplicationManager.getApplication().executeOnPooledThread {
                                val html = if (fullResponse.isBlank()) {
                                    ChatMessageRenderer.convertMarkdownToHtml("（模型未返回内容，请重试）")
                                } else {
                                    ChatMessageRenderer.convertMarkdownToHtml(fullResponse)
                                }
                                SwingUtilities.invokeLater {
                                    if (!isActiveStream(streamId)) return@invokeLater
                                    if (!isPaneActive(localContentPane)) {
                                        logger.info("[ChatPanel] Stream complete: pane not active, will still render")
                                    }
                                    if (isAurodRenderer) {
                                        resetHtmlDocument(localContentPane, "stream-complete")
                                        logger.debug("[ChatPanel][AurodRender] onComplete htmlLen=${html.length} responseLen=${fullResponse.length} pane=${localContentPane.javaClass.simpleName}")
                                    }
                                    logger.info("[ChatPanel] Stream complete: fullResponse=$fullResponse")
                                    localContentPane.text = html
                                    localContentPane.revalidate()
                                    localContentPane.repaint()
                                    messagesPanel.revalidate()
                                    messagesPanel.repaint()
                                    scrollToBottom()
                                    chatSession.addMessage(MessageRole.ASSISTANT, fullResponse)
                                }
                            }
                        }

                        override fun onError(error: String) {
                            logger.error("[ChatPanel] Stream error: $error")
                            val friendlyError = translateErrorMessage(error)
                            SwingUtilities.invokeLater {
                                if (!isActiveStream(streamId)) return@invokeLater
                                if (isAurodRenderer) {
                                    resetHtmlDocument(localContentPane, "stream-error")
                                    logger.debug("[ChatPanel][AurodRender] onError pane=${localContentPane.javaClass.simpleName} errorLen=${friendlyError.length}")
                                }
                                localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml(friendlyError)
                                localContentPane.revalidate()
                                localContentPane.repaint()
                                messagesPanel.revalidate()
                                messagesPanel.repaint()
                            }
                        }
                    }

                    // 调用流式LLM服务
                    val llmService = LLMServiceManager.getInstance(project).getService()
                    llmService.chatStreamWithContext(userMessage, context, callback)

                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        if (!isActiveStream(streamId)) return@invokeLater
                        if (isAurodRenderer) {
                            resetHtmlDocument(localContentPane, "stream-exception")
                            logger.debug("[ChatPanel][AurodRender] onException pane=${localContentPane.javaClass.simpleName} error=${e.javaClass.simpleName}")
                        }
                        localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml("请求失败: ${e.message ?: "未知错误"}")
                        localContentPane.revalidate()
                        localContentPane.repaint()
                        messagesPanel.revalidate()
                        messagesPanel.repaint()
                    }
                }
            }

            override fun onSuccess() {
                finishStreaming(streamId)
            }

            override fun onCancel() {
                finishStreaming(streamId)
                NotificationService.operationCancelled(project)
                SwingUtilities.invokeLater {
                    if (!isActiveStream(streamId)) return@invokeLater
                    if (localStreamingContent.isEmpty()) {
                        // 移除空的消息气泡
                        val count = messagesPanel.componentCount
                        if (count >= 2) {
                            messagesPanel.remove(count - 1) // 移除间距
                            messagesPanel.remove(count - 2) // 移除消息
                        }
                        messagesPanel.revalidate()
                        messagesPanel.repaint()
                    }
                }
            }

            override fun onThrowable(error: Throwable) {
                finishStreaming(streamId)
                NotificationService.llmRequestFailed(project, error.message ?: "未知错误")
                SwingUtilities.invokeLater {
                    if (!isActiveStream(streamId)) return@invokeLater
                    if (isAurodRenderer) {
                        resetHtmlDocument(localContentPane, "stream-throwable")
                        logger.debug("[ChatPanel][AurodRender] onThrowable pane=${localContentPane.javaClass.simpleName} error=${error.javaClass.simpleName}")
                    }
                    localContentPane.text = ChatMessageRenderer.convertMarkdownToHtml("请求失败: ${error.message ?: "未知错误"}")
                    localContentPane.revalidate()
                    localContentPane.repaint()
                    messagesPanel.revalidate()
                    messagesPanel.repaint()
                }
            }
        })
    }

    private fun finishStreaming(streamId: Long) {
        SwingUtilities.invokeLater {
            if (!isActiveStream(streamId)) return@invokeLater
            isStreaming = false
            setLoading(false)
            streamingContentPane = null
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
    }

    private fun addMessage(role: MessageRole, content: String) {
        val addAction = {
            val isAurodRenderer = role == MessageRole.ASSISTANT && isAurodProvider()
            val messageComponent = createMessageComponent(role, content, isAurodRenderer)
            messagesPanel.add(messageComponent)
            messagesPanel.add(Box.createVerticalStrut(8))  // 消息间距

            messagesPanel.revalidate()
            messagesPanel.repaint()
            scrollToBottom()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            addAction()
        } else {
            SwingUtilities.invokeLater(addAction)
        }
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }

    /**
     * 创建静态消息组件
     */
    private fun createMessageComponent(role: MessageRole, content: String, isAurodRenderer: Boolean = false): JComponent {
        val bgColor = ChatMessageRenderer.getMessageBgColor(role)
        val accentColor = ChatMessageRenderer.getMessageAccentColor(role)
        val borderColor = ChatMessageRenderer.getMessageBorderColor(role)

        return JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            isOpaque = true

            val bubblePanel = JPanel(BorderLayout(0, 8)).apply {
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1),
                    JBUI.Borders.empty(12)
                )
            }

            val roleLabel = JLabel(ChatMessageRenderer.getRoleDisplayName(role)).apply {
                font = ChatUiUtils.getChineseFont(Font.BOLD, 12)
                foreground = accentColor
            }

            val headerPanel = JPanel(BorderLayout()).apply {
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT
                add(roleLabel, BorderLayout.WEST)
            }

            val contentPane = JTextPane().apply {
                contentType = "text/html"
                if (isAurodRenderer) {
                    resetHtmlDocument(this, "static-message")
                    logger.debug("[ChatPanel][AurodRender] static message pane=${this.javaClass.simpleName} contentLen=${content.length}")
                }
                text = ChatMessageRenderer.convertMarkdownToHtml(content)
                isEditable = false
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT
                border = null
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 13)
            }

            bubblePanel.add(headerPanel, BorderLayout.NORTH)
            bubblePanel.add(contentPane, BorderLayout.CENTER)
            add(bubblePanel, BorderLayout.CENTER)
        }
    }

    /**
     * 创建流式消息组件（返回组件和内容面板引用）
     */
    private fun createStreamingMessageComponent(isAurodRenderer: Boolean): Pair<JComponent, JTextPane> {
        val role = MessageRole.ASSISTANT
        val bgColor = ChatMessageRenderer.getMessageBgColor(role)
        val accentColor = ChatMessageRenderer.getMessageAccentColor(role)
        val borderColor = ChatMessageRenderer.getMessageBorderColor(role)

        lateinit var contentPane: JTextPane

        val messageComponent = JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            isOpaque = true

            val bubblePanel = JPanel(BorderLayout(0, 8)).apply {
                background = bgColor
                isOpaque = false  // AI回复透明
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1),
                    JBUI.Borders.empty(12)
                )
            }

            val roleLabel = JLabel(ChatMessageRenderer.getRoleDisplayName(role)).apply {
                font = ChatUiUtils.getChineseFont(Font.BOLD, 12)
                foreground = accentColor
            }

            val headerPanel = JPanel(BorderLayout()).apply {
                background = bgColor
                isOpaque = false
                add(roleLabel, BorderLayout.WEST)
            }

            contentPane = JTextPane().apply {
                contentType = "text/html"
                if (isAurodRenderer) {
                    resetHtmlDocument(this, "stream-init")
                }
                text = ChatMessageRenderer.convertMarkdownToHtml("")
                isEditable = false
                background = bgColor
                isOpaque = false
                border = null
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 13)
                // 显式设置前景色，确保文本可见
                foreground = JBColor.namedColor("Label.foreground", JBColor(Color.BLACK, Color.WHITE))
            }

            bubblePanel.add(headerPanel, BorderLayout.NORTH)
            bubblePanel.add(contentPane, BorderLayout.CENTER)
            add(bubblePanel, BorderLayout.CENTER)
        }

        return Pair(messageComponent, contentPane)
    }

    private fun setLoading(loading: Boolean) {
        SwingUtilities.invokeLater {
            loadingLabel.isVisible = loading
            sendButton.isEnabled = !loading
            inputTextArea.isEnabled = !loading
        }
    }

    private fun clearChat() {
        if (isStreaming) return  // 流式响应时不允许清空

        invalidateActiveStream()
        clearPendingSelectionContext()
        messagesPanel.removeAll()
        chatSession.clear()
        addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /**
     * 供会话管理面板「新建会话」调用：清空当前对话并显示欢迎语
     */
    fun clearChatForNewLocalSession() {
        if (isStreaming) return
        invalidateActiveStream()
        clearPendingSelectionContext()
        messagesPanel.removeAll()
        chatSession.clear()
        addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /**
     * 接收来自外部（如右键菜单）的消息
     * 将消息填入输入框并自动发送
     */
    override fun receiveExternalMessage(message: String) {
        if (isStreaming) {
            NotificationService.warning(project, "请稍候", "当前正在处理请求，请等待完成后再发送")
            return
        }

        SwingUtilities.invokeLater {
            clearPendingSelectionContext()
            inputTextArea.text = message
            sendMessage()
        }
    }

    /**
     * 获取当前会话消息（供本地会话保存）
     */
    fun getCurrentMessagesForSave(): List<LocalMessageDto> {
        return chatSession.getMessages().map {
            LocalMessageDto(
                role = when (it.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                },
                content = it.content
            )
        }
    }

    /**
     * 加载本地会话历史到面板（供会话管理面板「加载」使用）
     * 会清空当前展示并填入 messages，不添加欢迎语
     */
    fun loadLocalSession(messages: List<LocalMessageDto>) {
        if (isStreaming) return
        invalidateActiveStream()
        isStreaming = false
        streamingContent.clear()
        streamingContentPane = null
        clearPendingSelectionContext()
        setLoading(false)
        messagesPanel.removeAll()
        chatSession.clear()
        for (msg in messages) {
            val role = when (msg.role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            }
            addMessage(role, msg.content)
            chatSession.addMessage(role, msg.content)
        }
        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollToBottom()
    }

    /**
     * 兼容旧调用：显示文本与真实 payload 相同。
     */
    fun appendContextToInput(contextInfo: String, fileCount: Int, totalTokens: Int) {
        appendContextToInput(contextInfo, contextInfo, fileCount, totalTokens)
    }

    /**
     * 在输入框中仅展示醒目标识；真实上下文作为隐藏 payload 在发送时拼接。
     */
    fun appendContextToInput(displayInfo: String, contextPayload: String, fileCount: Int, totalTokens: Int) {
        SwingUtilities.invokeLater {
            pendingSelectionContextDisplay = mergeContextChunk(pendingSelectionContextDisplay, displayInfo)
            pendingSelectionContextPayload = mergeContextChunk(pendingSelectionContextPayload, contextPayload)

            val currentUserText = extractUserInput(inputTextArea.text)
            val displayBlock = if (pendingSelectionContextDisplay.isBlank()) {
                ""
            } else {
                "[CONTEXT_TAGS]\n$pendingSelectionContextDisplay\n[/CONTEXT_TAGS]\n\n"
            }
            val prompt = if (currentUserText.isBlank()) "请描述你的需求..." else currentUserText
            inputTextArea.text = "$displayBlock$prompt"
            inputTextArea.caretPosition = inputTextArea.text.length
            inputTextArea.requestFocusInWindow()
        }
    }

    private fun buildMessageForModel(userInput: String): String {
        val prompt = if (userInput.isBlank()) "请基于上述上下文进行分析。"
        else userInput

        if (pendingSelectionContextPayload.isBlank()) return prompt

        return buildString {
            appendLine(pendingSelectionContextPayload.trim())
            appendLine()
            appendLine("### User Request")
            appendLine(prompt)
        }.trim()
    }

    private fun extractUserInput(rawInput: String): String {
        val withoutBlock = rawInput.replace(Regex("(?s)\\[CONTEXT_TAGS\\].*?\\[/CONTEXT_TAGS\\]\\s*"), "")
        val text = withoutBlock.trim()
        return if (text == "请描述你的需求...") "" else text
    }

    private fun mergeContextChunk(existing: String, incoming: String): String {
        val incomingTrimmed = incoming.trim()
        if (incomingTrimmed.isBlank()) return existing
        if (existing.isBlank()) return incomingTrimmed
        return "$existing\n\n$incomingTrimmed"
    }

    private fun clearPendingSelectionContext() {
        pendingSelectionContextPayload = ""
        pendingSelectionContextDisplay = ""
    }

    override fun dispose() {
        // 清理资源
    }

    /**
     * 将英文错误代码转换为友好的中文消息
     */
    private fun translateErrorMessage(error: String): String {
        return when {
            error.contains("DNS_RESOLUTION_FAILED") || error.contains("DNS") -> {
                """
                **无法连接到 Aurod 服务器**

                DNS 解析失败，无法解析域名 ai.aurod.cn

                **可能原因：**
                1. 网络连接问题 - 请检查是否能访问外网
                2. DNS 服务器问题 - 请尝试更换 DNS (如 8.8.8.8)
                3. 需要配置代理 - 在某些网络环境下需要代理
                4. 防火墙拦截 - 请检查防火墙/安全软件设置

                **解决方案：**
                • 在浏览器中访问 https://ai.aurod.cn 测试连通性
                • 如需代理，请配置 IDE 的代理设置 (Settings > HTTP Proxy)
                • 尝试在命令行执行: ping ai.aurod.cn
                • 使用 Aurod 操作面板中的"测试连接"功能诊断

                详细错误: $error
                """.trimIndent()
            }
            error.contains("CONNECTION_TIMEOUT") || error.contains("timeout", ignoreCase = true) -> {
                """
                **连接超时**

                无法在规定时间内连接到服务器

                **解决方案：**
                • 检查网络速度
                • 检查防火墙设置
                • 配置代理服务器
                • 稍后重试

                详细错误: $error
                """.trimIndent()
            }
            error.contains("NETWORK_ERROR") -> {
                """
                **网络错误**

                网络连接出现问题

                **解决方案：**
                • 检查网络连接
                • 检查代理设置
                • 稍后重试

                详细错误: $error
                """.trimIndent()
            }
            else -> error
        }
    }
}
