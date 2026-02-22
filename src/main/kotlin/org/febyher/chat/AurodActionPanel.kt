package org.febyher.chat

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.febyher.llm.aurod.*
import org.febyher.notification.NotificationService
import org.febyher.settings.CopilotSettings
import java.awt.*
import javax.swing.*

/**
 * Aurod AI 操作面板
 * 提供 9 大功能的 UI 操作入口，嵌入到 ChatToolWindow 的标题栏区域
 *
 * 功能列表：
 * 1. 新建会话  2. 启动对话  3. 本地会话历史  4. 已有会话选择
 * 5. 远程会话列表  6. 聊天记录  7. 模型列表  8. 模型更新  9. 删除会话
 */
class AurodActionPanel(private val project: Project) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)) {

    private val logger = Logger.getInstance(AurodActionPanel::class.java)

    // 状态标签
    private val statusLabel = JLabel("未连接").apply {
        font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
        foreground = JBColor.GRAY
    }

    // 当前会话标签
    private val sessionLabel = JLabel("无会话").apply {
        font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
        foreground = JBColor(0x1976D2, 0x64B5F6)
    }

    init {
        background = JBColor.namedColor("Panel.background", Color.WHITE)
        border = JBUI.Borders.empty(2, 8)
        isOpaque = true

        buildUI()
    }

    private fun buildUI() {
        // ① 新建会话
        add(createButton("新建会话", "创建新的 Aurod 对话") { onNewSession() })
        // ② 启动对话 (在 ChatPanel 的发送按钮中已集成，这里提供快捷入口)
        add(createButton("发送", "在当前会话中发送消息") { onStartChat() })
        add(createSeparator())

        // ③ 本地历史  ④ 选择会话  ⑤ 远程列表
        add(createButton("本地历史", "查看本地缓存的会话列表") { onLocalHistory() })
        add(createButton("远程列表", "从服务器获取会话列表") { onRemoteList() })
        add(createSeparator())

        // ⑥ 聊天记录  ⑦ 模型列表  ⑧ 模型更新
        add(createButton("聊天记录", "查看当前会话的聊天记录") { onChatRecords() })
        add(createButton("模型列表", "查看可用模型") { onModelList() })
        add(createButton("刷新模型", "从服务器更新模型列表") { onRefreshModels() })
        add(createSeparator())

        // ⑨ 删除会话
        add(createButton("删除会话", "删除当前选中的会话") { onDeleteSession() })
        add(createSeparator())

        // ⑩ 测试连接（诊断工具）
        add(createButton("测试连接", "诊断网络连接问题") { onTestConnection() })
        add(createSeparator())

        // 状态信息
        add(statusLabel)
        add(Box.createHorizontalStrut(8))
        add(sessionLabel)
    }

