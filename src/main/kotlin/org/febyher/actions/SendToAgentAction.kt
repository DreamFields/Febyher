package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.febyher.chat.ChatPanel
import org.febyher.notification.NotificationService

/**
 * "发送到 Agent" 右键菜单操作
 * 将选中的代码作为上下文发送给 AI Agent
 */
class SendToAgentAction : AnAction() {
    
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
            NotificationService.warning(project, "无选中代码", "请先选中代码")
            return
        }
        
        val selectedText = selectionModel.selectedText ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val fileName = file?.name ?: "未知文件"
        val language = file?.fileType?.name ?: "未知语言"
        val document = editor.document
        
        // 获取选区行号
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
        
        // 构造上下文信息
        val context = buildContext(fileName, language, startLine, endLine, selectedText)
        
        // 发送到聊天窗口
        sendToChatWindow(project, context)
    }
    
    private fun buildContext(
        fileName: String,
        language: String,
        startLine: Int,
        endLine: Int,
        code: String
    ): String {
        return """
            [上下文信息]
            文件: $fileName
            语言: $language
            行号: $startLine - $endLine
            
            [代码内容]
            ```$language
            $code
            ```
            
            请记住这段代码的上下文，等待我的下一步指令。
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
