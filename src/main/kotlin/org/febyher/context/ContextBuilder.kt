package org.febyher.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

data class FileContext(
    val fileName: String,
    val filePath: String,
    val language: String,
    val content: String,
    val lineCount: Int,
    val tokenEstimate: Int,
    val isSelected: Boolean = true
)

data class ProjectContext(
    val files: List<FileContext>,
    val totalTokens: Int,
    val projectPath: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val fileCount: Int get() = files.size

    fun toContextSummary(): String {
        if (files.isEmpty()) return "No file context"

        val sb = StringBuilder()
        sb.appendLine("=== Project Context (${files.size} files, est. $totalTokens tokens) ===")
        sb.appendLine()

        for (file in files) {
            sb.appendLine("--- ${file.fileName} (${file.language}) ---")
            sb.appendLine("Path: ${file.filePath}")
            sb.appendLine("Lines: ${file.lineCount}, est. tokens: ${file.tokenEstimate}")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun toFullContext(): String {
        if (files.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("=== Project File Content ===")
        sb.appendLine()

        for (file in files) {
            sb.appendLine("--- ${file.fileName} (${file.language}) ---")
            sb.appendLine("```${file.language}")
            sb.appendLine(file.content)
            sb.appendLine("```")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun filterSelected(): ProjectContext = copy(
        files = files.filter { it.isSelected },
        totalTokens = files.filter { it.isSelected }.sumOf { it.tokenEstimate }
    )

    fun compressed(mode: ContextCompressionMode): ProjectContext {
        val compressedFiles = files.map { file ->
            val compact = ContextCompressor.compress(file.content, file.language, mode)
            file.copy(
                content = compact,
                lineCount = compact.lines().size,
                tokenEstimate = ContextBuilder.estimateTokens(compact)
            )
        }
        return copy(
            files = compressedFiles,
            totalTokens = compressedFiles.sumOf { it.tokenEstimate }
        )
    }

    fun fitToTokenLimit(maxTokens: Int): ProjectContext {
        if (totalTokens <= maxTokens) return this

        val kept = mutableListOf<FileContext>()
        var used = 0
        for (file in files) {
            if (used + file.tokenEstimate <= maxTokens || kept.isEmpty()) {
                kept.add(file)
                used += file.tokenEstimate
            }
        }

        return copy(files = kept, totalTokens = kept.sumOf { it.tokenEstimate })
    }
}

class ContextBuilder(private val project: Project) {

    private val files = mutableListOf<FileContext>()
    private val addedPaths = mutableSetOf<String>()
    private var maxTokens = 32000

    fun withMaxTokens(max: Int): ContextBuilder {
        maxTokens = max
        return this
    }

    fun addFile(file: VirtualFile): ContextBuilder {
        if (!addedPaths.add(file.path)) return this

        val language = getLanguageFromFile(file)
        val rawContent = readFileContent(file)
        val compact = ContextCompressor.compress(rawContent, language, ContextCompressionMode.BALANCED)

        files.add(
            FileContext(
                fileName = file.name,
                filePath = file.path,
                language = language,
                content = compact,
                lineCount = compact.lines().size,
                tokenEstimate = estimateTokens(compact)
            )
        )

        return this
    }

    fun addFiles(fileList: List<VirtualFile>): ContextBuilder {
        fileList.forEach { addFile(it) }
        return this
    }

    fun addEditorSelection(): ContextBuilder {
        val context = CodeContext.fromEditor(project)

        val language = context.language ?: "text"
        val rawContent = context.selectedCode ?: context.fileContent ?: ""
        val compact = ContextCompressor.compress(rawContent, language, ContextCompressionMode.BALANCED)

        files.add(
            FileContext(
                fileName = context.fileName ?: "unknown",
                filePath = context.fileName ?: "unknown",
                language = language,
                content = compact,
                lineCount = compact.lines().size,
                tokenEstimate = estimateTokens(compact)
            )
        )

        return this
    }

    fun addProjectStructure(): ContextBuilder {
        val projectFile = project.baseDir ?: return this
        val configFiles = listOf(
            "build.gradle.kts", "build.gradle", "pom.xml",
            "package.json", "Cargo.toml", "go.mod",
            "settings.gradle.kts", "settings.gradle"
        )

        for (child in projectFile.children) {
            if (child.name in configFiles) {
                addFile(child)
            }
        }

        return this
    }

    fun build(): ProjectContext {
        val totalTokens = files.sumOf { it.tokenEstimate }
        val projectPath = project.basePath ?: ""
        return ProjectContext(
            files = files.toList(),
            totalTokens = totalTokens,
            projectPath = projectPath
        )
    }

    fun buildWithLimit(): Pair<ProjectContext, Boolean> {
        val context = build()
        if (context.totalTokens <= maxTokens) {
            return Pair(context, true)
        }

        val reduced = context
            .compressed(ContextCompressionMode.AGGRESSIVE)
            .fitToTokenLimit(maxTokens)

        return Pair(reduced, reduced.totalTokens <= maxTokens)
    }

    private fun readFileContent(file: VirtualFile): String {
        return try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            "[Failed to read file: ${e.message}]"
        }
    }

    private fun getLanguageFromFile(file: VirtualFile): String {
        val extension = file.extension?.lowercase() ?: return "text"
        return when (extension) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js" -> "javascript"
            "ts", "tsx" -> "typescript"
            "rs" -> "rust"
            "go" -> "go"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "header"
            "json" -> "json"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "md" -> "markdown"
            "html" -> "html"
            "css" -> "css"
            "scss", "sass" -> "scss"
            "sql" -> "sql"
            "sh", "bash" -> "bash"
            "gradle" -> "gradle"
            "properties" -> "properties"
            else -> extension
        }
    }

    companion object {
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0

            val chineseChars = text.count { it.code > 0x4E00 && it.code < 0x9FFF }
            val otherChars = text.length - chineseChars
            return (chineseChars / 2 + otherChars / 4 + 1)
        }

        fun create(project: Project): ContextBuilder = ContextBuilder(project)
    }
}