    private fun createButton(text: String, tooltip: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
            toolTipText = tooltip
            isFocusPainted = false
            margin = Insets(2, 6, 2, 6)
            addActionListener { action() }
        }
    }

    private fun createSeparator(): JComponent {
        return JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 20)
        }
    }

    // ==================== 辅助方法 ====================

    private fun getSessionManager(): AurodSessionManager = AurodSessionManager.getInstance(project)

    private fun ensureLoggedIn(): Boolean {
        val manager = getSessionManager()
        if (manager.isLoggedIn) return true

        // 尝试自动登录
        val settings = CopilotSettings.getInstance()
        val config = settings.aurodConfig

        if (config.authToken.isBlank()) {
            NotificationService.warning(project, "Aurod 未配置",
                "请先在 Settings > Tools > Febyher AI 中配置 Aurod 认证信息")
            return false
        }

        // 设置认证信息并验证
        manager.apiClient.setAuth(config.authToken, config.cookie, config.uid)
        return manager.isLoggedIn
    }

    private fun updateStatus(text: String, isOk: Boolean = true) {
        SwingUtilities.invokeLater {
            statusLabel.text = text
            statusLabel.foreground = if (isOk) JBColor(0x388E3C, 0x81C784) else JBColor.RED
        }
    }

    private fun updateSessionLabel(session: AurodSession?) {
        SwingUtilities.invokeLater {
            if (session != null) {
                val name = if (session.sessionName.length > 20)
                    session.sessionName.take(20) + "..." else session.sessionName
                sessionLabel.text = "[$name] (${session.model})"
            } else {
                sessionLabel.text = "无会话"
            }
        }
    }

    // ==================== 1. 新建会话 ====================

    private fun onNewSession() {
        if (!ensureLoggedIn()) return

        // 弹出模型选择对话框
        val manager = getSessionManager()
        val models = manager.getCachedModels()?.models?.map { it.value }
            ?: listOf("gpt-5-chat", "claude-sonnet-4-5-20250929-thinking", "deepseek-r1")

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
                    updateSessionLabel(session)
                    updateStatus("已连接")
                    NotificationService.success(project, "会话已创建",
                        "会话 ID: ${session.sessionId}, 模型: ${session.model}")
                } catch (e: Exception) {
                    logger.error("[AurodActionPanel] 创建会话失败", e)
                    updateStatus("创建失败", false)
                    NotificationService.error(project, "创建会话失败", e.message ?: "未知错误")
                }
            }
        })
    }

    // ==================== 2. 启动对话 ====================

    private fun onStartChat() {
        if (!ensureLoggedIn()) return

        val manager = getSessionManager()
        if (manager.currentSession == null) {
            NotificationService.warning(project, "未选择会话", "请先新建或选择一个会话")
            return
        }

        // 弹出输入对话框
        val message = Messages.showInputDialog(
            project,
            "输入消息发送到 Aurod (会话: ${manager.currentSession?.sessionName})：",
            "Aurod 对话",
            null
        ) ?: return

        if (message.isBlank()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Aurod AI 生成中...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val response = manager.chatComplete(message)
                    SwingUtilities.invokeLater {
                        // 将结果作为系统消息显示在 ChatPanel 中
                        findChatPanel()?.let { chatPanel ->
                            chatPanel.receiveExternalMessage("Aurod 回复:\n\n$response")
                        } ?: run {
                            // 如果找不到 ChatPanel，用对话框展示
                            AurodResultDialog(project, "Aurod 对话结果", response).show()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("[AurodActionPanel] 对话失败", e)
                    NotificationService.error(project, "对话失败", e.message ?: "未知错误")
                }
            }
        })
    }

    // ==================== 3. 本地会话历史查看 ====================

    private fun onLocalHistory() {
        if (!ensureLoggedIn()) return

        val sessions = getSessionManager().getLocalSessionHistory()
        if (sessions.isEmpty()) {
            NotificationService.info(project, "本地历史为空", "暂无本地缓存的会话，请先获取远程列表或新建会话")
            return
        }

        showSessionListDialog("本地会话历史", sessions)
    }

    // ==================== 4. 已有会话选择 (集成在 3/5 的列表对话框中) ====================

    // ==================== 5. 远程会话列表获取 ====================

    private fun onRemoteList() {
        if (!ensureLoggedIn()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "获取远程会话列表...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val sessions = getSessionManager().syncRemoteSessions()
                    SwingUtilities.invokeLater {
                        if (sessions.isEmpty()) {
                            NotificationService.info(project, "远程列表为空", "服务器上暂无会话")
                        } else {
                            showSessionListDialog("远程会话列表 (共 ${sessions.size} 个)", sessions)
                        }
                    }
                    updateStatus("已连接")
                } catch (e: Exception) {
                    logger.error("[AurodActionPanel] 获取远程列表失败", e)
                    updateStatus("获取失败", false)
                    NotificationService.error(project, "获取远程列表失败", e.message ?: "未知错误")
                }
            }
        })
    }

    // ==================== 6. 聊天记录查看 ====================

    private fun onChatRecords() {
        if (!ensureLoggedIn()) return

        val manager = getSessionManager()
        val session = manager.currentSession
        if (session == null) {
            NotificationService.warning(project, "未选择会话", "请先选择一个会话再查看聊天记录")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "获取聊天记录...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = manager.getChatRecords(session.sessionId, page = 1, size = 50)
                    SwingUtilities.invokeLater {
                        if (result.messages.isEmpty()) {
                            NotificationService.info(project, "无聊天记录", "当前会话暂无聊天记录")
                        } else {
                            AurodChatRecordsDialog(project, session, result.messages).show()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("[AurodActionPanel] 获取聊天记录失败", e)
                    NotificationService.error(project, "获取聊天记录失败", e.message ?: "未知错误")
                }
            }
        })
    }

    // ==================== 7. 本地模型列表展示 ====================

    private fun onModelList() {
        if (!ensureLoggedIn()) return

        val cached = getSessionManager().getCachedModels()
        if (cached == null || cached.models.isEmpty()) {
            // 没有缓存，自动触发刷新
            onRefreshModels()
            return
        }

        showModelListDialog(cached)
    }

    // ==================== 8. 模型列表更新 ====================

    private fun onRefreshModels() {
        if (!ensureLoggedIn()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "更新模型列表...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = getSessionManager().refreshModels()
                    SwingUtilities.invokeLater {
                        showModelListDialog(result)
                    }
                    NotificationService.success(project, "模型列表已更新", "共 ${result.models.size} 个可用模型")
                } catch (e: Exception) {
                    logger.error("[AurodActionPanel] 刷新模型列表失败", e)
                    
                    val errorMsg = when {
                        e.message?.contains("DNS 解析失败", ignoreCase = true) == true ||
                        e.message?.contains("UnknownHostException", ignoreCase = true) == true -> {
                            buildString {
                                appendLine("无法连接到 Aurod 服务器")
                                appendLine()
                                appendLine("快速解决方案：")
                                appendLine("1. 在浏览器中访问 https://ai.aurod.cn 测试")
                                appendLine("2. 如果浏览器能访问但插件不能，请配置 IDE 代理：")
                                appendLine("   Settings > Appearance & Behavior > System Settings > HTTP Proxy")
                                appendLine("3. 如果浏览器也无法访问，请检查网络连接")
                            }
                        }
                        e.message?.contains("timeout", ignoreCase = true) == true -> {
                            "连接超时\n\n请检查网络连接速度或配置代理"
                        }
                        else -> {
                            "刷新失败: ${e.message}\n\n请检查网络连接和认证状态"
                        }
                    }
                    
                    NotificationService.error(project, "刷新模型列表失败", errorMsg)
                }
            }
        })
    }

    // ==================== 9. 会话删除功能 ====================

    private fun onDeleteSession() {
        if (!ensureLoggedIn()) return

        val manager = getSessionManager()
        val session = manager.currentSession
        if (session == null) {
            NotificationService.warning(project, "未选择会话", "请先选择一个会话再删除")
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            "确定要删除会话「${session.sessionName}」(ID: ${session.sessionId}) 吗？\n此操作不可撤销。",
            "删除 Aurod 会话",
            Messages.getWarningIcon()
        )

        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "删除会话...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val success = manager.deleteSession(session.sessionId)
                    if (success) {
                        updateSessionLabel(null)
                        NotificationService.success(project, "会话已删除", "会话「${session.sessionName}」已删除")
                    } else {
                        NotificationService.error(project, "删除失败", "服务器返回失败")
                    }
                } catch (e: Exception) {
                    logger.error("[AurodActionPanel] 删除会话失败", e)
                    NotificationService.error(project, "删除会话失败", e.message ?: "未知错误")
                }
            }
        })
    }

    // ==================== 10. 测试连接（诊断工具）====================

    /**
     * 测试 Aurod 服务器连接
     */
    private fun onTestConnection() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "测试连接中...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "正在测试与 Aurod 服务器的连接..."

                val manager = getSessionManager()
                val (success, message) = manager.apiClient.testConnection()

                SwingUtilities.invokeLater {
                    if (success) {
                        val fullMessage = buildString {
                            appendLine("✓ 连接测试成功！")
                            appendLine()
                            appendLine(message)
                            appendLine()
                            appendLine("您可以正常使用 Aurod AI 服务。")
                            appendLine()
                            appendLine("如果仍然遇到问题，请检查：")
                            appendLine("• 是否已登录 (配置了认证信息)")
                            appendLine("• 选择的模型是否可用")
                        }
                        Messages.showInfoMessage(project, fullMessage, "连接测试 - 成功")
                        NotificationService.success(project, "连接测试成功", "可以正常使用 Aurod AI")
                    } else {
                        val fullMessage = buildString {
                            appendLine("✗ 连接测试失败")
                            appendLine()
                            appendLine(message)
                            appendLine()
                            appendLine("解决建议：")
                            appendLine("1. 在浏览器中访问 https://ai.aurod.cn 测试网络")
                            appendLine("2. 如果浏览器能访问但插件不能：")
                            appendLine("   → Settings > HTTP Proxy 配置代理")
                            appendLine("3. 检查防火墙/安全软件是否拦截")
                            appendLine("4. 尝试更换 DNS 服务器 (如 8.8.8.8)")
                        }
                        Messages.showErrorDialog(project, fullMessage, "连接测试 - 失败")
                        NotificationService.error(project, "连接测试失败", message)
                    }
                }
            }
        })
    }

    // ==================== 对话框 ====================

    /**
     * 显示会话列表选择对话框（用于功能 3/4/5）
     * 双击或点击"选择"可选中会话
     */
    private fun showSessionListDialog(title: String, sessions: List<AurodSession>) {
        SwingUtilities.invokeLater {
            AurodSessionListDialog(project, title, sessions) { selected ->
                getSessionManager().selectSession(selected)
                updateSessionLabel(selected)
                updateStatus("已连接")

                // 同步到 ChatPanel 的 Aurod 会话栏和 LLMService
                findChatPanel()?.onAurodSessionSelectedFromExternal(selected)

                NotificationService.success(project, "会话已选择",
                    "「${selected.sessionName}」 (${selected.model})")
            }.show()
        }
    }

    /**
     * 显示模型列表对话框（用于功能 7/8）
     */
    private fun showModelListDialog(result: AurodModelListResult) {
        SwingUtilities.invokeLater {
            AurodModelListDialog(project, result).show()
        }
    }

    /**
     * 在工具窗口中查找 ChatPanel 实例
     */
    private fun findChatPanel(): ChatPanel? {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Febyher AI") ?: return null
        val content = toolWindow.contentManager.getContent(0) ?: return null
        return content.component as? ChatPanel
    }
}

