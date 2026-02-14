package org.febyher.context

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * 文件上下文信息
 */
data class FileContext(
    val fileName: String,
    val filePath: String,
    val language: String,
    val content: String,
    val lineCount: Int,
    val tokenEstimate: Int,
    val isSelected: Boolean = true
)

/**
 * 项目上下文 - 多文件上下文收集器
 */
data class ProjectContext(
    val files: List<FileContext>,
    val totalTokens: Int,
    val projectPath: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val fileCount: Int get() = files.size
    
    /**
     * 生成用于 LLM 的上下文摘要
     */
    fun toContextSummary(): String {
        if (files.isEmpty()) return "无文件上下文"
        
        val sb = StringBuilder()
        sb.appendLine("=== 项目上下文 (${files.size} 个文件, 预估 $totalTokens tokens) ===")
        sb.appendLine()
        
        for (file in files) {
            sb.appendLine("--- ${file.fileName} (${file.language}) ---")
            sb.appendLine("路径: ${file.filePath}")
            sb.appendLine("行数: ${file.lineCount}, 预估 tokens: ${file.tokenEstimate}")
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    /**
     * 生成完整的上下文内容（包含代码）
     */
    fun toFullContext(): String {
        if (files.isEmpty()) return ""
        
        val sb = StringBuilder()
        sb.appendLine("=== 项目文件内容 ===")
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
    
    /**
     * 过滤文件（保留选中的）
     */
    fun filterSelected(): ProjectContext = copy(
        files = files.filter { it.isSelected },
        totalTokens = files.filter { it.isSelected }.sumOf { it.tokenEstimate }
    )
}

/**
 * 上下文构建器 - 收集多文件上下文
 */
class ContextBuilder(private val project: Project) {
    
    private val files = mutableListOf<FileContext>()
    private var maxTokens = 32000  // 默认最大 token 数
    
    /**
     * 设置最大 token 数限制
     */
    fun withMaxTokens(max: Int): ContextBuilder {
        maxTokens = max
        return this
    }
    
    /**
     * 添加单个文件
     */
    fun addFile(file: VirtualFile): ContextBuilder {
        val content = readFileContent(file)
        val tokenEstimate = estimateTokens(content)
        
        files.add(FileContext(
            fileName = file.name,
            filePath = file.path,
            language = getLanguageFromFile(file),
            content = content,
            lineCount = content.lines().size,
            tokenEstimate = tokenEstimate
        ))
        
        return this
    }
    
    /**
     * 添加多个文件
     */
    fun addFiles(fileList: List<VirtualFile>): ContextBuilder {
        fileList.forEach { addFile(it) }
        return this
    }
    
    /**
     * 添加当前编辑器中的选中代码
     */
    fun addEditorSelection(): ContextBuilder {
        val context = CodeContext.fromEditor(project) ?: return this
        
        val content = context.selectedCode ?: context.fileContent ?: ""
        
        files.add(FileContext(
            fileName = context.fileName ?: "unknown",
            filePath = context.fileName ?: "unknown",
            language = context.language ?: "text",
            content = content,
            lineCount = content.lines().size,
            tokenEstimate = estimateTokens(content)
        ))
        
        return this
    }
    
    /**
     * 添加项目结构概览
     */
    fun addProjectStructure(): ContextBuilder {
        // 简化实现：只添加项目根目录的关键配置文件
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
    
    /**
     * 构建 ProjectContext
     */
    fun build(): ProjectContext {
        val totalTokens = files.sumOf { it.tokenEstimate }
        val projectPath = project.basePath ?: ""
        
        return ProjectContext(
            files = files.toList(),
            totalTokens = totalTokens,
            projectPath = projectPath
        )
    }
    
    /**
     * 构建并检查是否超出 token 限制
     */
    fun buildWithLimit(): Pair<ProjectContext, Boolean> {
        val context = build()
        val isWithinLimit = context.totalTokens <= maxTokens
        
        return Pair(context, isWithinLimit)
    }
    
    // ========== 辅助方法 ==========
    
    private fun readFileContent(file: VirtualFile): String {
        return try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            "[无法读取文件: ${e.message}]"
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
            else -> extension
        }
    }
    
    companion object {
        /**
         * 估算文本的 token 数量
         * 简单估算：英文约 4 字符 = 1 token，中文约 2 字符 = 1 token
         */
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            
            val chineseChars = text.count { it.code > 0x4E00 && it.code < 0x9FFF }
            val otherChars = text.length - chineseChars
            
            // 中文约 2 字符 = 1 token，其他约 4 字符 = 1 token
            return (chineseChars / 2 + otherChars / 4 + 1)
        }
        
        /**
         * 快速创建上下文构建器
         */
        fun create(project: Project): ContextBuilder = ContextBuilder(project)
    }
}
