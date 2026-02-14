package org.febyher.patch

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.febyher.notification.NotificationService
import java.io.File

/**
 * Diff 预览器
 * 使用 IntelliJ 内置的 Diff 工具展示差异
 */
object DiffPreview {
    
    /**
     * 显示单个文件的 Diff 预览
     */
    fun showDiff(project: Project, fileDiff: FileDiff, onApply: (() -> Unit)? = null) {
        val filePath = fileDiff.newPath
        val file = findFile(filePath)
        
        if (file == null && !fileDiff.isCreate) {
            NotificationService.warning(project, "文件不存在", "无法找到文件: $filePath")
            return
        }
        
        // 获取原始内容
        val originalContent = if (file != null) {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } else {
            ""
        }
        
        // 计算新内容
        val newContent = applyDiffToContent(originalContent, fileDiff)
        
        // 创建 Diff 内容
        val contentFactory = DiffContentFactory.getInstance()
        
        val content1 = if (file != null) {
            contentFactory.create(project, file) as DocumentContent
        } else {
            contentFactory.create(originalContent)
        }
        
        val content2 = contentFactory.create(newContent)
        
        // 创建 Diff 请求
        val request = SimpleDiffRequest(
            "Diff 预览: ${fileDiff.newPath.substringAfterLast('/')}",
            content1,
            content2,
            "原始文件",
            "修改后"
        )
        
        // 显示 Diff
        val diffManager = DiffManager.getInstance()
        
        if (onApply != null) {
            // 带确认按钮的 Diff
            showDiffWithAction(project, request, onApply)
        } else {
            diffManager.showDiff(project, request)
        }
    }
    
    /**
     * 显示多个文件的 Diff 预览链
     */
    fun showDiffChain(project: Project, diffs: List<FileDiff>, onApplyAll: (() -> Unit)? = null) {
        if (diffs.isEmpty()) {
            NotificationService.info(project, "无变更", "没有可预览的变更")
            return
        }
        
        if (diffs.size == 1) {
            showDiff(project, diffs[0], onApplyAll)
            return
        }
        
        // 创建 Diff 请求链
        val requests = diffs.mapNotNull { fileDiff ->
            createDiffRequest(project, fileDiff)
        }
        
        if (requests.isEmpty()) {
            NotificationService.warning(project, "预览失败", "无法创建任何 Diff 预览")
            return
        }
        
        // 逐个显示每个文件的 Diff
        for (request in requests) {
            DiffManager.getInstance().showDiff(project, request)
        }
    }
    
    /**
     * 创建单个文件的 Diff 请求
     */
    private fun createDiffRequest(project: Project, fileDiff: FileDiff): DiffRequest? {
        val filePath = fileDiff.newPath
        val file = findFile(filePath)
        
        val originalContent = if (file != null) {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } else {
            ""
        }
        
        val newContent = applyDiffToContent(originalContent, fileDiff)
        
        val contentFactory = DiffContentFactory.getInstance()
        
        val content1 = if (file != null) {
            contentFactory.create(project, file)
        } else {
            contentFactory.create(originalContent)
        }
        
        val content2 = contentFactory.create(newContent)
        
        return SimpleDiffRequest(
            fileDiff.newPath.substringAfterLast('/'),
            content1,
            content2,
            "原始",
            "修改后"
        )
    }
    
    /**
     * 将 diff 应用到内容，生成新内容
     */
    private fun applyDiffToContent(originalContent: String, fileDiff: FileDiff): String {
        if (fileDiff.hunks.isEmpty()) return originalContent
        
        val lines = originalContent.lines().toMutableList()
        
        // 从后往前应用 hunk
        val sortedHunks = fileDiff.hunks.sortedByDescending { it.oldStart }
        
        for (hunk in sortedHunks) {
            applyHunkToLines(lines, hunk)
        }
        
        return lines.joinToString("\n")
    }
    
    /**
     * 应用单个 hunk 到行列表
     */
    private fun applyHunkToLines(lines: MutableList<String>, hunk: DiffHunk) {
        var lineIndex = hunk.oldStart - 1
        
        // 收集变更
        val toRemove = mutableListOf<Int>()
        val toAdd = mutableListOf<Pair<Int, String>>()
        var insertIndex = lineIndex
        
        for (diffLine in hunk.lines) {
            when (diffLine.type) {
                LineType.CONTEXT -> {
                    insertIndex++
                    lineIndex++
                }
                LineType.REMOVE -> {
                    if (lineIndex < lines.size) {
                        toRemove.add(lineIndex)
                    }
                    lineIndex++
                }
                LineType.ADD -> {
                    toAdd.add(Pair(insertIndex, diffLine.content))
                    insertIndex++
                }
            }
        }
        
        // 先删除
        toRemove.sortedDescending().forEach { idx ->
            if (idx < lines.size) {
                lines.removeAt(idx)
            }
        }
        
        // 再添加
        for ((idx, content) in toAdd.sortedBy { it.first }) {
            val adjustedIdx = minOf(idx, lines.size)
            lines.add(adjustedIdx, content)
        }
    }
    
    /**
     * 显示带操作按钮的 Diff
     */
    private fun showDiffWithAction(project: Project, request: DiffRequest, onApply: () -> Unit) {
        // 使用标准 Diff 窗口
        DiffManager.getInstance().showDiff(project, request)
        
        // 提示用户是否应用
        NotificationService.showWithAction(
            project,
            "Diff 预览",
            "是否应用此修改？",
            NotificationService.Type.INFO,
            "应用修改"
        ) { onApply() }
    }
    
    /**
     * 查找文件
     */
    private fun findFile(path: String): VirtualFile? {
        val localFs = LocalFileSystem.getInstance()
        
        // 尝试直接路径
        var file = localFs.findFileByPath(path)
        
        if (file == null) {
            // 尝试作为相对路径
            val basePath = "" // project.basePath
            if (basePath != null) {
                file = localFs.findFileByPath("$basePath/$path")
            }
        }
        
        return file
    }
}
