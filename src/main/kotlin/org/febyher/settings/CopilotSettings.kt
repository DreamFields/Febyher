package org.febyher.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * AI服务提供商枚举
 */
enum class AIProvider(val displayName: String) {
    MOONSHOT("Moonshot (Kimi)"),
    DEEPSEEK("DeepSeek"),
    NVIDIA("NVIDIA NIM");
    
    companion object {
        fun fromName(name: String): AIProvider = 
            values().find { it.name.equals(name, ignoreCase = true) } ?: MOONSHOT
    }
}

/**
 * Provider默认配置接口
 */
interface ProviderDefaults {
    val defaultUrl: String
    val defaultModel: String
    val defaultTemperature: Double
    val availableModels: List<String>
}

/**
 * Moonshot默认配置
 */
object MoonshotDefaults : ProviderDefaults {
    override val defaultUrl = "https://api.moonshot.cn/v1/chat/completions"
    override val defaultModel = "kimi-k2-turbo-preview"
    override val defaultTemperature = 0.6
    override val availableModels = listOf(
        "kimi-k2-turbo-preview",
        "kimi-k2-pro",
        "moonshot-v1-8k",
        "moonshot-v1-32k",
        "moonshot-v1-128k"
    )
}

/**
 * DeepSeek默认配置
 */
object DeepSeekDefaults : ProviderDefaults {
    override val defaultUrl = "https://api.deepseek.com/chat/completions"
    override val defaultModel = "deepseek-chat"
    override val defaultTemperature = 1.0
    override val availableModels = listOf(
        "deepseek-chat",
        "deepseek-coder",
        "deepseek-reasoner"
    )
}

/**
 * NVIDIA NIM默认配置
 */
object NvidiaDefaults : ProviderDefaults {
    override val defaultUrl = "https://integrate.api.nvidia.com/v1/chat/completions"
    override val defaultModel = "moonshotai/kimi-k2.5"
    override val defaultTemperature = 1.0
    override val availableModels = listOf(
        "moonshotai/kimi-k2.5",
        "meta/llama-3.1-405b-instruct",
        "meta/llama-3.1-70b-instruct",
        "meta/llama-3.1-8b-instruct",
        "mistralai/mixtral-8x7b-instruct-v0.1",
        "mistralai/mistral-large-2-instruct",
        "nvidia/nemotron-4-340b-instruct",
        "google/gemma-2-27b-it"
    )
}

/**
 * Provider配置注册表 - 集中管理所有Provider的默认配置
 */
object ProviderDefaultsRegistry {
    
    private val registry = mutableMapOf<AIProvider, ProviderDefaults>(
        AIProvider.MOONSHOT to MoonshotDefaults,
        AIProvider.DEEPSEEK to DeepSeekDefaults,
        AIProvider.NVIDIA to NvidiaDefaults
    )
    
    /**
     * 注册新的Provider默认配置
     */
    fun register(provider: AIProvider, defaults: ProviderDefaults) {
        registry[provider] = defaults
    }
    
    /**
     * 获取Provider默认配置
     */
    fun getDefaults(provider: AIProvider): ProviderDefaults = 
        registry[provider] ?: throw IllegalArgumentException("Unknown provider: $provider")
    
    /**
     * 获取所有注册的Provider
     */
    fun getRegisteredProviders(): Set<AIProvider> = registry.keys
    
    /**
     * 获取Provider的所有可用模型
     */
    fun getAvailableModels(provider: AIProvider): List<String> = 
        getDefaults(provider).availableModels
}

/**
 * 单个Provider的配置
 */
data class ProviderConfig(
    var apiKey: String = "",
    var apiUrl: String = "",
    var model: String = "",
    var temperature: Double = -1.0  // -1表示使用默认值
)

/**
 * 安全密钥存储助手
 */
object SecureKeyStorage {
    private const val SERVICE_NAME = "Febyher AI"
    
