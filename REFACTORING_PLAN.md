# Febyher AI 插件架构重构方案

## 一、现状问题简述

| 问题 | 说明 |
|------|------|
| 模板遗留 | `org.jetbrains.plugins.template` 包（空 ChatPanel、依赖不存在的 MyProjectService 的测试）、`FebyherProjectService`、`FebyherToolWindowFactory` 为模板示例，未在 plugin.xml 使用 |
| 单文件过大 | `LLMService.kt` 含接口、基类、4 个 Provider 实现、Factory、Manager、Provider 等，约 1000+ 行；`ChatPanel.kt` 约 1370 行，职责过多 |
| 强耦合 | Actions 通过 `content.component as? ChatPanel` 强依赖具体 UI 类发送消息，不利于测试与替换 |
| 未使用代码 | `FebyherToolWindowFactory` 未在 plugin.xml 注册，工具窗口实际仅使用 `ChatToolWindowFactory` |

---

## 二、可行重构方案

### 方案 A：保守清理 + LLM 层拆文件（推荐优先）

**目标**：去除冗余、降低单文件复杂度，不改动业务行为。

1. **删除模板与死代码**
   - 删除包 `org.jetbrains.plugins.template`（含空 `ChatPanel.kt` 及对应测试）。
   - 删除 `FebyherToolWindowFactory`、`FebyherProjectService`。
   - 从 `plugin.xml` 中移除上述相关扩展（若有注册）。
   - 精简 `MyBundle.properties`（移除 `randomLabel`、`shuffle` 等模板文案）。
   - 测试：`MyPluginTest` 中移除对 `FebyherProjectService` 的测试，保留 XML/重命名等与业务无关的示例测试，或改为简单存在性测试（如 `LLMServiceManager.getInstance(project)`）。

2. **LLM 层按职责拆文件**
   - `LLMService.kt`：仅保留 `StreamCallback`、`LLMService` 接口、`ProviderConfig`。
   - `BaseLLMService.kt`：抽象基类（HTTP/SSE/解析等通用逻辑）。
   - `MoonshotLLMService.kt`、`DeepSeekLLMService.kt`：各一个文件（或合并为 `OpenAICompatibleProviders.kt`）。
   - `NvidiaLLMService.kt`：保留独立文件（含诊断逻辑）。
   - `LLMServiceFactory.kt`：工厂 + `LLMServiceManager` + `LLMServiceProvider`（或保留在单独 Manager 文件）。
   - `llm/aurod/` 保持不变。

3. **启动与文档**
   - `MyProjectActivity`：去掉模板提醒日志或改为简单初始化（如需要可在此做审计/权限预热）。
   - 更新 `ARCHITECTURE.md` 中目录与模块说明。

**优点**：风险小、改动集中、易于回滚。  
**缺点**：ChatPanel 仍大、Actions 仍强依赖 ChatPanel。

---

### 方案 B：在方案 A 基础上增加“聊天门面”与轻量拆分（推荐第二步）

**目标**：Actions 与 ChatPanel 解耦，便于测试与扩展。

1. **引入 Chat 门面**
   - 定义接口，例如：`ChatFacade { fun receiveExternalMessage(message: String) }`。
   - `ChatPanel` 实现该接口；工具窗口 Content 的 component 即为该实现。
   - 提供“获取当前项目 Chat 门面”的入口：例如 `Project.getService(ChatFacadeHolder::class.java).facade`，在 `ChatToolWindowFactory` 创建 Content 时注册；或通过 `Content.userData` 存放 facade，Actions 通过 ToolWindow 取 Content 再取 userData。
   - Actions（如 `ExplainCodeAction`）改为：获取 `ChatFacade` 并调用 `receiveExternalMessage(prompt)`，不再强转 `ChatPanel`。

2. **ChatPanel 轻量拆分（已完成）**
   - `AurodSessionBar`：会话栏 UI 组件，接收 `onNewSession`/`onSwitchSession` 回调，提供 `updateLabel(sessionName?, model?)`；逻辑仍由 ChatPanel 处理。
   - `ChatMessageRenderer`：Markdown/纯文本转 HTML、角色颜色与名称（`getMessageBgColor`、`getRoleDisplayName` 等），供消息气泡与流式内容使用。
   - `ChatUiUtils`：`getChineseFont(style, size)`，供 ChatPanel、AurodActionPanel 等复用。

**优点**：依赖更清晰，后续可替换 Chat UI 或做单元测试。  
**缺点**：需约定 ToolWindow 与 Content 的生命周期，确保 facade 可用。

---

### 方案 C：按领域重组包结构（可选、中长期）

**目标**：包名与领域一致，便于新成员理解与依赖方向约束。

- 例如：`core.api`（LLM 接口）、`core.provider`（各 LLM 实现）、`core.context`、`core.patch`、`ui.chat`、`ui.actions`、`app.startup`、`app.settings` 等。
- 可配合模块化（Gradle 多模块）或仅重命名包；若重命名，需全局替换 import 与 plugin.xml 中的类名。

**优点**：结构清晰，依赖边界更明确。  
**缺点**：改动面大，需全面回归测试；当前包结构已按功能划分，收益相对方案 A/B 较小。

---

## 三、实施顺序建议

1. **先做方案 A**：清理 + LLM 拆文件 + 启动与文档更新。  
2. **再做方案 B**：ChatFacade + Actions 解耦；视需要做 ChatPanel 子组件拆分。  
3. **方案 C** 作为后续可选步骤，在团队认可且有时间时再做。

---

## 四、本次实施范围（方案 A + 方案 B 门面部分）

- 执行方案 A 全部步骤。  
- 执行方案 B 的“ChatFacade 接口 + Actions 通过门面发送消息”，不强制拆分 ChatPanel 子组件。  
- 更新 `ARCHITECTURE.md` 以反映新结构与依赖关系。
