package org.febyher.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

data class CodeContext(
    val fileName: String? = null,
    val language: String? = null,
    val selectedCode: String? = null,
    val fileContent: String? = null,
    val caretLine: Int? = null,
    val openFiles: List<String> = emptyList()
) {
    companion object {
        fun fromEditor(project: Project): CodeContext {
            val manager = FileEditorManager.getInstance(project)
            val editor = manager.selectedTextEditor
            val virtualFile = manager.selectedFiles.firstOrNull()
            val caretLine = editor?.caretModel?.logicalPosition?.line?.plus(1)
            val fileContent = editor?.document?.text
            val selectedText = editor?.selectionModel?.selectedText

            // If no selection is present, capture a compact snippet around caret.
            val effectiveSelection = if (!selectedText.isNullOrBlank()) {
                selectedText
            } else if (!fileContent.isNullOrBlank() && caretLine != null) {
                ContextCompressor.excerptAroundLine(
                    text = fileContent,
                    line = caretLine,
                    radius = 30,
                    maxChars = 2000
                )
            } else {
                null
            }

            return CodeContext(
                fileName = virtualFile?.name,
                language = virtualFile?.extension,
                selectedCode = effectiveSelection,
                fileContent = fileContent,
                caretLine = caretLine,
                openFiles = manager.openFiles.map { it.name }
            )
        }

        fun buildContextPrompt(context: CodeContext): String {
            return buildString {
                appendLine("### Current Code Context")
                context.fileName?.let { appendLine("- File: $it") }
                context.language?.let { appendLine("- Language: $it") }
                context.caretLine?.let { appendLine("- Caret: line $it") }
                if (context.openFiles.isNotEmpty()) {
                    appendLine("- Open files: ${context.openFiles.take(8).joinToString(", ")}")
                }
                if (!context.selectedCode.isNullOrBlank()) {
                    appendLine()
                    appendLine("### Selected Code")
                    appendLine("```${context.language ?: ""}")
                    appendLine(ContextCompressor.compactForPrompt(context.selectedCode, maxChars = 2000))
                    appendLine("```")
                }
            }
        }
    }
}
