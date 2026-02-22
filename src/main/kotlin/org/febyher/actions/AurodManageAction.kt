package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
/**
 * 会话管理操作 - 在 Tools 菜单中打开会话管理面板（Aurod + 本地会话）
 */
class AurodManageAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Febyher AI") ?: return

        toolWindow.show {
            val contentManager = toolWindow.contentManager
            val sessionContent = contentManager.findContent("会话管理")
            if (sessionContent != null) {
                contentManager.setSelectedContent(sessionContent)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
