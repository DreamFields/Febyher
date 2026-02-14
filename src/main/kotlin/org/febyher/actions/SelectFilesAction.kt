package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import org.febyher.chat.ChatPanel
import org.febyher.context.ContextBuilder
import org.febyher.notification.NotificationService

/**
 * "添加到AI上下文" 右键菜单操作
 * 将选中的文件或文件夹直接添加到聊天输入框，与现有内容合并后一并发送
 */
class SelectFilesAction : AnAction() {
    
    // 允许的代码文件扩展名
    private val allowedExtensions = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", 
        "rs", "go", "cpp", "c", "h", "hpp", "json", "xml", "yaml", "yml",
        "md", "html", "css", "scss", "sql", "sh", "gradle", "properties"
    )
    
    // 排除的目录
    private val excludePatterns = setOf(
        "node_modules", ".git", ".idea", "build", "target", "dist", "out", ".gradle"
    )
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // 获取用户在项目视图中选中的文件/文件夹
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        
        if (selectedFiles.isNullOrEmpty()) {
            NotificationService.info(project, "未选择文件", "请选择文件或文件夹")
            return
        }
        
        // 收集所有代码文件（包括文件夹内的文件）
        val codeFiles = collectCodeFiles(selectedFiles.toList())
        
        if (codeFiles.isEmpty()) {
            NotificationService.info(project, "无可添加文件", "选中的文件/文件夹中没有可添加的代码文件")
            return
        }
        
        // 构建上下文
        val contextBuilder = ContextBuilder.create(project)
        contextBuilder.addFiles(codeFiles)
        val (context, isWithinLimit) = contextBuilder.buildWithLimit()
        
        if (!isWithinLimit) {
            NotificationService.contextTooLarge(project, context.totalTokens, 32000)
            return
        }
        
        // 发送到聊天窗口（追加到输入框，不自动发送）
        appendContextToChatInput(project, context)
    }
    
    /**
     * 递归收集代码文件
     */
    private fun collectCodeFiles(files: List<VirtualFile>): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        
        for (file in files) {
            if (file.isDirectory) {
                // 递归处理目录
                collectFilesFromDirectory(file, result)
            } else {
                // 直接处理文件
                if (isCodeFile(file)) {
                    result.add(file)
                }
            }
        }
        
        return result
    }
    
    /**
     * 从目录中递归收集代码文件
     */
    private fun collectFilesFromDirectory(dir: VirtualFile, result: MutableList<VirtualFile>) {
        // 跳过排除的目录
        if (dir.name in excludePatterns) return
        
        for (child in dir.children) {
            if (child.isDirectory) {
                collectFilesFromDirectory(child, result)
            } else if (isCodeFile(child)) {
                result.add(child)
            }
        }
    }
    
    /**
     * 判断是否为代码文件
     */
    private fun isCodeFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in allowedExtensions
    }
    
    /**
     * 将上下文追加到聊天输入框（不自动发送）
     */
    private fun appendContextToChatInput(project: Project, context: org.febyher.context.ProjectContext) {
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
                val contextInfo = buildContextInfo(context)
                chatPanel.appendContextToInput(contextInfo, context.fileCount, context.totalTokens)
                NotificationService.contextCollected(project, context.fileCount, context.totalTokens)
            }
        }
    }
    
    /**
     * 构建上下文信息（包含完整代码内容）
     */
    private fun buildContextInfo(context: org.febyher.context.ProjectContext): String {
        val sb = StringBuilder()
        sb.appendLine("[已添加 ${context.fileCount} 个文件作为上下文，共 ${context.totalTokens} tokens]")
        sb.appendLine()
        sb.append(context.toFullContext())
        return sb.toString()
    }
}
