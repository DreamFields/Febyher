package org.febyher.llm

import org.febyher.chat.LLMMessage
import org.febyher.context.CodeContext

/**
 * 流式响应回调接口
 */
interface StreamCallback {
    fun onDelta(delta: String)
    fun onComplete(fullResponse: String)
    fun onError(error: String)
}

/**
 * LLM 服务接口
 */
interface LLMService {
    fun chat(messages: List<LLMMessage>): String
    fun chatWithContext(userMessage: String, context: CodeContext?): String
    fun chatStream(messages: List<LLMMessage>, callback: StreamCallback)
    fun chatStreamWithContext(userMessage: String, context: CodeContext?, callback: StreamCallback)
}

/**
 * Provider 配置信息
 */
data class ProviderConfig(
    val apiKey: String,
    val apiUrl: String,
    val model: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096
)
