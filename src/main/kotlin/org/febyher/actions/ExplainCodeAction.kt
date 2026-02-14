package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.febyher.chat.ChatPanel
import org.febyher.notification.NotificationService

/**
 * "解释代码" 右键菜单操作
 * 将选中的代码发送到 AI 进行解释
 */
class ExplainCodeAction : AnAction() {
    
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
            NotificationService.warning(project, "无选中代码", "请先选中需要解释的代码")
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
            请解释以下 $language 代码的功能和逻辑：
            
            文件: $fileName
            
            ```$language
            $code
            ```
            
            请详细说明：
            1. 这段代码的主要功能是什么？
            2. 关键的实现步骤有哪些？
            3. 有什么需要注意的地方或潜在的改进建议？
        """.trimIndent()
    }
    
    private fun sendToChatWindow(project: Project, message: String) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Febyher AI")
        
        if (toolWindow == null) {
            NotificationService.warning(project, "窗口未找到", "请先打开 Febyher AI 工具窗口")
            return
        }
        
        // 激活工具窗口
        toolWindow.activate {
            // 获取 ChatPanel 并发送消息
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