// ==================== 会话列表对话框 ====================

/**
 * Aurod 会话列表对话框
 * 支持查看和选择会话
 */
class AurodSessionListDialog(
    private val project: Project,
    title: String,
    private val sessions: List<AurodSession>,
    private val onSelect: (AurodSession) -> Unit
) : DialogWrapper(project) {

    private lateinit var sessionList: JBList<AurodSession>

    init {
        this.title = title
        setOKButtonText("选择")
        setCancelButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val listModel = DefaultListModel<AurodSession>().apply {
            sessions.forEach { addElement(it) }
        }

        sessionList = JBList(listModel).apply {
            cellRenderer = SessionListCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            if (model.size > 0) selectedIndex = 0

            // 双击选择
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        doOKAction()
                    }
                }
            })
        }

        val scrollPane = JBScrollPane(sessionList).apply {
            preferredSize = Dimension(550, 400)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JLabel("共 ${sessions.size} 个会话，双击或点击「选择」使用").apply {
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyBottom(8)
            }, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val selected = sessionList.selectedValue
        if (selected != null) {
            onSelect(selected)
        }
        super.doOKAction()
    }
}

/**
 * 会话列表单元格渲染器
 */
private class SessionListCellRenderer : ListCellRenderer<AurodSession> {
    override fun getListCellRendererComponent(
        list: JList<out AurodSession>,
        value: AurodSession,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            background = if (isSelected) JBColor(0xE3F2FD, 0x1E3A5F) else JBColor.namedColor("Panel.background", Color.WHITE)

            // 上方：会话名称
            val nameLabel = JLabel(value.sessionName).apply {
                font = ChatUiUtils.getChineseFont(Font.BOLD, 13)
                foreground = if (isSelected) JBColor(0x1565C0, 0x90CAF9) else JBColor.foreground()
            }

            // 下方：模型 + 时间 + ID
            val infoText = buildString {
                append("模型: ${value.model}")
                value.createdTime?.let { append("  |  创建: $it") }
                append("  |  ID: ${value.sessionId}")
            }
            val infoLabel = JLabel(infoText).apply {
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
                foreground = JBColor.GRAY
            }

            val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(nameLabel)
                add(Box.createVerticalStrut(3))
                add(infoLabel)
            }

