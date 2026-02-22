# Febyher AI 项目架构说明

Febyher AI 是一款基于 IntelliJ Platform 的 AI 编程助手插件，支持多 LLM 提供商、聊天、代码解释/重构、Agent 规划与 Diff 应用等功能。

---

## 1. 技术栈与构建

| 项目 | 说明 |
|------|------|
| 平台 | IntelliJ Platform Plugin (Kotlin + Gradle) |
| 语言 | Kotlin，JVM 21 |
| 构建 | Gradle Kotlin DSL，intellij-platform-gradle-plugin |
| 依赖 | 版本目录 `gradle/libs.versions.toml`，阿里云 Maven 镜像 |

---

## 2. 目录结构概览

```
Febyher/
├── src/main/
│   ├── kotlin/org/febyher/
│   │   ├── actions/          # 编辑器/项目右键与菜单动作
│   │   ├── agent/            # Agent 编排、结构化输出与 Prompt
│   │   ├── chat/             # 聊天工具窗口、ChatFacade 门面、会话、消息
│   │   ├── context/          # 代码/项目上下文收集与构建
│   │   ├── llm/               # LLM 接口与类型、基类、多提供商实现、工厂与管理器
│   │   │   ├── LLMService.kt       # StreamCallback、LLMService、ProviderConfig
│   │   │   ├── BaseLLMService.kt   # 通用 HTTP/SSE 逻辑
│   │   │   ├── MoonshotLLMService.kt / DeepSeekLLMService.kt / NvidiaLLMService.kt
│   │   │   ├── LLMServiceFactory.kt  # 工厂、LLMServiceManager、LLMServiceProvider
│   │   │   ├── LLMDiagnosticLogger.kt
│   │   │   └── aurod/         # Aurod 专用 API、会话、模型
│   │   ├── notification/     # 通知封装
│   │   ├── patch/             # Diff 解析、预览、应用
│   │   ├── safety/            # 审计日志、权限
│   │   ├── settings/          # 设置存储与 UI
│   │   └── startup/           # 项目启动活动
│   └── resources/
│       ├── META-INF/plugin.xml
│       ├── icons/
│       └── messages/
├── src/test/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

---

## 3. 架构分层与模块职责

### 3.1 入口与扩展（plugin.xml）

- **插件 ID**：`org.febyher`，名称：Febyher AI。
- **扩展**：
  - 通知组：`Febyher AI Notifications`
  - 工具窗口：`ChatToolWindowFactory`（ID: `Febyher AI`，右侧）
  - 启动活动：`MyProjectActivity`
  - 项目服务：`LLMServiceManager`、`AurodSessionManager`
  - 应用服务：`CopilotSettings`、`AuditLogService`、`PermissionManager`
  - 设置面板：`CopilotSettingsConfigurable`（Tools > Febyher AI）
- **动作**：
  - 编辑器右键：解释代码、重构代码、发送到 Agent、选择文件
  - 项目视图右键：添加到 AI 上下文
  - Tools 菜单：选择文件、会话管理

### 3.2 UI 与交互层

| 模块 | 主要类 | 职责 |
|------|--------|------|
| **chat** | `ChatFacade`, `ChatToolWindowFactory`, `ChatPanel`, `SessionManagementPanel`, `AurodSessionBar`, `ChatMessageRenderer`, `ChatUiUtils`, `AurodActionPanel`, `ChatMessage` | `ChatFacade` 门面；ChatPanel 主面板；`SessionManagementPanel` 统一会话管理（Aurod 标签 + 本地会话标签）；`AurodSessionBar` 会话栏 UI；`ChatMessageRenderer` 消息渲染；`ChatUiUtils` 字体工具；`AurodActionPanel` Aurod 会话/模型/测试 |
| **chat.session** | `LocalChatSession`, `LocalMessageDto`, `LocalSessionStorage`, `LocalSessionPanel` | 本地会话数据模型与按项目持久化（`.idea/febyher/chat_sessions.json`），仅用于非 Aurod 模型（Kimi/DeepSeek/NVIDIA）；新建/保存/加载/删除 |
| **settings** | `CopilotSettingsConfigurable`, `CopilotSettings` | 设置持久化与设置 UI（API Key、Provider、模型、Aurod 等） |
| **actions** | `ExplainCodeAction`, `RefactorCodeAction`, `SendToAgentAction`, `SelectFilesAction`, `AurodManageAction` | 通过 `ChatFacade` 或 Agent/Context 与聊天与 LLM 交互，不直接依赖 `ChatPanel` 实现类 |

### 3.3 上下文层（context）

| 类 | 职责 |
|----|------|
| `CodeContext` | 单次请求的代码上下文：当前文件、语言、选中代码、光标行、打开文件列表；`fromEditor(project)` 从当前编辑器采集 |
| `FileContext` | 单文件元数据与内容：文件名、路径、语言、内容、行数、token 估算、是否选中 |
| `ProjectContext` | 多文件上下文：`FileContext` 列表、总 token、项目路径；提供 `toContextSummary()`、`toFullContext()`、`filterSelected()` |
| `ContextBuilder` | 从项目/选中文件构建 `ProjectContext` |
| `FileSelector` | 多文件选择 UI，供「选择文件」类动作使用 |

### 3.4 Agent 层（agent）

| 类/对象 | 职责 |
|---------|------|
| `AgentOrchestrator` | 按项目单例使用，驱动「规划 → 生成 Diff → 可选应用」流程；调用 `LLMServiceManager` 做流式对话，解析结构化输出并触发 Diff 预览/应用 |
| `AgentSession` | 维护当前 Agent 对话消息队列与处理状态（idle/processing） |
| `AgentState` | 状态：Idle / Planning / Coding / Reviewing / Error |
| `AgentPlan`, `PlanStep` | 执行计划与步骤（目标文件、动作：modify/create/delete） |
| `AgentResponse`, `CodeChange` | 结构化响应（plan、changes、commands、risks、summary）；`parseDiffs()` 转成 `FileDiff` 列表 |
| `StructuredOutputParser` | 从 LLM 文本中抽取 JSON（含 ```json 块），反序列化为 `AgentResponse`，并提取 `AgentPlan` |
| `PromptTemplates` | 系统提示（结构化 JSON 约束）、任务提示、代码修改/审查提示模板 |

