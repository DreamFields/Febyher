package org.febyher.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import org.febyher.chat.ChatPanel
import org.febyher.context.ContextBuilder
import org.febyher.context.ProjectContext
import org.febyher.notification.NotificationService
import org.febyher.settings.CopilotSettings

class SelectFilesAction : AnAction() {

    private val allowedExtensions = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx",
        "rs", "go", "cpp", "c", "h", "hpp", "json", "xml", "yaml", "yml",
        "md", "html", "css", "scss", "sql", "sh", "gradle", "properties"
    )

    private val excludePatterns = setOf(
        "node_modules", ".git", ".idea", "build", "target", "dist", "out", ".gradle"
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedRoots = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        if (selectedRoots.isNullOrEmpty()) {
            NotificationService.info(project, "No Selection", "Select files or folders first.")
            return
        }

        val codeFiles = collectCodeFiles(selectedRoots.toList())
        if (codeFiles.isEmpty()) {
            NotificationService.info(project, "No Supported Files", "No code files were found in selection.")
            return
        }

        val maxTokens = CopilotSettings.getInstance().maxTokens.coerceAtLeast(1024)
        val builder = ContextBuilder.create(project).withMaxTokens(maxTokens).addFiles(codeFiles)
        val (context, isWithinLimit) = builder.buildWithLimit()
        if (!isWithinLimit) {
            NotificationService.contextTooLarge(project, context.totalTokens, maxTokens)
            return
        }

        appendSelectionToChatInput(project, selectedRoots.toList(), context, codeFiles.size)
    }

    private fun collectCodeFiles(files: List<VirtualFile>): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()

        for (file in files) {
            if (file.isDirectory) {
                collectFilesFromDirectory(file, result)
            } else if (isCodeFile(file)) {
                result.add(file)
            }
        }

        return result
    }

    private fun collectFilesFromDirectory(dir: VirtualFile, result: MutableList<VirtualFile>) {
        if (dir.name in excludePatterns) return

        for (child in dir.children) {
            if (child.isDirectory) {
                collectFilesFromDirectory(child, result)
            } else if (isCodeFile(child)) {
                result.add(child)
            }
        }
    }

    private fun isCodeFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in allowedExtensions
    }

    private fun appendSelectionToChatInput(
        project: Project,
        selectedRoots: List<VirtualFile>,
        context: ProjectContext,
        matchedCodeFiles: Int
    ) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Febyher AI")
        if (toolWindow == null) {
            NotificationService.warning(project, "Tool Window Missing", "Open the Febyher AI tool window first.")
            return
        }

        toolWindow.activate {
            val content = toolWindow.contentManager.getContent(0)
            val chatPanel = content?.component as? ChatPanel
            if (chatPanel != null) {
                val displayTags = buildSelectionTags(selectedRoots, matchedCodeFiles)
                val payload = buildPayload(context)
                chatPanel.appendContextToInput(displayTags, payload, context.fileCount, context.totalTokens)
                NotificationService.info(
                    project,
                    "Selection Added",
                    "Added ${selectedRoots.size} item(s), packed ${context.fileCount} file(s) context."
                )
            }
        }
    }

    private fun buildPayload(context: ProjectContext): String {
        return buildString {
            appendLine("### Attached Project Context")
            appendLine(context.toContextSummary())
            appendLine(context.toFullContext())
        }.trim()
    }

    private fun buildSelectionTags(selectedRoots: List<VirtualFile>, matchedCodeFiles: Int): String {
        val fileLabels = selectedRoots
            .filter { !it.isDirectory }
            .map { "`[FILE] ${it.name}`" }
        val folderLabels = selectedRoots
            .filter { it.isDirectory }
            .map { "`[FOLDER] ${it.name}`" }

        return buildString {
            appendLine("### CONTEXT TARGETS")
            if (folderLabels.isNotEmpty()) appendLine(folderLabels.joinToString("  "))
            if (fileLabels.isNotEmpty()) appendLine(fileLabels.joinToString("  "))
            appendLine()
            appendLine("Matched code files: $matchedCodeFiles")
        }.trim()
    }
}