            add(textPanel, BorderLayout.CENTER)
        }
    }
}

// ==================== 聊天记录对话框 ====================

/**
 * 聊天记录查看对话框
 */
class AurodChatRecordsDialog(
    project: Project,
    private val session: AurodSession,
    private val records: List<AurodChatRecord>
) : DialogWrapper(project) {

    init {
        title = "聊天记录 - ${session.sessionName}"
        setOKButtonText("关闭")
        setCancelButtonText("")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        for (record in records) {
            // 用户消息
            if (record.userText.isNotBlank()) {
                panel.add(createRecordBubble("用户", record.userText,
                    JBColor(0xE3F2FD, 0x1E3A5F), record.createdTime))
                panel.add(Box.createVerticalStrut(6))
            }

            // AI 回复
            if (record.aiText.isNotBlank()) {
                panel.add(createRecordBubble("AI (${record.model})", record.aiText,
                    JBColor(0xF5F5F5, 0x3C3C3C), null,
                    "Tokens: ${record.promptTokens}+${record.completionTokens}=${record.useTokens}"))
                panel.add(Box.createVerticalStrut(12))
            }
        }

        val scrollPane = JBScrollPane(panel).apply {
            preferredSize = Dimension(650, 500)
            verticalScrollBar.unitIncrement = 16
        }

        return JPanel(BorderLayout()).apply {
            add(JLabel("会话: ${session.sessionName}  |  模型: ${session.model}  |  共 ${records.size} 条记录").apply {
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(0, 10, 8, 10)
            }, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun createRecordBubble(
        role: String,
        content: String,
        bgColor: JBColor,
        time: String?,
        extra: String? = null
    ): JComponent {
        return JPanel(BorderLayout()).apply {
            background = bgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xE0E0E0, 0x555555), 1),
                JBUI.Borders.empty(8)
            )

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JLabel(role).apply {
                    font = ChatUiUtils.getChineseFont(Font.BOLD, 12)
                    foreground = JBColor(0x1976D2, 0x64B5F6)
                }, BorderLayout.WEST)

                val rightInfo = buildString {
                    time?.let { append(it) }
                    extra?.let { if (isNotEmpty()) append("  |  "); append(it) }
                }
                if (rightInfo.isNotEmpty()) {
                    add(JLabel(rightInfo).apply {
                        font = ChatUiUtils.getChineseFont(Font.PLAIN, 10)
                        foreground = JBColor.GRAY
                    }, BorderLayout.EAST)
                }
            }

            val displayContent = if (content.length > 2000) content.take(2000) + "\n...(内容已截断)" else content
            val contentArea = JTextArea(displayContent).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 13)
                isOpaque = false
                border = JBUI.Borders.emptyTop(4)
            }

            add(header, BorderLayout.NORTH)
            add(contentArea, BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}

