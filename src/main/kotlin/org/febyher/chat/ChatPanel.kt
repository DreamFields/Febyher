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

                loadingLabel = JLabel("思考中...").apply {
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
            addMessage(MessageRole.SYSTEM, "请先在 Settings > Tools > Febyher AI 中配置 ${provider.displayName} 的 API Key")
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
        if (message.isEmpty()) return

        // 添加用户消息到界面
        addMessage(MessageRole.USER, message)
        chatSession.addMessage(MessageRole.USER, message)

        // 清空输入框
        inputTextArea.text = ""

        // 获取代码上下文并发送给AI
        sendToAI(message)
    }

    private fun sendToAI(userMessage: String) {
        setLoading(true)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI 思考中...", true) {
            private var response: String = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    // 获取当前代码上下文
                    val context = try {
                        CodeContext.fromEditor(project)
                    } catch (e: Exception) {
                        null
                    }

                    // 调用LLM服务
                    val llmService = LLMServiceManager.getInstance(project).getService()
                    response = llmService.chatWithContext(userMessage, context)

                } catch (e: Exception) {
                    response = "请求失败: ${e.message ?: "未知错误"}"
                }
            }

            override fun onSuccess() {
                // 添加AI回复到界面
                addMessage(MessageRole.ASSISTANT, response)
                chatSession.addMessage(MessageRole.ASSISTANT, response)
                setLoading(false)
            }

            override fun onCancel() {
                addMessage(MessageRole.SYSTEM, "请求已取消")
                setLoading(false)
            }
        })
    }

    private fun addMessage(role: MessageRole, content: String) {
        SwingUtilities.invokeLater {
            val messageComponent = createMessageComponent(role, content)
            messagesPanel.add(messageComponent)
            messagesPanel.add(Box.createVerticalStrut(8))  // 消息间距

            messagesPanel.revalidate()
            messagesPanel.repaint()

            // 滚动到底部
            SwingUtilities.invokeLater {
                scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
            }
        }
    }

    private fun createMessageComponent(role: MessageRole, content: String): JComponent {
        // 定义颜色方案
        val bgColor = when (role) {
            MessageRole.USER -> JBColor(0xE3F2FD, 0x1E3A5F)      // 蓝色系
            MessageRole.ASSISTANT -> JBColor.namedColor("Panel.background", Color.WHITE)  // 透明背景
            MessageRole.SYSTEM -> JBColor(0xFFF8E1, 0x4A3728)     // 黄色系
        }
        
        val accentColor = when (role) {
            MessageRole.USER -> JBColor(0x1976D2, 0x64B5F6)
            MessageRole.ASSISTANT -> JBColor(0x388E3C, 0x81C784)
            MessageRole.SYSTEM -> JBColor(0xF57C00, 0xFFB74D)
        }
        
        val borderColor = when (role) {
            MessageRole.USER -> JBColor(0x90CAF9, 0x1565C0)
            MessageRole.ASSISTANT -> JBColor(0xE0E0E0, 0x555555)
            MessageRole.SYSTEM -> JBColor(0xFFE082, 0x6D4C41)
        }

        return JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", Color.WHITE)
            isOpaque = true
            
            // 消息气泡 - 占满整个宽度
            val bubblePanel = JPanel(BorderLayout(0, 8)).apply {
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT  // AI回复透明
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1),
                    JBUI.Borders.empty(12)
                )
            }

            // 角色标签行
            val roleLabel = JLabel(getRoleDisplayName(role)).apply {
                font = getChineseFont(Font.BOLD, 12)
                foreground = accentColor
            }

            val headerPanel = JPanel(BorderLayout()).apply {
                background = bgColor
                isOpaque = role != MessageRole.ASSISTANT
                add(roleLabel, BorderLayout.WEST)
            }

            // 内容区域 - 使用JTextPane支持HTML格式化
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

            // 气泡占满整个宽度
            add(bubblePanel, BorderLayout.CENTER)
        }
    }

    private fun getRoleDisplayName(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "用户"
            MessageRole.ASSISTANT -> "AI助手"
            MessageRole.SYSTEM -> "系统"
        }
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        // 简单的Markdown转HTML
        // 注意：处理顺序很重要，先处理代码块，再处理其他

        val sb = StringBuilder()
        var lastIndex = 0

        // 代码块模式
        val codeBlockPattern = "```(\\w+)?\\n(.*?)\\n```".toRegex(RegexOption.DOT_MATCHES_ALL)

        for (match in codeBlockPattern.findAll(markdown)) {
            // 处理代码块之前的内容
            val beforeCode = markdown.substring(lastIndex, match.range.first)
            sb.append(processInlineMarkdown(beforeCode))

            // 处理代码块
            val code = match.groupValues[2]
            val escapedCode = escapeHtmlForCode(code)
            sb.append("<pre style='background-color:#2D2D2D;color:#E0E0E0;padding:12px;border-radius:6px;overflow-x:auto;margin:8px 0;font-family:Consolas,Monaco,monospace;font-size:12px;'><code>")
            sb.append(escapedCode)
            sb.append("</code></pre>")

            lastIndex = match.range.last + 1
        }

        // 处理剩余内容
        if (lastIndex < markdown.length) {
            sb.append(processInlineMarkdown(markdown.substring(lastIndex)))
        }

        var html = sb.toString()

        // 如果没有代码块，处理整个内容
        if (html.isEmpty()) {
            html = processInlineMarkdown(markdown)
        }

        return "<html><body style='font-family:Microsoft YaHei,SimHei,Noto Sans CJK SC,sans-serif;line-height:1.6;font-size:13px;margin:0;padding:0;'>$html</body></html>"
    }

    /**
     * 处理行内Markdown（代码块之外的）
     */
    private fun processInlineMarkdown(text: String): String {
        var html = text

        // 首先转义HTML特殊字符
        html = escapeHtmlBasic(html)

        // 行内代码（先处理，避免与其他格式冲突）
        html = processInlineCode(html)

        // 粗体
        html = html.replace("\\*\\*(.+?)\\*\\*".toRegex(), "<b>$1</b>")

        // 斜体（单个星号，但要避免匹配到已经处理的bold）
        html = html.replace("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)".toRegex(), "<i>$1</i>")

        // 换行
        html = html.replace("\n", "<br>")

        return html
    }

    /**
     * 基本的HTML转义
     */
    private fun escapeHtmlBasic(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /**
     * 处理行内代码
     */
    private fun processInlineCode(text: String): String {
        val sb = StringBuilder()
        var inCode = false
        var codeStart = 0
        var i = 0

        while (i < text.length) {
            if (text[i] == '`') {
                if (!inCode) {
                    // 开始代码
                    sb.append(text.substring(codeStart, i))
                    inCode = true
                    codeStart = i + 1
                } else {
                    // 结束代码
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

        // 添加剩余内容
        if (codeStart < text.length) {
            sb.append(text.substring(codeStart))
        }

        return sb.toString()
    }

    /**
     * 为代码块转义HTML
     */
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
        messagesPanel.removeAll()
        chatSession.clear()
        addWelcomeMessage()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    override fun dispose() {
        // 清理资源
    }

    companion object {
        /**
         * 获取支持中文的字体
         */
        fun getChineseFont(style: Int = Font.PLAIN, size: Int): Font {
            // 尝试使用支持中文的字体
            val fontNames = arrayOf(
                "Microsoft YaHei",      // 微软雅黑 (Windows)
                "SimHei",               // 黑体 (Windows)
                "SimSun",               // 宋体 (Windows)
                "Noto Sans CJK SC",     // Noto Sans (Linux)
                "Source Han Sans SC",   // 思源黑体
                "WenQuanYi Micro Hei",  // 文泉驿 (Linux)
                "PingFang SC",          // 苹方 (macOS)
                "Heiti SC",             // 黑体 (macOS)
                "STHeiti"               // 华文黑体 (macOS)
            )

            for (fontName in fontNames) {
                val font = Font(fontName, style, size)
                if (font.family != "Dialog") {  // 如果字体有效
                    return font
                }
            }

            // 如果都找不到，使用系统默认字体
            return Font(Font.DIALOG, style, size)
        }
    }
}
