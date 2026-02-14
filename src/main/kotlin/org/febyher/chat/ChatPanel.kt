package org.febyher.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import org.febyher.context.CodeContext
import org.febyher.llm.LLMServiceManager
import org.febyher.llm.StreamCallback
import org.febyher.notification.NotificationService
import org.febyher.settings.AIProvider
import org.febyher.settings.CopilotSettings
import org.febyher.settings.ProviderDefaultsRegistry
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * 模型选择项（包含Provider信息）
 */
data class ModelItem(val provider: AIProvider, val modelName: String) {
    override fun toString(): String = "${provider.displayName} - $modelName"
}

/**
 * 聊天面板主组件
 */
class ChatPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {

    private val chatSession = ChatSession()

    // UI组件
    private lateinit var messagesPanel: JPanel
    private lateinit var scrollPane: JBScrollPane
    private lateinit var inputTextArea: JBTextArea
    private lateinit var sendButton: JButton
    private lateinit var loadingLabel: JLabel
    private lateinit var modelComboBox: JComboBox<ModelItem>
    
    // 流式响应状态
    @Volatile private var isStreaming = false
    private var streamingContentPane: JTextPane? = null
    private var streamingContent = StringBuilder()

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

        // 输入区域
        val inputPanel = createInputPanel()

        // 主面板
        val mainPanel = JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            add(scrollPane, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }

        setContent(mainPanel)
    }

    private fun createInputPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            
            // 输入文本区域
            inputTextArea = JBTextArea().apply {
                lineWrap = true
                wrapStyleWord = true
                font = getChineseFont(Font.PLAIN, 13)
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
    
    private fun createBottomPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            border = JBUI.Borders.emptyTop(8)
            
            // 左侧：模型选择
            val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                background = JBColor.namedColor("Panel.background", Color.WHITE)
                isOpaque = false
                
                val modelLabel = JLabel("模型:").apply {
                    font = getChineseFont(Font.PLAIN, 12)
                }
                add(modelLabel)
                
                modelComboBox = JComboBox<ModelItem>().apply {
                    font = getChineseFont(Font.PLAIN, 12)
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
                    font = getChineseFont(Font.PLAIN, 12)
                }

                val clearButton = JButton("清空").apply {
                    font = getChineseFont(Font.PLAIN, 12)
                    addActionListener { clearChat() }
                }

                sendButton = JButton("发送").apply {
                    font = getChineseFont(Font.PLAIN, 12)
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
    
    private fun onModelChanged() {
        val selectedItem = modelComboBox.selectedItem as? ModelItem ?: return
        val settings = CopilotSettings.getInstance()
        val provider = selectedItem.provider
        val modelName = selectedItem.modelName
        
        // 检查Provider是否已配置
        if (!settings.isProviderConfigured(provider)) {
            NotificationService.apiKeyNotConfiguredWithAction(project, provider.displayName) {
                // 打开设置面板
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Febyher AI")
            }
            // 恢复之前的选择
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
        val message = inputTextArea.text.trim()
        if (message.isEmpty() || isStreaming) return

        // 添加用户消息到界面
        addMessage(MessageRole.USER, message)
        chatSession.addMessage(MessageRole.USER, message)

        // 清空输入框
        inputTextArea.text = ""

        // 获取代码上下文并发送给AI（流式）
        sendToAIStream(message)
    }

    /**
     * 流式发送请求到AI
     */
    private fun sendToAIStream(userMessage: String) {
        setLoading(true)
        isStreaming = true
        streamingContent.clear()
        
        // 预先创建一个空的AI消息气泡用于流式更新
        val (messageComponent, contentPane) = createStreamingMessageComponent()
        streamingContentPane = contentPane
        
        SwingUtilities.invokeLater {
            messagesPanel.add(messageComponent)
            messagesPanel.add(Box.createVerticalStrut(8))
            messagesPanel.revalidate()
            scrollToBottom()
        }

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

                    // 创建流式回调
                    val callback = object : StreamCallback {
                        override fun onDelta(delta: String) {
                            if (indicator.isCanceled) return
                            
                            streamingContent.append(delta)
                            
                            // 在EDT中更新UI
                            SwingUtilities.invokeLater {
                                streamingContentPane?.let { pane ->
                                    pane.text = convertMarkdownToHtml(streamingContent.toString())
                                    scrollToBottom()
                                }
                            }
                        }
                        
                        override fun onComplete(fullResponse: String) {
                            // 流式完成
                            SwingUtilities.invokeLater {
                                chatSession.addMessage(MessageRole.ASSISTANT, fullResponse)
                            }
                        }
                        
                        override fun onError(error: String) {
                            SwingUtilities.invokeLater {
                                // 更新为错误消息
                                streamingContentPane?.text = convertMarkdownToHtml(error)
                            }
                        }
                    }

                    // 调用流式LLM服务
                    val llmService = LLMServiceManager.getInstance(project).getService()
                    llmService.chatStreamWithContext(userMessage, context, callback)

                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        streamingContentPane?.text = convertMarkdownToHtml("请求失败: ${e.message ?: "未知错误"}")
                    }
                }
            }

            override fun onSuccess() {
                finishStreaming()
            }

            override fun onCancel() {
                finishStreaming()
                NotificationService.operationCancelled(project)
                SwingUtilities.invokeLater {
                    if (streamingContent.isEmpty()) {
                        // 移除空的消息气泡
                        messagesPanel.remove(messagesPanel.componentCount - 1) // 移除间距
                        messagesPanel.remove(messagesPanel.componentCount - 1) // 移除消息
                        messagesPanel.revalidate()
                        messagesPanel.repaint()
                    }
                }
            }
            
            override fun onThrowable(error: Throwable) {
                finishStreaming()
                NotificationService.llmRequestFailed(project, error.message ?: "未知错误")
                SwingUtilities.invokeLater {
                    streamingContentPane?.text = convertMarkdownToHtml("请求失败: ${error.message ?: "未知错误"}")
                }
            }
        })
    }
    
    private fun finishStreaming() {
        SwingUtilities.invokeLater {
            isStreaming = false
            setLoading(false)
            streamingContentPane = null
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }
    }

    private fun addMessage(role: MessageRole, content: String) {
        SwingUtilities.invokeLater {
            val messageComponent = createMessageComponent(role, content)
            messagesPanel.add(messageComponent)
            messagesPanel.add(Box.createVerticalStrut(8))  // 消息间距

            messagesPanel.revalidate()
            messagesPanel.repaint()
            scrollToBottom()
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
    private fun createMessageComponent(role: MessageRole, content: String): JComponent {
        val bgColor = getMessageBgColor(role)
        val accentColor = getMessageAccentColor(role)
        val borderColor = getMessageBorderColor(role)

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

            val roleLabel = JLabel(getRoleDisplayName(role)).apply {
                font = getChineseFont(Font.BOLD, 12)
                foreground = accentColor
            }

            val headerPanel = JPanel(BorderLayout()).apply {
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT
                add(roleLabel, BorderLayout.WEST)
            }

            val contentPane = JTextPane().apply {
                contentType = "text/html"
                text = convertMarkdownToHtml(content)
                isEditable = false
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT
                border = null
                font = getChineseFont(Font.PLAIN, 13)
            }

            bubblePanel.add(headerPanel, BorderLayout.NORTH)
            bubblePanel.add(contentPane, BorderLayout.CENTER)
            add(bubblePanel, BorderLayout.CENTER)
        }
    }
    
    /**
     * 创建流式消息组件（返回组件和内容面板引用）
     */
    private fun createStreamingMessageComponent(): Pair<JComponent, JTextPane> {
        val role = MessageRole.ASSISTANT
        val bgColor = getMessageBgColor(role)
        val accentColor = getMessageAccentColor(role)
        val borderColor = getMessageBorderColor(role)
        
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

            val roleLabel = JLabel(getRoleDisplayName(role)).apply {
                font = getChineseFont(Font.BOLD, 12)
                foreground = accentColor
            }

            val headerPanel = JPanel(BorderLayout()).apply {
                background = bgColor
                isOpaque = false
                add(roleLabel, BorderLayout.WEST)
            }

            contentPane = JTextPane().apply {
                contentType = "text/html"
                text = convertMarkdownToHtml("")
                isEditable = false
                background = bgColor
                isOpaque = false
                border = null
                font = getChineseFont(Font.PLAIN, 13)
            }

            bubblePanel.add(headerPanel, BorderLayout.NORTH)
            bubblePanel.add(contentPane, BorderLayout.CENTER)
            add(bubblePanel, BorderLayout.CENTER)
        }

        return Pair(messageComponent, contentPane)
    }
    
    private fun getMessageBgColor(role: MessageRole): JBColor = when (role) {
        MessageRole.USER -> JBColor(0xE3F2FD, 0x1E3A5F)
        MessageRole.ASSISTANT -> JBColor.namedColor("Panel.background", Color.WHITE)
        MessageRole.SYSTEM -> JBColor(0xFFF8E1, 0x4A3728)
    }
    
    private fun getMessageAccentColor(role: MessageRole): JBColor = when (role) {
        MessageRole.USER -> JBColor(0x1976D2, 0x64B5F6)
        MessageRole.ASSISTANT -> JBColor(0x388E3C, 0x81C784)
        MessageRole.SYSTEM -> JBColor(0xF57C00, 0xFFB74D)
    }
    
    private fun getMessageBorderColor(role: MessageRole): JBColor = when (role) {
        MessageRole.USER -> JBColor(0x90CAF9, 0x1565C0)
        MessageRole.ASSISTANT -> JBColor(0xE0E0E0, 0x555555)
        MessageRole.SYSTEM -> JBColor(0xFFE082, 0x6D4C41)
    }

    private fun getRoleDisplayName(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "用户"
            MessageRole.ASSISTANT -> "AI助手"
            MessageRole.SYSTEM -> "系统"
        }
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        var lastIndex = 0

        val codeBlockPattern = "```(\\w+)?\\n(.*?)\\n```".toRegex(RegexOption.DOT_MATCHES_ALL)

        for (match in codeBlockPattern.findAll(markdown)) {
            val beforeCode = markdown.substring(lastIndex, match.range.first)
            sb.append(processInlineMarkdown(beforeCode))

            val code = match.groupValues[2]
            val escapedCode = escapeHtmlForCode(code)
            sb.append("<pre style='background-color:#2D2D2D;color:#E0E0E0;padding:12px;border-radius:6px;overflow-x:auto;margin:8px 0;font-family:Consolas,Monaco,monospace;font-size:12px;'><code>")
            sb.append(escapedCode)
            sb.append("</code></pre>")

            lastIndex = match.range.last + 1
        }

        if (lastIndex < markdown.length) {
            sb.append(processInlineMarkdown(markdown.substring(lastIndex)))
        }

        var html = sb.toString()

        if (html.isEmpty()) {
            html = processInlineMarkdown(markdown)
        }

        return "<html><body style='font-family:Microsoft YaHei,SimHei,Noto Sans CJK SC,sans-serif;line-height:1.6;font-size:13px;margin:0;padding:0;'>$html</body></html>"
    }

    private fun processInlineMarkdown(text: String): String {
        var html = text

        html = escapeHtmlBasic(html)
        html = processInlineCode(html)
        html = html.replace("\\*\\*(.+?)\\*\\*".toRegex(), "<b>$1</b>")
        html = html.replace("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)".toRegex(), "<i>$1</i>")
        html = html.replace("\n", "<br>")

        return html
    }

    private fun escapeHtmlBasic(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun processInlineCode(text: String): String {
        val sb = StringBuilder()
        var inCode = false
        var codeStart = 0
        var i = 0

        while (i < text.length) {
            if (text[i] == '`') {
                if (!inCode) {
                    sb.append(text.substring(codeStart, i))
                    inCode = true
                    codeStart = i + 1
                } else {
                    val code = text.substring(codeStart, i)
                    sb.append("<code style='background-color:#2D2D2D;color:#E0E0E0;padding:3px 6px;border-radius:4px;font-family:Consolas,Monaco,monospace;font-size:12px;'>")
                    sb.append(escapeHtmlBasic(code))
                    sb.append("</code>")
                    inCode = false
                    codeStart = i + 1
                }
            }
            i++
        }

        if (codeStart < text.length) {
            sb.append(text.substring(codeStart))
        }

        return sb.toString()
    }

    private fun escapeHtmlForCode(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
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
    fun receiveExternalMessage(message: String) {
        if (isStreaming) {
            NotificationService.warning(project, "请稍候", "当前正在处理请求，请等待完成后再发送")
            return
        }
        
        SwingUtilities.invokeLater {
            inputTextArea.text = message
            sendMessage()
        }
    }
    
    /**
     * 将上下文追加到输入框（不自动发送）
     * 允许用户继续输入内容后一并发送
     */
    fun appendContextToInput(contextInfo: String, fileCount: Int, totalTokens: Int) {
        SwingUtilities.invokeLater {
            val currentText = inputTextArea.text.trim()
            val separator = if (currentText.isNotEmpty()) "\n\n" else ""
            
            // 追加上下文到现有内容后面
            inputTextArea.text = "$currentText$separator$contextInfo\n\n请告诉我你想对这些代码做什么？"
            
            // 滚动到输入框底部并获取焦点
            inputTextArea.caretPosition = inputTextArea.text.length
            inputTextArea.requestFocusInWindow()
        }
    }

    override fun dispose() {
        // 清理资源
    }

    companion object {
        fun getChineseFont(style: Int = Font.PLAIN, size: Int): Font {
            val fontNames = arrayOf(
                "Microsoft YaHei",
                "SimHei",
                "SimSun",
                "Noto Sans CJK SC",
                "Source Han Sans SC",
                "WenQuanYi Micro Hei",
                "PingFang SC",
                "Heiti SC",
                "STHeiti"
            )

            for (fontName in fontNames) {
                val font = Font(fontName, style, size)
                if (font.family != "Dialog") {
                    return font
                }
            }

            return Font(Font.DIALOG, style, size)
        }
    }
}
