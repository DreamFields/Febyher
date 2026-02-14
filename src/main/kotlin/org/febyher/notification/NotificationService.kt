package org.febyher.notification

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.NotNull

/**
 * Febyher AI 通知服务
 * 统一管理插件内的所有通知消息
 */
object NotificationService {
    
    private const val NOTIFICATION_GROUP_ID = "Febyher AI Notifications"
    
    /**
     * 通知类型枚举
     */
    enum class Type {
        SUCCESS,    // 成功操作
        INFO,       // 信息提示
        WARNING,    // 警告
        ERROR       // 错误
    }
    
    /**
     * 显示通知消息
     * 
     * @param project 当前项目（可为null表示应用级通知）
     * @param title 通知标题
     * @param content 通知内容
     * @param type 通知类型
     * @param action 可选的操作按钮
     */
    fun show(
        project: Project?,
        title: String,
        content: String,
        type: Type = Type.INFO,
        action: NotificationAction? = null
    ) {
        val notification = createNotification(title, content, type)
        
        action?.let { notification.addAction(it) }
        
        notification.notify(project)
    }
    
    /**
     * 显示成功通知
     */
    fun success(project: Project?, title: String, content: String) {
        show(project, title, content, Type.SUCCESS)
    }
    
    /**
     * 显示信息通知
     */
    fun info(project: Project?, title: String, content: String) {
        show(project, title, content, Type.INFO)
    }
    
    /**
     * 显示警告通知
     */
    fun warning(project: Project?, title: String, content: String) {
        show(project, title, content, Type.WARNING)
    }
    
    /**
     * 显示错误通知
     */
    fun error(project: Project?, title: String, content: String) {
        show(project, title, content, Type.ERROR)
    }
    
    /**
     * 显示带操作按钮的通知
     */
    fun showWithAction(
        project: Project?,
        title: String,
        content: String,
        type: Type,
        actionText: String,
        actionCallback: (AnActionEvent) -> Unit
    ) {
        val action = object : NotificationAction(actionText) {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                actionCallback(e)
                notification.expire()
            }
        }
        show(project, title, content, type, action)
    }
    
    /**
     * 创建通知对象
     */
    private fun createNotification(title: String, content: String, type: Type): Notification {
        val notificationType = when (type) {
            Type.SUCCESS -> NotificationType.INFORMATION
            Type.INFO -> NotificationType.INFORMATION
            Type.WARNING -> NotificationType.WARNING
            Type.ERROR -> NotificationType.ERROR
        }
        
        return NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, notificationType)
    }
    
    // ========== 预定义的常用通知 ==========
    
    /**
     * API Key 配置成功
     */
    fun apiKeyConfigured(project: Project?, provider: String) {
        success(project, "配置成功", "$provider API Key 已保存")
    }
    
    /**
     * API Key 未配置警告
     */
    fun apiKeyNotConfigured(project: Project?, provider: String) {
        warning(project, "配置缺失", "请先配置 $provider API Key")
    }
    
    /**
     * LLM 请求失败
     */
    fun llmRequestFailed(project: Project?, error: String) {
        error(project, "请求失败", "AI 服务请求失败: $error")
    }
    
    /**
     * 代码补丁应用成功
     */
    fun patchApplied(project: Project?, fileCount: Int) {
        success(project, "补丁已应用", "成功修改 $fileCount 个文件")
    }
    
    /**
     * 代码补丁应用失败
     */
    fun patchApplyFailed(project: Project?, reason: String) {
        error(project, "补丁应用失败", reason)
    }
    
    /**
     * 文件写入成功
     */
    fun fileWritten(project: Project?, fileName: String) {
        success(project, "文件已保存", "$fileName 已成功更新")
    }
    
    /**
     * 流式响应开始
     */
    fun streamingStarted(project: Project?) {
        info(project, "AI 生成中", "正在生成回复...")
    }
    
    /**
     * 操作被取消
     */
    fun operationCancelled(project: Project?) {
        info(project, "操作已取消", "")
    }
    
    /**
     * 上下文收集完成
     */
    fun contextCollected(project: Project?, fileCount: Int, tokenEstimate: Int) {
        info(project, "上下文已收集", "共 $fileCount 个文件，预估 ${tokenEstimate} tokens")
    }
    
    /**
     * 上下文过大警告
     */
    fun contextTooLarge(project: Project?, tokenEstimate: Int, maxTokens: Int) {
        warning(project, "上下文过大", "预估 ${tokenEstimate} tokens 超过限制 $maxTokens，请减少文件数量")
    }
    
    /**
     * 网络连接错误
     */
    fun networkError(project: Project?, details: String) {
        error(project, "网络错误", "无法连接到 AI 服务: $details")
    }
    
    /**
     * 带设置按钮的 API Key 未配置通知
     */
    fun apiKeyNotConfiguredWithAction(project: Project?, provider: String, openSettings: () -> Unit) {
        showWithAction(
            project,
            "配置缺失",
            "请先配置 $provider API Key",
            Type.WARNING,
            "打开设置"
        ) { openSettings() }
    }
}