### 3.5 LLM 层（llm）

| 文件/组件 | 职责 |
|-----------|------|
| **LLMService.kt** | `StreamCallback`、`LLMService` 接口、`ProviderConfig` 数据类 |
| **BaseLLMService.kt** | 抽象基类：HTTP/SSE 连接、请求体构建、流解析、同步/流式调用、错误与 JSON 解析；子类实现 `getSystemPrompt()`、`getProviderName()`、可选 `getExtraRequestParams()` |
| **MoonshotLLMService.kt** / **DeepSeekLLMService.kt** | OpenAI 兼容的简单实现，仅系统提示不同 |
| **NvidiaLLMService.kt** | 重写 `chat`/`chatStream`，集成 `LLMDiagnosticLogger` 的请求/响应/性能诊断 |
| **LLMServiceFactory.kt** | `LLMServiceFactory` 对象（按 `AIProvider` 创建实例）、`LLMServiceManager`（项目服务）、`LLMServiceProvider`（便捷访问） |
| **LLMDiagnosticLogger.kt** | 请求/响应/性能记录与空响应诊断 |
| **aurod/** | `AurodLLMService`、`AurodApiClient`、`AurodSessionManager` 等 |

#### llm.aurod 子包

| 类 | 职责 |
|----|------|
| `AurodApiClient` | Aurod 专用 HTTP API：认证、会话 CRUD、聊天完成、流式聊天、模型列表等 |
| `AurodLLMService` | 实现 `LLMService`，内部用 `AurodApiClient`；支持当前会话 ID、当前模型；`ensureAuth()` 同步设置认证信息 |
| `AurodSessionManager` | 管理 Aurod 会话列表与当前会话切换，与 `AurodLLMService` 配合 |
| `AurodModels` | Aurod 模型相关数据/常量 |

### 3.6 Patch 层（diff 解析与应用）

| 类 | 职责 |
|----|------|
| `DiffLine`, `DiffHunk`, `FileDiff` | 统一表示 unified diff（行类型：CONTEXT/ADD/REMOVE）；`FileDiff` 含 oldPath/newPath、hunks、是否 create/delete，可计算统计信息 |
| `DiffParser` | 将 LLM 返回的 diff 文本解析为 `FileDiff` 列表 |
| `DiffPreview` | 在 IDE 中展示 diff 链，用户确认后再应用 |
| `PatchApplier` | 将 `FileDiff` 应用到 VFS：修改/创建/删除文件；返回 `PatchResult`（Success / Conflict / Error） |

### 3.7 安全与运维（safety / notification）

| 类 | 职责 |
|----|------|
| `AuditLogService` | 应用级审计日志 |
| `PermissionManager` | 权限控制 |
| `NotificationService` | 统一成功/错误/警告通知 |

### 3.8 其他

| 类 | 职责 |
|----|------|
| `FebyherBundle` | 国际化文案 |
| `MyProjectActivity` | 项目启动时执行（预留初始化入口） |

---

## 4. 数据流与调用关系（概要）

```
用户操作（右键/菜单）
    → actions（ExplainCode / RefactorCode / SendToAgent / SelectFiles / AurodManage）
        → context：CodeContext.fromEditor 或 ContextBuilder/FileSelector 得到 ProjectContext
        → agent：AgentOrchestrator.execute(请求, context, 回调)
            → LLMServiceManager.getService() → LLMService.chatStream(...)
            → StructuredOutputParser 解析 → AgentResponse.parseDiffs() → List<FileDiff>
            → DiffPreview 展示 → 用户确认 → PatchApplier.apply
        → 或直接 ChatPanel 中与 LLM 对话（chatStream），无结构化 Diff
```

- **聊天**：`ChatPanel` 实现 `ChatFacade`，对外提供 `receiveExternalMessage`；Actions 通过工具窗口取 Content 的 component 转为 `ChatFacade` 注入消息。`ChatPanel` 内部使用 `LLMServiceManager.getService()` 做流式对话；Aurod 时与 `AurodSessionManager`、`AurodActionPanel` 配合。
- **设置**：`CopilotSettings` 持久化 Provider、API Key、URL、模型等；`CopilotSettingsConfigurable` 提供 UI；变更后 `LLMServiceManager.refresh()` 使下次 `getService()` 使用新配置。

---

## 5. 服务与生命周期

| 服务 | 级别 | 说明 |
|------|------|------|
| `LLMServiceManager` | Project | 每个项目一个，管理当前 LLM 实例 |
| `AurodSessionManager` | Project | 每个项目一个，管理 Aurod 会话 |
| `CopilotSettings` | Application | 全局一份，持久化到 IDE 存储 |
| `AuditLogService` | Application | 全局审计 |
| `PermissionManager` | Application | 全局权限 |

---

## 6. 配置文件与资源

- **插件元数据**：`src/main/resources/META-INF/plugin.xml`（扩展、动作、依赖）
- **文案**：`src/main/resources/messages/MyBundle.properties`、`FebyherBundle.kt`
- **图标**：`src/main/resources/icons/ai-icon.svg`
- **构建与版本**：`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`

---

## 7. 测试

- 测试：`src/test/kotlin/org/febyher/MyPluginTest.kt`（XML 与重命名示例）；测试数据在 `src/test/testData/`。
- 重构后已移除模板包 `org.jetbrains.plugins.template`、示例服务与未使用的工具窗口工厂；详见 `REFACTORING_PLAN.md`。

此文档随代码变更可更新，以保持与实现一致。