    /**
     * 为指定Provider创建凭据属性
     */
    private fun createCredentialAttributes(provider: AIProvider): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName(SERVICE_NAME, "${provider.name.lowercase()}-api-key")
        )
    }
    
    /**
     * 安全存储API Key
     */
    fun storeApiKey(provider: AIProvider, apiKey: String) {
        val attributes = createCredentialAttributes(provider)
        val credentials = Credentials(provider.name, apiKey)
        PasswordSafe.instance.set(attributes, credentials)
    }
    
    /**
     * 获取存储的API Key
     */
    fun getApiKey(provider: AIProvider): String? {
        val attributes = createCredentialAttributes(provider)
        val credentials = PasswordSafe.instance.get(attributes)
        return credentials?.password?.toString()
    }
    
    /**
     * 删除存储的API Key
     */
    fun removeApiKey(provider: AIProvider) {
        val attributes = createCredentialAttributes(provider)
        PasswordSafe.instance.set(attributes, null)
    }
}

/**
 * 插件设置状态类 - 为每个Provider独立存储配置
 * API Key 使用 PasswordSafe 安全存储，不在此 State 中保存
 */
@State(
    name = "org.febyher.settings.CopilotSettings",
    storages = [Storage("FebyherSettings.xml")]
)
@Service(Service.Level.APP)
class CopilotSettings : PersistentStateComponent<CopilotSettings.State> {

    /**
     * State 只存储非敏感配置
     * API Key 不再存储在 XML 中，改用 PasswordSafe
     */
    data class State(
        var currentProvider: String = AIProvider.MOONSHOT.name,
        var moonshotApiUrl: String = "",
        var moonshotModel: String = "",
        var deepseekApiUrl: String = "",
        var deepseekModel: String = "",
        var nvidiaApiUrl: String = "",
        var nvidiaModel: String = "",
        var maxTokens: Int = 4096,
        // 用于迁移标记
        var migratedToSecureStorage: Boolean = false,
        // 旧字段用于迁移（迁移后会被清空）
        @Deprecated("Use SecureKeyStorage") var moonshotApiKey: String = "",
        @Deprecated("Use SecureKeyStorage") var deepseekApiKey: String = ""
    )

    private var myState = State()

