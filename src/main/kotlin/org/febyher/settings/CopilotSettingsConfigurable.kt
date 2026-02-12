package org.febyher.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
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

        settingsPanel = FormBuilder.createFormBuilder()
            .addComponent(createHeaderLabel("配置各AI服务提供商的API密钥"))
            .addTooltip("API Key留空则无法使用对应服务，URL留空使用默认值")
            .addSeparator()
            .addComponent(moonshotResult.panel)
            .addSeparator()
            .addComponent(deepseekResult.panel)
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

    override fun isModified(): Boolean {
        val settings = CopilotSettings.getInstance()
        
        val moonshotConfig = settings.getProviderConfig(AIProvider.MOONSHOT)
        val deepseekConfig = settings.getProviderConfig(AIProvider.DEEPSEEK)
        
        return moonshotApiKeyField?.password?.concatToString() != moonshotConfig.apiKey ||
               moonshotApiUrlField?.text != moonshotConfig.apiUrl ||
               deepseekApiKeyField?.password?.concatToString() != deepseekConfig.apiKey ||
               deepseekApiUrlField?.text != deepseekConfig.apiUrl
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
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
