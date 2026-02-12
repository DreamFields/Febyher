package org.febyher.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * AI聊天工具窗口工厂
 */
class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)
        
        val content = ContentFactory.getInstance().createContent(
            chatPanel,
            "AI Assistant",
            false
        )
        content.setDisposer(chatPanel)
        
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
