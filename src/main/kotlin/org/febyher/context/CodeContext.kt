package org.febyher.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 代码上下文数据类
 */
data class CodeContext(
    val fileName: String? = null,
    val language: String? = null,
    val selectedCode: String? = null,
    val fileContent: String? = null,
    val caretLine: Int? = null,
    val openFiles: List<String> = emptyList()
) {
    companion object {
        /**
         * 从当前编辑器获取代码上下文
         */
        fun fromEditor(project: Project): CodeContext {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

            return CodeContext(
                fileName = virtualFile?.name,
                language = virtualFile?.extension,
                selectedCode = editor?.selectionModel?.selectedText,
                fileContent = editor?.document?.text,
                caretLine = editor?.caretModel?.logicalPosition?.line?.plus(1),
                openFiles = FileEditorManager.getInstance(project).openFiles.map { it.name }
            )
        }

        /**
         * 构建用于LLM的上下文提示
         */
        fun buildContextPrompt(context: CodeContext): String {
            return buildString {
                appendLine("### 当前代码上下文")
                context.fileName?.let { appendLine("- 当前文件: $it") }
                context.language?.let { appendLine("- 语言: $it") }
                context.caretLine?.let { appendLine("- 光标位置: 第 $it 行") }

                if (context.selectedCode != null) {
                    appendLine("\n### 选中的代码")
                    appendLine("```${context.language ?: ""}")
                    appendLine(context.selectedCode)
                    appendLine("```")
                }
            }
        }
    }
}
