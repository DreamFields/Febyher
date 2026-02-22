package org.febyher.llm

/**
 * DeepSeek LLM 服务实现
 */
class DeepSeekLLMService(config: ProviderConfig) : BaseLLMService(config) {

    override fun getProviderName(): String = "DeepSeek"

    override fun getSystemPrompt(): String {
        return "你是DeepSeek AI，由DeepSeek公司开发的专业编程助手。" +
               "你可以帮助用户：解释代码的工作原理、帮助调试和修复bug、提供代码优化建议、" +
               "生成高质量的代码片段、回答编程相关的问题。" +
               "回复时使用Markdown格式，代码块使用对应的语言标记。保持回答简洁、准确、有帮助。"
    }
}
