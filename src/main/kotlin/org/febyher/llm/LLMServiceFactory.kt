package org.febyher.llm

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import org.febyher.llm.aurod.AurodLLMService
import org.febyher.settings.AIProvider
import org.febyher.settings.CopilotSettings

/**
 * LLM 服务工厂 - 根据 Provider 类型创建对应实例
 */
object LLMServiceFactory {

    private val logger = Logger.getInstance(LLMServiceFactory::class.java)

    private val providerRegistry = mutableMapOf<AIProvider, (ProviderConfig) -> LLMService>(
        AIProvider.MOONSHOT to { config -> MoonshotLLMService(config) },
        AIProvider.DEEPSEEK to { config -> DeepSeekLLMService(config) },
        AIProvider.NVIDIA to { config -> NvidiaLLMService(config) },
        AIProvider.AUROD to { config -> AurodLLMService(config) }
    )

    fun registerProvider(provider: AIProvider, factory: (ProviderConfig) -> LLMService) {
        providerRegistry[provider] = factory
        logger.info("Registered LLM provider: $provider")
    }

    fun create(provider: AIProvider, config: ProviderConfig): LLMService {
        val factory = providerRegistry[provider]
            ?: throw IllegalArgumentException("Unsupported provider: $provider")
        return factory(config)
    }

    fun getSupportedProviders(): Set<AIProvider> = providerRegistry.keys.toSet()
}

/**
 * LLM 服务管理器 - 管理当前项目下的 LLM 实例生命周期
 */
@Service(Service.Level.PROJECT)
class LLMServiceManager {

    private var currentService: LLMService? = null
    private var currentProvider: AIProvider? = null

    fun getService(): LLMService {
        val settings = CopilotSettings.getInstance()
        val provider = settings.currentProvider
        if (currentService == null || currentProvider != provider) {
            val config = ProviderConfig(
                apiKey = settings.getEffectiveApiKey(),
                apiUrl = settings.getEffectiveApiUrl(),
                model = settings.getEffectiveModel(),
                temperature = settings.getEffectiveTemperature(),
                maxTokens = settings.maxTokens
            )
            currentService = LLMServiceFactory.create(provider, config)
            currentProvider = provider
        }
        return currentService!!
    }

    fun refresh() {
        currentService = null
        currentProvider = null
    }

    companion object {
        fun getInstance(project: com.intellij.openapi.project.Project): LLMServiceManager {
            return project.getService(LLMServiceManager::class.java)
        }
    }
}

/**
 * 便捷访问点（向后兼容）
 */
object LLMServiceProvider {

    private var projectRef: com.intellij.openapi.project.Project? = null

    fun init(project: com.intellij.openapi.project.Project) {
        projectRef = project
    }

    fun getService(): LLMService {
        val project = projectRef
            ?: throw IllegalStateException("LLMServiceProvider not initialized. Call init(project) first.")
        return LLMServiceManager.getInstance(project).getService()
    }
}
