package org.febyher.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import org.febyher.llm.aurod.AurodConfig
import javax.swing.*

/**
 * Provider面板创建结果
 */
private data class ProviderPanelResult(
    val panel: JPanel,
    val apiKeyField: JBPasswordField,
    val apiUrlField: JBTextField
)

/**
 * Aurod 面板创建结果
 * 支持两种认证方式：账号密码登录 / 直接填写 Cookie、Auth Token、UID
 */
private data class AurodPanelResult(
    val panel: JPanel,
    val accountField: JBTextField,
    val passwordField: JBPasswordField,
    val authTokenField: JBTextField,
    val cookieField: JBTextField,
    val uidField: JBTextField
)

/**
 * 设置面板配置类 - 每个Provider纵向排列
 */
class CopilotSettingsConfigurable : Configurable {

    private var settingsPanel: JPanel? = null
    
    // Moonshot配置
    private var moonshotApiKeyField: JBPasswordField? = null
    private var moonshotApiUrlField: JBTextField? = null
    
    // DeepSeek配置
    private var deepseekApiKeyField: JBPasswordField? = null
    private var deepseekApiUrlField: JBTextField? = null
    
    // NVIDIA配置
    private var nvidiaApiKeyField: JBPasswordField? = null
    private var nvidiaApiUrlField: JBTextField? = null
    
    // Aurod AI 配置
    private var aurodAccountField: JBTextField? = null
    private var aurodPasswordField: JBPasswordField? = null
    private var aurodAuthTokenField: JBTextField? = null
    private var aurodCookieField: JBTextField? = null
    private var aurodUidField: JBTextField? = null

    override fun getDisplayName(): String = "Febyher AI"

    override fun getPreferredFocusedComponent(): JComponent? = moonshotApiKeyField

    override fun createComponent(): JComponent? {
        // Moonshot配置面板
        val moonshotResult = createProviderPanel("Moonshot (Kimi)", MoonshotDefaults.defaultUrl)
        moonshotApiKeyField = moonshotResult.apiKeyField
        moonshotApiUrlField = moonshotResult.apiUrlField
        
        // DeepSeek配置面板
        val deepseekResult = createProviderPanel("DeepSeek", DeepSeekDefaults.defaultUrl)
        deepseekApiKeyField = deepseekResult.apiKeyField
        deepseekApiUrlField = deepseekResult.apiUrlField
        
        // NVIDIA配置面板
        val nvidiaResult = createProviderPanel("NVIDIA NIM", NvidiaDefaults.defaultUrl)
        nvidiaApiKeyField = nvidiaResult.apiKeyField
        nvidiaApiUrlField = nvidiaResult.apiUrlField
        
        // Aurod AI 配置面板
        val aurodResult = createAurodPanel()
        aurodAccountField = aurodResult.accountField
        aurodPasswordField = aurodResult.passwordField
        aurodAuthTokenField = aurodResult.authTokenField
        aurodCookieField = aurodResult.cookieField
        aurodUidField = aurodResult.uidField

        settingsPanel = FormBuilder.createFormBuilder()
            .addComponent(createHeaderLabel("配置各AI服务提供商的API密钥"))
            .addTooltip("API Key留空则无法使用对应服务，URL留空使用默认值")
            .addSeparator()
            .addComponent(moonshotResult.panel)
            .addSeparator()
            .addComponent(deepseekResult.panel)
            .addSeparator()
            .addComponent(nvidiaResult.panel)
            .addSeparator()
            .addComponent(aurodResult.panel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply {
                border = JBUI.Borders.empty(15)
            }

        reset()
        return settingsPanel
    }
    
    private fun createHeaderLabel(text: String): JLabel {
        return JLabel(text).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }
    }
    
