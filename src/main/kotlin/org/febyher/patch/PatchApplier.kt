package org.febyher.patch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import org.febyher.notification.NotificationService
import java.io.IOException

/**
 * Patch 应用结果
 */
sealed class PatchResult {
    data class Success(
        val filePath: String,
        val additions: Int,
        val deletions: Int
    ) : PatchResult()
    
    data class Conflict(
        val filePath: String,
        val reason: String,
        val hunkIndex: Int
    ) : PatchResult()
    
    data class Error(
        val filePath: String,
        val message: String
    ) : PatchResult()
}

/**
 * Patch 应用器
 * 将 diff 应用到实际文件
 */
class PatchApplier(private val project: Project) {
    
    /**
     * 应用单个文件的 diff
     */
    fun applyDiff(fileDiff: FileDiff, previewOnly: Boolean = false): PatchResult {
        if (fileDiff.isDelete) {
            return handleDelete(fileDiff, previewOnly)
        }
        
        if (fileDiff.isCreate) {
            return handleCreate(fileDiff, previewOnly)
        }
        
        return handleModify(fileDiff, previewOnly)
    }
    
    /**
     * 应用多个文件的 diff
     */
    fun applyDiffs(diffs: List<FileDiff>, previewOnly: Boolean = false): List<PatchResult> {
        return diffs.map { applyDiff(it, previewOnly) }
    }
    
    /**
     * 处理文件修改
     */
    private fun handleModify(fileDiff: FileDiff, previewOnly: Boolean): PatchResult {
        val filePath = fileDiff.newPath
        val file = findFile(filePath)
        
        if (file == null) {
            return PatchResult.Error(filePath, "文件不存在: $filePath")
        }
        
        try {
            val originalContent = String(file.contentsToByteArray(), Charsets.UTF_8)
            val originalLines = originalContent.lines().toMutableList()
            
            // 从后往前应用 hunk，避免行号偏移
            val sortedHunks = fileDiff.hunks.sortedByDescending { it.oldStart }
            
            for ((hunkIndex, hunk) in sortedHunks.withIndex()) {
                val result = applyHunk(originalLines, hunk)
                if (result is PatchResult.Conflict) {
                    return result.copy(hunkIndex = hunkIndex)
                }
            }
            
            if (!previewOnly) {
                val newContent = originalLines.joinToString("\n")
                writeFile(file, newContent)
            }
            
            val stats = fileDiff.getStats()
            return PatchResult.Success(filePath, stats.additions, stats.deletions)
            
        } catch (e: Exception) {
            return PatchResult.Error(filePath, e.message ?: "未知错误")
        }
    }
    
    /**
     * 应用单个 hunk
     */
    private fun applyHunk(lines: MutableList<String>, hunk: DiffHunk): PatchResult {
        val startLine = hunk.oldStart - 1 // 转为 0-based 索引
        
        if (startLine < 0 || startLine > lines.size) {
            return PatchResult.Conflict("", "行号超出范围: ${hunk.oldStart}", 0)
        }
        
        // 验证上下文匹配
        var lineIndex = startLine
        var contextMatched = true
        
        for (diffLine in hunk.lines) {
            when (diffLine.type) {
                LineType.CONTEXT -> {
                    if (lineIndex < lines.size) {
                        val actualLine = lines[lineIndex]
                        val expectedLine = diffLine.content
                        // 简单比较（忽略空白差异）
                        if (actualLine.trim() != expectedLine.trim()) {
                            contextMatched = false
                        }
                    }
                    lineIndex++
                }
                LineType.REMOVE -> {
                    if (lineIndex < lines.size) {
                        val actualLine = lines[lineIndex]
                        if (actualLine.trim() != diffLine.content.trim()) {
                            return PatchResult.Conflict("", "删除行不匹配: 期望 '${diffLine.content}', 实际 '$actualLine'", 0)
                        }
                    }
                }
                LineType.ADD -> {
                    // 新增行不需要验证
                }
            }
        }
        
        // 应用变更
        lineIndex = startLine
        val newLines = mutableListOf<String>()
        
        for (diffLine in hunk.lines) {
            when (diffLine.type) {
                LineType.CONTEXT -> {
                    if (lineIndex < lines.size) {
                        newLines.add(lines[lineIndex])
                    }
                    lineIndex++
                }
                LineType.REMOVE -> {
                    // 跳过删除的行
                    lineIndex++
                }
                LineType.ADD -> {
                    newLines.add(diffLine.content)
                }
            }
        }
        
        // 替换原始行
        val endLine = minOf(lineIndex, lines.size)
        repeat(endLine - startLine) {
            if (startLine < lines.size) {
                lines.removeAt(startLine)
            }
        }
        
        for ((i, newLine) in newLines.withIndex()) {
            lines.add(startLine + i, newLine)
        }
        
        return PatchResult.Success("", 0, 0) // 临时返回，会被上层覆盖
    }
    
    /**
     * 处理文件创建
     */
    private fun handleCreate(fileDiff: FileDiff, previewOnly: Boolean): PatchResult {
        val filePath = fileDiff.newPath
        
        if (!previewOnly) {
            // 创建新文件
            val parentPath = filePath.substringBeforeLast('/')
            val fileName = filePath.substringAfterLast('/')
            
            // 这里需要更完整的实现来创建目录和文件
            return PatchResult.Error(filePath, "文件创建功能尚未完全实现")
        }
        
        val stats = fileDiff.getStats()
        return PatchResult.Success(filePath, stats.additions, stats.deletions)
    }
    
    /**
     * 处理文件删除
     */
    private fun handleDelete(fileDiff: FileDiff, previewOnly: Boolean): PatchResult {
        val filePath = fileDiff.oldPath
        
        if (!previewOnly) {
            val file = findFile(filePath)
            if (file != null) {
                try {
                    file.delete(this)
                    return PatchResult.Success(filePath, 0, 0)
                } catch (e: IOException) {
                    return PatchResult.Error(filePath, "删除失败: ${e.message}")
                }
            }
        }
        
        return PatchResult.Success(filePath, 0, 0)
    }
    
    /**
     * 查找文件
     */
    private fun findFile(path: String): VirtualFile? {
        // 尝试相对于项目根目录查找
        val basePath = project.basePath ?: return null
        
        // 移除可能的项目名前缀
        val relativePath = path.removePrefix("$basePath/")
        
        val fullPath = "$basePath/$relativePath"
        return VirtualFileManager.getInstance().findFileByUrl("file://$fullPath")
    }
    
    /**
     * 写入文件内容
     */
    private fun writeFile(file: VirtualFile, content: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                NotificationService.error(project, "写入失败", "无法写入文件 ${file.name}: ${e.message}")
            }
        }
    }
    
    companion object {
        /**
         * 预览 diff 应用结果
         */
        fun preview(project: Project, diffs: List<FileDiff>): List<PatchResult> {
            return PatchApplier(project).applyDiffs(diffs, previewOnly = true)
        }
        
        /**
         * 应用 diff
         */
        fun apply(project: Project, diffs: List<FileDiff>): List<PatchResult> {
            return PatchApplier(project).applyDiffs(diffs, previewOnly = false)
        }
    }
}
