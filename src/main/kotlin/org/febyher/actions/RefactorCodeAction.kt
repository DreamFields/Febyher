package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.febyher.chat.ChatPanel
import org.febyher.notification.NotificationService

/**
 * "重构代码" 右键菜单操作
 * 请求 AI 对选中的代码进行重构优化
 */
class RefactorCodeAction : AnAction() {
    
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectionModel = editor?.selectionModel
        
        // 只有选中代码时才启用此操作
        e.presentation.isEnabledAndVisible = selectionModel?.hasSelection() == true
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        
        if (!selectionModel.hasSelection()) {
            NotificationService.warning(project, "无选中代码", "请先选中需要重构的代码")
            return
        }
        
        val selectedText = selectionModel.selectedText ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = file?.name ?: "未知文件"
        val language = file?.fileType?.name ?: "未知语言"
        
        // 构造提示词
        val prompt = buildPrompt(fileName, language, selectedText)
        
        // 发送到聊天窗口
        sendToChatWindow(project, prompt)
    }
    
    private fun buildPrompt(fileName: String, language: String, code: String): String {
        return """
            请重构以下 $language 代码，使其更加清晰、高效和可维护：
            
            文件: $fileName
            
            ```$language
            $code
            ```
            
            请提供：
            1. 重构后的代码（使用 unified diff 格式）
            2. 重构的理由和改进说明
            3. 性能或可读性方面的提升
            
            注意：请使用 unified diff 格式输出修改，格式如下：
            ```diff
            --- a/$fileName
            +++ b/$fileName
            @@ -行号,数量 +行号,数量 @@
            -旧代码
            +新代码
            ```
        """.trimIndent()
    }
    
    private fun sendToChatWindow(project: Project, message: String) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Febyher AI")
        
        if (toolWindow == null) {
            NotificationService.warning(project, "窗口未找到", "请先打开 Febyher AI 工具窗口")
            return
        }
        
        toolWindow.activate {
            val content = toolWindow.contentManager.getContent(0)
            val chatPanel = content?.component as? ChatPanel
            
            if (chatPanel != null) {
                chatPanel.receiveExternalMessage(message)
            } else {
                NotificationService.error(project, "发送失败", "无法访问聊天面板")
            }
        }
    }
}
