package org.febyher.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * AI聊天工具窗口工厂
 * 包含两个标签页：
 * 1. AI Assistant - 主聊天面板
 * 2. Aurod 管理 - Aurod 会话/模型管理操作面板
 */
class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 标签页 1: 主聊天面板
        val chatPanel = ChatPanel(project)
        val chatContent = ContentFactory.getInstance().createContent(
            chatPanel,
            "AI Assistant",
            false
        )
        chatContent.setDisposer(chatPanel)
        toolWindow.contentManager.addContent(chatContent)

        // 标签页 2: Aurod 管理面板
        val aurodPanel = AurodActionPanel(project)
        val aurodContent = ContentFactory.getInstance().createContent(
            aurodPanel,
            "Aurod 管理",
            false
        )
        toolWindow.contentManager.addContent(aurodContent)
    }

    override fun shouldBeAvailable(project: Project) = true
}