    private fun createProviderPanel(providerName: String, defaultUrl: String): ProviderPanelResult {
        val apiKeyField = JBPasswordField().apply {
            columns = 40
            emptyText.text = "sk-..."
        }
        
        val apiUrlField = JBTextField().apply {
            columns = 40
            emptyText.text = defaultUrl
        }
        
        val panel = JPanel().apply {
            layout = java.awt.GridBagLayout()
            border = JBUI.Borders.empty(10, 0)
            background = JBColor.namedColor("Panel.background", java.awt.Color.WHITE)
            
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(5, 5, 5, 5)
            }
            
            // Provider名称
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
            add(JLabel(providerName).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 12f)
                foreground = JBColor(0x1976D2, 0x64B5F6)
            }, gbc)
            
            // API Key行
            gbc.gridy = 1; gbc.gridwidth = 1
            gbc.gridx = 0; add(JLabel("API Key:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(apiKeyField, gbc)
            
            // API URL行
            gbc.gridy = 2; gbc.weightx = 0.0
            gbc.gridx = 0; add(JLabel("API URL:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(apiUrlField, gbc)
        }
        
        return ProviderPanelResult(panel, apiKeyField, apiUrlField)
    }
    
    /**
     * 创建 Aurod AI 配置面板
     * 方式一：填写账号密码（自动登录获取 token）
     * 方式二：直接填写 Cookie / Auth Token / UID（跳过登录）
     */
    private fun createAurodPanel(): AurodPanelResult {
        val accountField = JBTextField().apply {
            columns = 40
            emptyText.text = "手机号/邮箱"
        }
        
        val passwordField = JBPasswordField().apply {
            columns = 40
            emptyText.text = "密码"
        }
        
        val authTokenField = JBTextField().apply {
            columns = 40
            emptyText.text = "Bearer Token（从浏览器请求头复制）"
        }
        
        val cookieField = JBTextField().apply {
            columns = 40
            emptyText.text = "Cookie（从浏览器请求头复制）"
        }
        
        val uidField = JBTextField().apply {
            columns = 40
            emptyText.text = "用户 UID（数字）"
        }
        
        val panel = JPanel().apply {
            layout = java.awt.GridBagLayout()
            border = JBUI.Borders.empty(10, 0)
            background = JBColor.namedColor("Panel.background", java.awt.Color.WHITE)
            
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(5, 5, 5, 5)
            }
            
            // Provider名称
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
            add(JLabel("Aurod AI (ai.aurod.cn)").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 12f)
                foreground = JBColor(0x1976D2, 0x64B5F6)
            }, gbc)
            
            // 方式一说明
            gbc.gridy = 1
            add(JLabel("方式一：账号密码登录").apply {
                font = font.deriveFont(java.awt.Font.ITALIC, 11f)
                foreground = JBColor.GRAY
            }, gbc)
            
            // 账号行
            gbc.gridy = 2; gbc.gridwidth = 1
            gbc.gridx = 0; gbc.weightx = 0.0; add(JLabel("账号:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(accountField, gbc)
            
            // 密码行
            gbc.gridy = 3; gbc.weightx = 0.0
            gbc.gridx = 0; add(JLabel("密码:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(passwordField, gbc)
            
            // 分隔说明
            gbc.gridy = 4; gbc.gridwidth = 2; gbc.gridx = 0
            add(JLabel("方式二：直接填写认证信息（跳过登录，优先使用）").apply {
                font = font.deriveFont(java.awt.Font.ITALIC, 11f)
                foreground = JBColor.GRAY
            }, gbc)
            
            // Auth Token 行
            gbc.gridy = 5; gbc.gridwidth = 1
            gbc.gridx = 0; gbc.weightx = 0.0; add(JLabel("Auth Token:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(authTokenField, gbc)
            
            // Cookie 行
            gbc.gridy = 6; gbc.weightx = 0.0
            gbc.gridx = 0; add(JLabel("Cookie:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(cookieField, gbc)
            
            // UID 行
            gbc.gridy = 7; gbc.weightx = 0.0
            gbc.gridx = 0; add(JLabel("UID:"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; add(uidField, gbc)
        }
        
        return AurodPanelResult(panel, accountField, passwordField, authTokenField, cookieField, uidField)
    }

    override fun isModified(): Boolean {
        val settings = CopilotSettings.getInstance()
        
        val moonshotConfig = settings.getProviderConfig(AIProvider.MOONSHOT)
        val deepseekConfig = settings.getProviderConfig(AIProvider.DEEPSEEK)
        val nvidiaConfig = settings.getProviderConfig(AIProvider.NVIDIA)
        val aurodConfig = settings.aurodConfig
        
        return moonshotApiKeyField?.password?.concatToString() != moonshotConfig.apiKey ||
               moonshotApiUrlField?.text != moonshotConfig.apiUrl ||
               deepseekApiKeyField?.password?.concatToString() != deepseekConfig.apiKey ||
               deepseekApiUrlField?.text != deepseekConfig.apiUrl ||
               nvidiaApiKeyField?.password?.concatToString() != nvidiaConfig.apiKey ||
               nvidiaApiUrlField?.text != nvidiaConfig.apiUrl ||
               aurodAccountField?.text != aurodConfig.account ||
               aurodPasswordField?.password?.concatToString() != aurodConfig.password ||
               aurodAuthTokenField?.text != aurodConfig.authToken ||
               aurodCookieField?.text != aurodConfig.cookie ||
               aurodUidField?.text != (if (aurodConfig.uid != 0L) aurodConfig.uid.toString() else "")
    }

    override fun apply() {
        val settings = CopilotSettings.getInstance()
        
        // 保存Moonshot配置
        val moonshotConfig = settings.getProviderConfig(AIProvider.MOONSHOT)
        moonshotConfig.apiKey = moonshotApiKeyField?.password?.concatToString() ?: ""
        moonshotConfig.apiUrl = moonshotApiUrlField?.text ?: ""
        settings.setProviderConfig(AIProvider.MOONSHOT, moonshotConfig)
        
        // 保存DeepSeek配置
        val deepseekConfig = settings.getProviderConfig(AIProvider.DEEPSEEK)
        deepseekConfig.apiKey = deepseekApiKeyField?.password?.concatToString() ?: ""
        deepseekConfig.apiUrl = deepseekApiUrlField?.text ?: ""
        settings.setProviderConfig(AIProvider.DEEPSEEK, deepseekConfig)
        
        // 保存NVIDIA配置
        val nvidiaConfig = settings.getProviderConfig(AIProvider.NVIDIA)
        nvidiaConfig.apiKey = nvidiaApiKeyField?.password?.concatToString() ?: ""
        nvidiaConfig.apiUrl = nvidiaApiUrlField?.text ?: ""
        settings.setProviderConfig(AIProvider.NVIDIA, nvidiaConfig)
        
        // 保存 Aurod 配置
        val aurodAccount = aurodAccountField?.text ?: ""
        val aurodPassword = aurodPasswordField?.password?.concatToString() ?: ""
        val aurodAuthToken = aurodAuthTokenField?.text ?: ""
        val aurodCookie = aurodCookieField?.text ?: ""
        val aurodUid = aurodUidField?.text?.toLongOrNull() ?: 0L
        settings.aurodConfig = AurodConfig(
            authToken = aurodAuthToken,
            cookie = aurodCookie,
            uid = aurodUid,
            account = aurodAccount,
            password = aurodPassword
        )
    }

    override fun reset() {
        val settings = CopilotSettings.getInstance()
        
        // 加载Moonshot配置
        val moonshotConfig = settings.getProviderConfig(AIProvider.MOONSHOT)
        moonshotApiKeyField?.text = moonshotConfig.apiKey
        moonshotApiUrlField?.text = moonshotConfig.apiUrl
        
        // 加载DeepSeek配置
        val deepseekConfig = settings.getProviderConfig(AIProvider.DEEPSEEK)
        deepseekApiKeyField?.text = deepseekConfig.apiKey
        deepseekApiUrlField?.text = deepseekConfig.apiUrl
        
        // 加载NVIDIA配置
        val nvidiaConfig = settings.getProviderConfig(AIProvider.NVIDIA)
        nvidiaApiKeyField?.text = nvidiaConfig.apiKey
        nvidiaApiUrlField?.text = nvidiaConfig.apiUrl
        
        // 加载 Aurod 配置
        val aurodConfig = settings.aurodConfig
        aurodAccountField?.text = aurodConfig.account
        aurodPasswordField?.text = aurodConfig.password
        aurodAuthTokenField?.text = aurodConfig.authToken
        aurodCookieField?.text = aurodConfig.cookie
        aurodUidField?.text = if (aurodConfig.uid != 0L) aurodConfig.uid.toString() else ""
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
