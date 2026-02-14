package org.febyher.patch

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.febyher.notification.NotificationService

/**
 * Diff 块信息
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

/**
 * Diff 行
 */
data class DiffLine(
    val type: LineType,
    val content: String,
    val lineNumber: Int = 0
)

/**
 * 行类型
 */
enum class LineType {
    CONTEXT,    // 上下文行（空格开头）
    ADD,        // 新增行（+开头）
    REMOVE      // 删除行（-开头）
}

/**
 * 单个文件的 Diff 信息
 */
data class FileDiff(
    val oldPath: String,
    val newPath: String,
    val hunks: List<DiffHunk>,
    val isCreate: Boolean = false,
    val isDelete: Boolean = false
) {
    val hasChanges: Boolean get() = hunks.isNotEmpty()
    
    /**
     * 统计变更
     */
    fun getStats(): DiffStats {
        var additions = 0
        var deletions = 0
        
        for (hunk in hunks) {
            for (line in hunk.lines) {
                when (line.type) {
                    LineType.ADD -> additions++
                    LineType.REMOVE -> deletions++
                    LineType.CONTEXT -> {}
                }
            }
        }
        
        return DiffStats(additions, deletions)
    }
}

/**
 * Diff 统计
 */
data class DiffStats(
    val additions: Int,
    val deletions: Int
) {
    val total: Int get() = additions + deletions
}

/**
 * Diff 解析器
 * 解析 unified diff 格式
 */
object DiffParser {
    
    private val HUNK_HEADER_REGEX = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
    
    /**
     * 解析完整的 diff 文本
     */
    fun parse(diffText: String): List<FileDiff> {
        val files = mutableListOf<FileDiff>()
        val lines = diffText.lines()
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i]
            
            // 查找文件头
            if (line.startsWith("--- ")) {
                val oldPath = parseDiffPath(line.substring(4))
                i++
                
                if (i < lines.size && lines[i].startsWith("+++ ")) {
                    val newPath = parseDiffPath(lines[i].substring(4))
                    i++
                    
                    // 解析 hunks
                    val hunks = mutableListOf<DiffHunk>()
                    while (i < lines.size) {
                        val hunk = parseHunk(lines, i)
                        if (hunk != null) {
                            hunks.add(hunk.first)
                            i = hunk.second
                        } else if (lines[i].startsWith("--- ") || lines[i].startsWith("diff ")) {
                            break
                        } else {
                            i++
                        }
                    }
                    
                    files.add(FileDiff(
                        oldPath = oldPath,
                        newPath = newPath,
                        hunks = hunks,
                        isCreate = oldPath == "/dev/null",
                        isDelete = newPath == "/dev/null"
                    ))
                }
            } else {
                i++
            }
        }
        
        return files
    }
    
    /**
     * 解析单个 hunk
     */
    private fun parseHunk(lines: List<String>, startIndex: Int): Pair<DiffHunk, Int>? {
        val headerMatch = HUNK_HEADER_REGEX.find(lines[startIndex]) ?: return null
        
        val oldStart = headerMatch.groupValues[1].toInt()
        val oldCount = headerMatch.groupValues[2].ifEmpty { "1" }.toInt()
        val newStart = headerMatch.groupValues[3].toInt()
        val newCount = headerMatch.groupValues[4].ifEmpty { "1" }.toInt()
        
        val diffLines = mutableListOf<DiffLine>()
        var i = startIndex + 1
        var oldLineNum = oldStart
        var newLineNum = newStart
        
        while (i < lines.size) {
            val line = lines[i]
            
            // 遇到新的 hunk 或文件头，停止
            if (line.startsWith("@@ ") || line.startsWith("--- ") || line.startsWith("diff ")) {
                break
            }
            
            // 空行可能表示 hunk 结束
            if (line.isEmpty() && diffLines.isNotEmpty()) {
                // 检查是否已达到预期行数
                val contextCount = diffLines.count { it.type == LineType.CONTEXT }
                val addCount = diffLines.count { it.type == LineType.ADD }
                val removeCount = diffLines.count { it.type == LineType.REMOVE }
                
                if (contextCount + removeCount >= oldCount && contextCount + addCount >= newCount) {
                    break
                }
            }
            
            when {
                line.startsWith('+') -> {
                    diffLines.add(DiffLine(LineType.ADD, line.substring(1), newLineNum))
                    newLineNum++
                }
                line.startsWith('-') -> {
                    diffLines.add(DiffLine(LineType.REMOVE, line.substring(1), oldLineNum))
                    oldLineNum++
                }
                line.startsWith(' ') || line.isEmpty() -> {
                    val content = if (line.isEmpty()) "" else line.substring(1)
                    diffLines.add(DiffLine(LineType.CONTEXT, content, oldLineNum))
                    oldLineNum++
                    newLineNum++
                }
                line.startsWith("\\") -> {
                    // No newline at end of file 标记，忽略
                }
                else -> {
                    // 可能是上下文行（没有前导空格的情况）
                    diffLines.add(DiffLine(LineType.CONTEXT, line, oldLineNum))
                    oldLineNum++
                    newLineNum++
                }
            }
            
            i++
        }
        
        return Pair(
            DiffHunk(oldStart, oldCount, newStart, newCount, diffLines),
            i
        )
    }
    
    /**
     * 解析 diff 路径
     */
    private fun parseDiffPath(path: String): String {
        // 移除 a/ 或 b/ 前缀
        return path.removePrefix("a/").removePrefix("b/")
    }
    
    /**
     * 从 AI 响应中提取 diff
     */
    fun extractDiffsFromResponse(response: String): List<FileDiff> {
        // 查找 ```diff 代码块
        val diffBlockRegex = Regex("```diff\\s*\\n([\\s\\S]*?)\\n```")
        val matches = diffBlockRegex.findAll(response)
        
        val allDiffs = mutableListOf<FileDiff>()
        
        for (match in matches) {
            val diffText = match.groupValues[1]
            val diffs = parse(diffText)
            allDiffs.addAll(diffs)
        }
        
        return allDiffs
    }
}
