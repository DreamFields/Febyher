package org.febyher.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.febyher.chat.session.LocalSessionPanel
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * 统一会话管理面板
 * - Aurod 会话：通过 API 管理（新建/远程列表/本地缓存/聊天记录/模型/删除/测试连接）
 * - 本地会话：按项目存储的 Moonshot / DeepSeek / NVIDIA 会话（新建/保存/加载/删除）
 */
class SessionManagementPanel(project: Project) : JPanel(BorderLayout()) {

    init {
        background = JBColor.namedColor("Panel.background", Color.WHITE)
        border = JBUI.Borders.empty(0)

        val tabbedPane = JTabbedPane().apply {
            addTab("Aurod 会话", AurodActionPanel(project))
            addTab("本地会话", LocalSessionPanel(project))
        }
        add(tabbedPane, BorderLayout.CENTER)
    }
}