// ==================== 模型列表对话框 ====================

/**
 * 模型列表展示对话框
 */
class AurodModelListDialog(
    project: Project,
    private val result: AurodModelListResult
) : DialogWrapper(project) {

    init {
        title = "Aurod 可用模型列表"
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columns = arrayOf("模型名称", "标识", "标签", "积分", "多模态", "备注")
        val data = result.models.map { model ->
            arrayOf(
                model.name,
                model.value,
                model.tag,
                model.integral,
                if (model.multimodal) "是" else "否",
                model.note
            )
        }.toTypedArray()

        val table = javax.swing.JTable(data, columns).apply {
            font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
            rowHeight = 28
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            autoResizeMode = javax.swing.JTable.AUTO_RESIZE_ALL_COLUMNS
            tableHeader.font = ChatUiUtils.getChineseFont(Font.BOLD, 12)
        }

        val scrollPane = JBScrollPane(table).apply {
            preferredSize = Dimension(700, 400)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)

            val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(JLabel("共 ${result.models.size} 个模型").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                })
                add(JLabel("|  默认模型: ${result.defaultModel}").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                    foreground = JBColor(0x1976D2, 0x64B5F6)
                })
                if (result.thinkModel.isNotBlank()) {
                    add(JLabel("|  思考模型: ${result.thinkModel}").apply {
                        font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
                        foreground = JBColor(0x388E3C, 0x81C784)
                    })
                }
                border = JBUI.Borders.emptyBottom(8)
            }

            add(infoPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}

// ==================== 结果展示对话框 ====================

/**
 * 通用结果展示对话框
 */
class AurodResultDialog(
    project: Project,
    dialogTitle: String,
    private val content: String
) : DialogWrapper(project) {

    init {
        title = dialogTitle
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val textArea = JTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = ChatUiUtils.getChineseFont(Font.PLAIN, 13)
            border = JBUI.Borders.empty(10)
        }

        return JBScrollPane(textArea).apply {
            preferredSize = Dimension(600, 400)
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
