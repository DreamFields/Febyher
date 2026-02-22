package org.febyher.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Aurod 会话栏 UI 组件
 * 显示当前会话信息，提供「新建会话」「切换会话」按钮，具体逻辑由回调交给 ChatPanel
 */
class AurodSessionBar(
    private val project: Project,
    private val onNewSession: () -> Unit,
    private val onSwitchSession: () -> Unit
) : JPanel(BorderLayout()) {

    private val sessionLabel = JLabel("未选择会话 — 发送消息时将自动创建").apply {
        font = ChatUiUtils.getChineseFont(Font.PLAIN, 12)
        foreground = JBColor(0x616161, 0xB0B0B0)
    }

    init {
        background = JBColor(0xE8F5E9, 0x1B3A1B)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xC8E6C9, 0x2E5B2E)),
            JBUI.Borders.empty(6, 10)
        )

        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JLabel("Aurod").apply {
                font = ChatUiUtils.getChineseFont(Font.BOLD, 12)
                foreground = JBColor(0x2E7D32, 0x81C784)
            })
            add(sessionLabel)
        }

        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(JButton("新建会话").apply {
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
                toolTipText = "创建新的 Aurod 对话会话"
                isFocusPainted = false
                margin = Insets(2, 8, 2, 8)
                addActionListener { onNewSession() }
            })
            add(JButton("切换会话").apply {
                font = ChatUiUtils.getChineseFont(Font.PLAIN, 11)
                toolTipText = "从已有会话列表中选择"
                isFocusPainted = false
                margin = Insets(2, 8, 2, 8)
                addActionListener { onSwitchSession() }
            })
        }

        add(infoPanel, BorderLayout.CENTER)
        add(actionPanel, BorderLayout.EAST)
    }

    /**
     * 更新会话标签文案与颜色
     * @param sessionName 会话名称，null 表示未选择
     * @param model 模型名，null 时忽略
     */
    fun updateLabel(sessionName: String?, model: String?) {
        if (sessionName.isNullOrBlank()) {
            sessionLabel.text = "未选择会话 — 发送消息时将自动创建"
            sessionLabel.foreground = JBColor(0x616161, 0xB0B0B0)
        } else {
            val displayName = if (sessionName.length > 30) sessionName.take(30) + "..." else sessionName
            sessionLabel.text = "会话: $displayName  |  模型: ${model ?: "-"}"
            sessionLabel.foreground = JBColor(0x2E7D32, 0x81C784)
        }
    }
}
