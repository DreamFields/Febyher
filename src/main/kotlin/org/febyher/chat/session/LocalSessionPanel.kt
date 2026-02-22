package org.febyher.chat.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.febyher.chat.ChatPanel
import org.febyher.chat.ChatUiUtils
import org.febyher.llm.LLMServiceManager
import org.febyher.notification.NotificationService
import org.febyher.settings.AIProvider
import org.febyher.settings.CopilotSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

/**
 * 本地会话管理面板（非 Aurod 模型：Moonshot / DeepSeek / NVIDIA）
 * 提供：新建会话、保存当前、会话列表、加载、删除
 */
class LocalSessionPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(LocalSessionPanel::class.java)
    private val storage = LocalSessionStorage.getInstance(project)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    private lateinit var sessionList: JList<LocalChatSession>
    private lateinit var listModel: DefaultListModel<LocalChatSession>

    init {
        background = JBColor.namedColor("Panel.background", java.awt.Color.WHITE)
        border = JBUI.Borders.empty(10)
        buildUI()
    }

    private fun buildUI() {
        listModel = DefaultListModel<LocalChatSession>().apply {
            storage.listSessions().forEach { addElement(it) }
        }
        sessionList = JList(listModel).apply {
            cellRenderer = LocalSessionCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
        }

        val scrollPane = JBScrollPane(sessionList).apply {
            preferredSize = Dimension(400, 280)
        }

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            background = JBColor.namedColor("Panel.background", java.awt.Color.WHITE)
            add(createButton("新建会话") { onNewSession() })
            add(createButton("保存当前会话") { onSaveCurrent() })
            add(createButton("加载") { onLoad() })
            add(createButton("删除") { onDelete() })
            add(createButton("刷新列表") { refreshList() })
        }

        val topLabel = JLabel("本地会话（Kimi / DeepSeek / NVIDIA 等）：按项目存储在 .idea/febyher/chat_sessions.json").apply {
            font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyBottom(6)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", java.awt.Color.WHITE)
            add(topLabel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(buttonsPanel, BorderLayout.SOUTH)
        }
        add(centerPanel, BorderLayout.CENTER)
    }

    private fun createButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
            margin = Insets(4, 12, 4, 12)
            addActionListener { action() }
        }
    }

    private fun findChatPanel(): ChatPanel? {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Febyher AI") ?: return null
        val content = toolWindow.contentManager.getContent(0) ?: return null
        return content.component as? ChatPanel
    }

    private fun onNewSession() {
        val chatPanel = findChatPanel()
        if (chatPanel != null) {
            chatPanel.clearChatForNewLocalSession()
            NotificationService.info(project, "新建会话", "已清空当前对话，可开始新会话")
        } else {
            NotificationService.warning(project, "未找到聊天面板", "请先打开 AI Assistant 标签页")
        }
    }

    private fun onSaveCurrent() {
        val chatPanel = findChatPanel() ?: run {
            NotificationService.warning(project, "未找到聊天面板", "请先打开 AI Assistant 标签页")
            return
        }
        val settings = CopilotSettings.getInstance()
        if (settings.currentProvider == AIProvider.AUROD) {
            NotificationService.warning(project, "当前为 Aurod", "保存本地会话仅支持非 Aurod 模型（Kimi / DeepSeek / NVIDIA）。请先切换模型再保存。")
            return
        }
        val messages = chatPanel.getCurrentMessagesForSave()
        if (messages.isEmpty()) {
            NotificationService.warning(project, "无内容", "当前没有可保存的消息")
            return
        }
        val title = Messages.showInputDialog(project, "输入会话标题：", "保存本地会话", null, "未命名会话", null)
            ?: return
        val session = LocalChatSession(
            id = java.util.UUID.randomUUID().toString(),
            title = title.ifBlank { "未命名会话" },
            provider = settings.currentProvider.name,
            model = settings.getEffectiveModel(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = messages.toMutableList()
        )
        storage.saveSession(session)
        refreshList()
        NotificationService.success(project, "已保存", "会话「${session.title}」已保存到本地")
    }

    private fun onLoad() {
        val selected = sessionList.selectedValue ?: run {
            NotificationService.warning(project, "未选择", "请先在列表中选中一个会话")
            return
        }
        val full = storage.getSession(selected.id) ?: run {
            NotificationService.error(project, "加载失败", "会话不存在或已损坏")
            return
        }
        val chatPanel = findChatPanel() ?: run {
            NotificationService.warning(project, "未找到聊天面板", "请先打开 AI Assistant 标签页")
            return
        }
        val settings = CopilotSettings.getInstance()
        settings.currentProvider = full.providerEnum()
        val config = settings.getProviderConfig(full.providerEnum())
        config.model = full.model
        settings.setProviderConfig(full.providerEnum(), config)
        LLMServiceManager.getInstance(project).refresh()
        chatPanel.loadLocalSession(full.messages)
        NotificationService.success(project, "已加载", "会话「${full.title}」(${full.providerEnum().displayName} - ${full.model})")
    }

    private fun onDelete() {
        val selected = sessionList.selectedValue ?: run {
            NotificationService.warning(project, "未选择", "请先在列表中选中一个会话")
            return
        }
        val confirm = Messages.showYesNoDialog(
            project,
            "确定删除会话「${selected.title}」？此操作不可恢复。",
            "删除本地会话",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return
        storage.deleteSession(selected.id)
        refreshList()
        NotificationService.success(project, "已删除", "会话「${selected.title}」已删除")
    }

    private fun refreshList() {
        listModel.clear()
        storage.listSessions().forEach { listModel.addElement(it) }
    }

    private class LocalSessionCellRenderer : ListCellRenderer<LocalChatSession> {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

        override fun getListCellRendererComponent(
            list: JList<out LocalChatSession>,
            value: LocalChatSession,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            return JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 8)
                background = if (isSelected) JBColor(0xE3F2FD, 0x1E3A5F) else JBColor.namedColor("Panel.background", java.awt.Color.WHITE)
                val nameLabel = JLabel(value.title).apply {
                    font = ChatUiUtils.getChineseFont(Font.BOLD, 13)
                    foreground = if (isSelected) JBColor(0x1565C0, 0x90CAF9) else JBColor.foreground()
                }
                val infoLabel = JLabel("${value.providerEnum().displayName} · ${value.model}  |  ${value.messages.size} 条消息  |  ${dateFormat.format(Date(value.updatedAt))}").apply {
                    font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
                    foreground = JBColor.GRAY
                }
                val textPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(nameLabel)
                    add(Box.createVerticalStrut(2))
                    add(infoLabel)
                }
                add(textPanel, BorderLayout.CENTER)
            }
        }
    }
}
