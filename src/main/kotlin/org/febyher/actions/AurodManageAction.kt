package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import org.febyher.chat.AurodActionPanel

/**
 * Aurod 管理操作 - 在 Tools 菜单中打开 Aurod 管理面板
 */
class AurodManageAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Febyher AI") ?: return

        // 切换到 Aurod 管理标签页
        toolWindow.show {
            val contentManager = toolWindow.contentManager
            val aurodContent = contentManager.findContent("Aurod 管理")
            if (aurodContent != null) {
                contentManager.setSelectedContent(aurodContent)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
