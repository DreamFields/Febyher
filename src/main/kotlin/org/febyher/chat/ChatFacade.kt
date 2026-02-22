package org.febyher.chat

/**
 * 聊天面板门面接口
 * 供 Actions 等向聊天窗口注入消息，避免强依赖具体 UI 实现
 */
interface ChatFacade {
    /**
     * 接收外部消息并展示到聊天输入/历史（由实现决定具体行为）
     */
    fun receiveExternalMessage(message: String)
}
