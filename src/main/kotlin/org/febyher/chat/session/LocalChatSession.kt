package org.febyher.chat.session

import org.febyher.settings.AIProvider

/**
 * 本地持久化的一条消息（用于 JSON 序列化）
 */
data class LocalMessageDto(
    val role: String,  // "user" | "assistant" | "system"
    val content: String
)

/**
 * 本地会话（非 Aurod 的 LLM 会话，按项目存储到磁盘）
 */
data class LocalChatSession(
    val id: String,
    var title: String,
    val provider: String,   // AIProvider.name
    val model: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<LocalMessageDto> = mutableListOf()
) {
    fun providerEnum(): AIProvider = AIProvider.fromName(provider)
}
