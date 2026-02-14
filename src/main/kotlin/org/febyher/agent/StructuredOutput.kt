package org.febyher.agent

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.febyher.patch.DiffParser
import org.febyher.patch.FileDiff

/**
 * Agent 执行计划
 */
data class AgentPlan(
    val steps: List<PlanStep>,
    val summary: String
)

/**
 * 计划步骤
 */
data class PlanStep(
    val order: Int,
    val description: String,
    val targetFiles: List<String>,
    val action: String // "modify", "create", "delete"
)

/**
 * Agent 响应（结构化输出）
 */
data class AgentResponse(
    @SerializedName("plan") val plan: List<String>? = null,
    @SerializedName("changes") val changes: List<CodeChange>? = null,
    @SerializedName("commands") val commands: List<String>? = null,
    @SerializedName("risks") val risks: List<String>? = null,
    @SerializedName("summary") val summary: String = "",
    @SerializedName("raw_content") val rawContent: String = ""
) {
    /**
     * 检查是否有代码变更
     */
    fun hasChanges(): Boolean = !changes.isNullOrEmpty()
    
    /**
     * 获取所有文件路径
     */
    fun getAffectedFiles(): List<String> {
        return changes?.mapNotNull { it.file } ?: emptyList()
    }
    
    /**
     * 解析所有 diffs
     */
    fun parseDiffs(): List<FileDiff> {
        val allDiffs = mutableListOf<FileDiff>()
        
        changes?.forEach { change ->
            if (!change.diff.isNullOrBlank()) {
                val diffs = DiffParser.parse(change.diff)
                allDiffs.addAll(diffs)
            }
        }
        
        return allDiffs
    }
}

/**
 * 代码变更
 */
data class CodeChange(
    @SerializedName("file") val file: String?,
    @SerializedName("diff") val diff: String?,
    @SerializedName("reason") val reason: String?,
    @SerializedName("action") val action: String = "modify" // "modify", "create", "delete"
)

/**
 * 结构化输出解析器
 * 从 LLM 响应中提取 JSON 结构
 */
object StructuredOutputParser {
    
    private val gson = Gson()
    
    private val JSON_BLOCK_REGEX = Regex("```json\\s*\\n([\\s\\S]*?)\\n```")
    private val JSON_OBJECT_REGEX = Regex("\\{[\\s\\S]*\\}")
    
    /**
     * 从文本中解析 JSON 响应
     */
    fun parse(text: String): AgentResponse {
        // 首先尝试从 ```json 代码块中提取
        val jsonBlockMatch = JSON_BLOCK_REGEX.find(text)
        
        if (jsonBlockMatch != null) {
            val jsonText = jsonBlockMatch.groupValues[1]
            return parseJson(jsonText, text)
        }
        
        // 尝试查找 JSON 对象
        val jsonObjectMatch = JSON_OBJECT_REGEX.find(text)
        
        if (jsonObjectMatch != null) {
            return parseJson(jsonObjectMatch.value, text)
        }
        
        // 无法解析为 JSON，返回原始内容
        return AgentResponse(
            summary = "无法解析为结构化输出",
            rawContent = text
        )
    }
    
    /**
     * 解析 JSON 文本
     */
    private fun parseJson(jsonText: String, originalText: String): AgentResponse {
        return try {
            val response = gson.fromJson(jsonText, AgentResponse::class.java)
            response.copy(rawContent = originalText)
        } catch (e: JsonSyntaxException) {
            AgentResponse(
                summary = "JSON 解析失败: ${e.message}",
                rawContent = originalText
            )
        }
    }
    
    /**
     * 从响应中提取 diff 块
     */
    fun extractDiffs(text: String): List<FileDiff> {
        return DiffParser.extractDiffsFromResponse(text)
    }
    
    /**
     * 从响应中提取执行计划
     */
    fun extractPlan(text: String): AgentPlan? {
        val response = parse(text)
        
        if (response.plan.isNullOrEmpty()) return null
        
        val steps = response.plan.mapIndexed { index, stepText ->
            PlanStep(
                order = index + 1,
                description = stepText,
                targetFiles = response.changes?.mapNotNull { it.file } ?: emptyList(),
                action = "modify"
            )
        }
        
        return AgentPlan(steps, response.summary)
    }
}

/**
 * Prompt 模板生成器
 * 用于生成约束模型输出的 prompt
 */
object PromptTemplates {
    
    /**
     * 系统提示：结构化输出约束
     */
    val STRUCTURED_OUTPUT_SYSTEM = """
        你是一个专业的代码助手。你的回复必须严格遵循以下 JSON 格式：

        ```json
        {
            "plan": ["步骤1", "步骤2", ...],
            "changes": [
                {
                    "file": "文件路径",
                    "diff": "unified diff 格式的变更",
                    "reason": "变更原因",
                    "action": "modify|create|delete"
                }
            ],
            "commands": ["可选的命令，如测试命令"],
            "risks": ["潜在风险"],
            "summary": "变更摘要"
        }
        ```

        重要规则：
        1. diff 必须使用标准的 unified diff 格式
        2. 文件路径使用相对于项目根目录的路径
        3. 每个变更必须包含 reason 字段说明原因
        4. 如果涉及多个文件，在 changes 数组中分别列出
        5. 不要输出任何 JSON 之外的额外文本
    """.trimIndent()
    
    /**
     * 任务提示模板
     */
    fun taskPrompt(userRequest: String, context: String = ""): String {
        val contextSection = if (context.isNotBlank()) {
            "\n\n=== 上下文 ===\n$context\n"
        } else {
            ""
        }
        
        return """
            用户请求：$userRequest
            
            $contextSection
            
            请分析需求并按照 JSON 格式输出你的响应。确保：
            1. 先列出执行计划（plan）
            2. 然后给出具体的代码变更（changes）
            3. 说明潜在风险（risks）
            4. 提供简要摘要（summary）
        """.trimIndent()
    }
    
    /**
     * 代码修改提示模板
     */
    fun codeModificationPrompt(
        fileName: String,
        language: String,
        currentCode: String,
        userRequest: String
    ): String {
        return """
            文件：$fileName
            语言：$language
            
            当前代码：
            ```$language
            $currentCode
            ```
            
            修改请求：$userRequest
            
            请生成 unified diff 格式的修改。确保 diff 格式正确：
            ```diff
            --- a/$fileName
            +++ b/$fileName
            @@ -起始行,行数 +起始行,行数 @@
            -删除的行
            +新增的行
             上下文行
            ```
            
            按照结构化 JSON 格式输出。
        """.trimIndent()
    }
    
    /**
     * 代码审查提示模板
     */
    fun codeReviewPrompt(
        fileName: String,
        language: String,
        code: String
    ): String {
        return """
            请审查以下代码：
            
            文件：$fileName
            语言：$language
            
            ```$language
            $code
            ```
            
            请检查以下方面并提供改进建议：
            1. 潜在的 bug 或错误
            2. 性能问题
            3. 代码风格和可读性
            4. 安全风险
            5. 最佳实践建议
            
            如果有改进建议，请提供 unified diff 格式的修改。
            按照结构化 JSON 格式输出。
        """.trimIndent()
    }
}