    companion object {
        @JvmStatic
        fun getInstance(): CopilotSettings {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(CopilotSettings::class.java)
        }
    }

    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        // 执行一次性迁移
        migrateOldApiKeysIfNeeded()
    }
    
    /**
     * 将旧的明文 API Key 迁移到 PasswordSafe
     */
    private fun migrateOldApiKeysIfNeeded() {
        if (myState.migratedToSecureStorage) return
        
        // 迁移 Moonshot API Key
        @Suppress("DEPRECATION")
        if (myState.moonshotApiKey.isNotBlank()) {
            SecureKeyStorage.storeApiKey(AIProvider.MOONSHOT, myState.moonshotApiKey)
            @Suppress("DEPRECATION")
            myState.moonshotApiKey = ""
        }
        
        // 迁移 DeepSeek API Key
        @Suppress("DEPRECATION")
        if (myState.deepseekApiKey.isNotBlank()) {
            SecureKeyStorage.storeApiKey(AIProvider.DEEPSEEK, myState.deepseekApiKey)
            @Suppress("DEPRECATION")
            myState.deepseekApiKey = ""
        }
        
        myState.migratedToSecureStorage = true
    }

    // 当前使用的Provider
    var currentProvider: AIProvider
        get() = AIProvider.fromName(myState.currentProvider)
        set(value) { myState.currentProvider = value.name }

    var maxTokens: Int
        get() = myState.maxTokens
        set(value) { myState.maxTokens = value }

    // Moonshot配置 - API Key 使用安全存储
    var moonshotApiKey: String
        get() = SecureKeyStorage.getApiKey(AIProvider.MOONSHOT) ?: ""
        set(value) { 
            if (value.isNotBlank()) {
                SecureKeyStorage.storeApiKey(AIProvider.MOONSHOT, value)
            } else {
                SecureKeyStorage.removeApiKey(AIProvider.MOONSHOT)
            }
        }
    
    var moonshotApiUrl: String
        get() = myState.moonshotApiUrl
        set(value) { myState.moonshotApiUrl = value }
    
    var moonshotModel: String
        get() = myState.moonshotModel
        set(value) { myState.moonshotModel = value }

    // DeepSeek配置 - API Key 使用安全存储
    var deepseekApiKey: String
        get() = SecureKeyStorage.getApiKey(AIProvider.DEEPSEEK) ?: ""
        set(value) { 
            if (value.isNotBlank()) {
                SecureKeyStorage.storeApiKey(AIProvider.DEEPSEEK, value)
            } else {
                SecureKeyStorage.removeApiKey(AIProvider.DEEPSEEK)
            }
        }
    
    var deepseekApiUrl: String
        get() = myState.deepseekApiUrl
        set(value) { myState.deepseekApiUrl = value }
    
    var deepseekModel: String
        get() = myState.deepseekModel
        set(value) { myState.deepseekModel = value }

    // NVIDIA配置 - API Key 使用安全存储
    var nvidiaApiKey: String
        get() = SecureKeyStorage.getApiKey(AIProvider.NVIDIA) ?: ""
        set(value) { 
            if (value.isNotBlank()) {
                SecureKeyStorage.storeApiKey(AIProvider.NVIDIA, value)
            } else {
                SecureKeyStorage.removeApiKey(AIProvider.NVIDIA)
            }
        }
    
    var nvidiaApiUrl: String
        get() = myState.nvidiaApiUrl
        set(value) { myState.nvidiaApiUrl = value }
    
    var nvidiaModel: String
        get() = myState.nvidiaModel
        set(value) { myState.nvidiaModel = value }

    /**
     * 获取指定Provider的配置
     */
    fun getProviderConfig(provider: AIProvider): ProviderConfig {
        return when (provider) {
            AIProvider.MOONSHOT -> ProviderConfig(
                apiKey = moonshotApiKey,
                apiUrl = moonshotApiUrl,
                model = moonshotModel
            )
            AIProvider.DEEPSEEK -> ProviderConfig(
                apiKey = deepseekApiKey,
                apiUrl = deepseekApiUrl,
                model = deepseekModel
            )
            AIProvider.NVIDIA -> ProviderConfig(
                apiKey = nvidiaApiKey,
                apiUrl = nvidiaApiUrl,
                model = nvidiaModel
            )
        }
    }

    /**
     * 设置指定Provider的配置
     */
    fun setProviderConfig(provider: AIProvider, config: ProviderConfig) {
        when (provider) {
            AIProvider.MOONSHOT -> {
                moonshotApiKey = config.apiKey
                moonshotApiUrl = config.apiUrl
                moonshotModel = config.model
            }
            AIProvider.DEEPSEEK -> {
                deepseekApiKey = config.apiKey
                deepseekApiUrl = config.apiUrl
                deepseekModel = config.model
            }
            AIProvider.NVIDIA -> {
                nvidiaApiKey = config.apiKey
                nvidiaApiUrl = config.apiUrl
                nvidiaModel = config.model
            }
        }
    }

    /**
     * 获取当前Provider的有效API Key
     */
    fun getEffectiveApiKey(): String {
        val config = getProviderConfig(currentProvider)
        return config.apiKey.ifBlank { "" }
    }

    /**
     * 获取当前Provider的有效API URL
     */
    fun getEffectiveApiUrl(): String {
        val config = getProviderConfig(currentProvider)
        val defaults = ProviderDefaultsRegistry.getDefaults(currentProvider)
        return config.apiUrl.ifBlank { defaults.defaultUrl }
    }

    /**
     * 获取当前Provider的有效模型
     */
    fun getEffectiveModel(): String {
        val config = getProviderConfig(currentProvider)
        val defaults = ProviderDefaultsRegistry.getDefaults(currentProvider)
        return config.model.ifBlank { defaults.defaultModel }
    }

    /**
     * 获取当前Provider的有效temperature
     */
    fun getEffectiveTemperature(): Double {
        val config = getProviderConfig(currentProvider)
        val defaults = ProviderDefaultsRegistry.getDefaults(currentProvider)
        return if (config.temperature >= 0) config.temperature else defaults.defaultTemperature
    }

    /**
     * 获取当前Provider可用的模型列表
     */
    fun getAvailableModels(): List<String> = ProviderDefaultsRegistry.getAvailableModels(currentProvider)

    /**
     * 检查指定Provider是否已配置
     */
    fun isProviderConfigured(provider: AIProvider): Boolean {
        val config = getProviderConfig(provider)
        return config.apiKey.isNotBlank()
    }

    /**
     * 检查当前Provider是否已配置
     */
    fun isConfigured(): Boolean = isProviderConfigured(currentProvider)
}
