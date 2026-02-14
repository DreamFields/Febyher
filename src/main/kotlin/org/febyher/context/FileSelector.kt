package org.febyher.context

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import org.febyher.notification.NotificationService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

/**
 * 文件选择结果
 */
data class FileSelectionResult(
    val selectedFiles: List<VirtualFile>,
    val estimatedTokens: Int,
    val cancelled: Boolean = false
)

/**
 * 多文件选择器面板
 */
class FileSelectorPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val fileListModel = DefaultListModel<FileListItem>()
    private val fileList = JBList(fileListModel)
    private val tokenLabel = JLabel("预估 tokens: 0")
    
    private val selectedFiles = mutableSetOf<VirtualFile>()
    private var maxTokens = 32000
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        // 文件列表
        fileList.cellRenderer = FileListCellRenderer()
        fileList.addListSelectionListener { updateTokenCount() }
        
        val scrollPane = JBScrollPane(fileList).apply {
            preferredSize = Dimension(400, 300)
        }
        
        // 工具栏
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            
            add(JButton("添加文件...").apply {
                addActionListener { addFiles() }
            })
            add(Box.createHorizontalStrut(5))
            add(JButton("添加目录...").apply {
                addActionListener { addDirectory() }
            })
            add(Box.createHorizontalStrut(5))
            add(JButton("移除选中").apply {
                addActionListener { removeSelected() }
            })
            add(Box.createHorizontalStrut(5))
            add(JButton("清空").apply {
                addActionListener { clearAll() }
            })
        }
        
        // 底部状态栏
        val statusBar = JPanel(BorderLayout()).apply {
            add(tokenLabel, BorderLayout.WEST)
            add(JButton("全选").apply {
                addActionListener { selectAll() }
            }, BorderLayout.EAST)
        }
        
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
    }
    
    private fun addFiles() {
        val descriptor = FileChooserDescriptor(
            true,  // chooseFiles
            false, // chooseFolders
            false, // chooseJars
            false, // chooseJarsAsFiles
            false, // chooseJarContents
            true   // chooseMultiple - 允许选择多个文件
        ).apply {
            title = "选择文件"
            description = "选择要添加到上下文的文件"
        }
        
        val files = FileChooser.chooseFiles(descriptor, project, null)
        files.forEach { addFile(it) }
        updateTokenCount()
    }
    
    private fun addDirectory() {
        val descriptor = FileChooserDescriptor(
            false, // chooseFiles
            true,  // chooseFolders
            false, // chooseJars
            false, // chooseJarsAsFiles
            false, // chooseJarContents
            false  // chooseMultiple
        ).apply {
            title = "选择目录"
            description = "选择要添加到上下文的目录"
        }
        
        val dirs = FileChooser.chooseFiles(descriptor, project, null)
        dirs.forEach { dir ->
            addDirectoryContents(dir)
        }
        updateTokenCount()
    }
    
    private fun addDirectoryContents(dir: VirtualFile) {
        // 递归添加目录下的代码文件
        val allowedExtensions = setOf(
            "kt", "kts", "java", "py", "js", "ts", "tsx", 
            "rs", "go", "cpp", "c", "h", "hpp", "json", "xml", "yaml", "yml"
        )
        
        val excludePatterns = setOf(
            "node_modules", ".git", ".idea", "build", "target", "dist", "out"
        )
        
        fun processDir(d: VirtualFile) {
            for (child in d.children) {
                if (child.isDirectory) {
                    if (child.name !in excludePatterns) {
                        processDir(child)
                    }
                } else {
                    val ext = child.extension?.lowercase()
                    if (ext in allowedExtensions) {
                        addFile(child)
                    }
                }
            }
        }
        
        processDir(dir)
    }
    
    private fun addFile(file: VirtualFile) {
        if (selectedFiles.contains(file)) return
        
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val tokens = ContextBuilder.estimateTokens(content)
            
            val item = FileListItem(
                file = file,
                fileName = file.name,
                filePath = file.path,
                tokens = tokens,
                lineCount = content.lines().size
            )
            
            fileListModel.addElement(item)
            selectedFiles.add(file)
        } catch (e: Exception) {
            NotificationService.warning(project, "文件读取失败", "无法读取 ${file.name}: ${e.message}")
        }
    }
    
    private fun removeSelected() {
        val selected = fileList.selectedValuesList
        selected.forEach { item ->
            fileListModel.removeElement(item)
            selectedFiles.remove(item.file)
        }
        updateTokenCount()
    }
    
    private fun clearAll() {
        fileListModel.clear()
        selectedFiles.clear()
        updateTokenCount()
    }
    
    private fun selectAll() {
        fileList.selectionModel.setSelectionInterval(0, fileListModel.size() - 1)
    }
    
    private fun updateTokenCount() {
        val totalTokens = fileListModel.elements().toList().sumOf { it.tokens }
        tokenLabel.text = "预估 tokens: $totalTokens / $maxTokens"
        
        if (totalTokens > maxTokens) {
            tokenLabel.foreground = java.awt.Color.RED
        } else {
            tokenLabel.foreground = java.awt.Color.BLACK
        }
    }
    
    /**
     * 获取选中的文件
     */
    fun getSelectedFiles(): List<VirtualFile> {
        return fileList.selectedValuesList.map { it.file }
    }
    
    /**
     * 获取所有文件
     */
    fun getAllFiles(): List<VirtualFile> {
        return selectedFiles.toList()
    }
    
    /**
     * 获取预估 token 数
     */
    fun getEstimatedTokens(): Int {
        return fileListModel.elements().toList().sumOf { it.tokens }
    }
    
    /**
     * 检查是否超出限制
     */
    fun isWithinLimit(): Boolean = getEstimatedTokens() <= maxTokens
    
    /**
     * 设置最大 token 限制
     */
    fun setMaxTokens(max: Int) {
        maxTokens = max
        updateTokenCount()
    }
}

/**
 * 文件列表项
 */
data class FileListItem(
    val file: VirtualFile,
    val fileName: String,
    val filePath: String,
    val tokens: Int,
    val lineCount: Int
)

/**
 * 文件列表渲染器
 */
class FileListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        
        if (value is FileListItem) {
            text = "${value.fileName} (${value.tokens} tokens, ${value.lineCount} 行)"
            toolTipText = value.filePath
        }
        
        return component
    }
}
