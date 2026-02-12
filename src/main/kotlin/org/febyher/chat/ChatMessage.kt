package org.febyher.chat

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 聊天消息数据类
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        private val formatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun getFormattedTime(): String = timestamp.format(formatter)
}

/**
 * 消息角色枚举
 */
enum class MessageRole {
    USER,      // 用户消息
    ASSISTANT, // AI助手消息
    SYSTEM     // 系统消息
}

/**
 * 聊天会话类，管理消息历史
 */
class ChatSession {
    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(role: MessageRole, content: String) {
        messages.add(ChatMessage(role, content))
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun getRecentMessages(count: Int): List<ChatMessage> {
        return messages.takeLast(count)
    }

    fun clear() {
        messages.clear()
    }

    fun toLLMMessages(): List<LLMMessage> {
        return messages.map {
            LLMMessage(
                role = when (it.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                },
                content = it.content
            )
        }
    }
}

/**
 * 用于LLM API的消息格式
 */
data class LLMMessage(
    val role: String,
    val content: String
)
