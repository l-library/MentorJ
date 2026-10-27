# MentorJ 模块协作契约（API.md）

> 本文件是三个小组并行开发的接口约定，保证模块之间协作不出问题。**改契约必须先改本文件，三方确认后再改代码**；文档与实现不一致时以本文件为准。

小组分工：

| 模块 | 职责 | 包 / 目录 | 不要做 |
| --- | --- | --- | --- |
| 界面组 | 侧边栏 tool window、编辑器右键菜单、三个智能操作的触发入口与文本输入框、上下文采集（选中代码、staged diff）、回答与引用 / 免责声明的展示 | `edu.mentorj.ui`、`src/main/resources/messages/` | 不直接调用 LLM，不实现检索 |
| agent 核心组 | `AgentLoop`（循环）、系统提示词（含引用规则）、工具调用（`ToolRegistry` / `Tool`）、`LlmService` 的真实实现（LLM 接入）、单测与 commit message 的生成提示词 | `edu.mentorj.agent` | 不碰 Swing / PSI / EDT |
| RAG 和其他组 | 文档处理（解析课程材料、切块、保留页码 / 范围）、知识库与检索、检索工具、CI、docs（含本文件） | `edu.mentorj.rag`、CI 配置、`*.md` | 不生成回答文案 |
| 共享契约 | 三个模块共用的接口与数据类型，只放契约不放实现 | `edu.mentorj.api` | 改动需三方确认 |
| 组装 | 把三个模块接起来，暴露唯一入口 | `edu.mentorj.MentorJProjectService` | 只做接线，不写业务逻辑 |

三个模块之间**不互相 import**，全部通过 `edu.mentorj.api` 里的接口协作；唯一允许 import 各模块具体实现的地方是组装点 `MentorJProjectService`。

```
界面组(ui) ── AgentRequest / AgentResponse ──▶ agent 核心(agent)
                                                  │
                                        KnowledgeBase.retrieve / Tool
                                                  ▼
                          RAG(rag)：DocumentProcessor → 知识库 → 检索
```

## 1. 包结构

```
src/main/java/edu/mentorj/
├── api/                        共享契约（接口 + 数据类型），改动需三方确认
│   ├── AgentAction.java        智能操作类型枚举
│   ├── AgentRequest.java       一次请求（问题 / 场景 + 上下文）
│   ├── AgentResponse.java      一次回答（正文 + 引用 + 免责标志）
│   ├── AgentService.java       agent 入口接口
│   ├── Citation.java           来源引用（文档 + 页码/范围）
│   ├── EditorContext.java      编辑器上下文（文件、源码、选中代码）
│   ├── KnowledgeBase.java      知识库检索接口
│   ├── KnowledgeChunk.java     知识块（内容 + 来源 + 页码/范围 + 分数）
│   ├── LlmService.java         LLM 调用接口（含消息 / 工具调用数据类型）
│   └── Tool.java               agent 可调用的工具接口
├── agent/                      agent 核心组：循环、系统提示词、工具调用、LLM 接入
│   ├── AgentLoop.java          AgentService 实现：检索 → 提示词 → LLM → 工具循环
│   ├── SystemPromptBuilder.java 系统提示词（助教身份 + 引用规则 + 分操作指令）
│   ├── ToolRegistry.java       工具注册与调用
│   ├── ChatCompletionsLlmService.java  文本生成与原生工具调用接入
│   ├── LlmSettings.java        本地环境变量配置
│   └── LlmException.java       可展示的配置与调用错误
├── rag/                        RAG 组：文档处理、知识库、检索工具
│   ├── DocumentProcessor.java  课程材料解析与切块（保留页码/范围）
│   ├── KnowledgeSearchTool.java 检索工具（实现 api.Tool，供 agent 工具调用）
│   └── RagKnowledgeBase.java   KnowledgeBase 实现
├── ui/                         界面组：侧边栏、右键菜单、智能操作入口
│   ├── AskCodeAction.java      编辑器右键：就选中代码提问
│   ├── EditorContexts.java     编辑器上下文采集
│   ├── GenerateCommitMessageAction.java  生成 Git 提交消息
│   ├── GenerateUnitTestAction.java       生成单元测试
│   └── MentorJToolWindowFactory.java     侧边栏 tool window
├── MentorJBundle.java          文案 bundle 访问器
└── MentorJProjectService.java  组装点：构造知识库、工具、AgentService
```

## 2. 契约清单（`edu.mentorj.api`）

### 2.1 数据类型

| 类型 | 字段 | 语义 |
| --- | --- | --- |
| `AgentAction` | `ASK`、`GENERATE_UNIT_TEST`、`GENERATE_COMMIT_MESSAGE`、`CREATIVE` | 一次请求要做的事；`CREATIVE` 为创意智能操作预留 |
| `EditorContext` | `filePath`、`sourceCode`、`selectedCode` | 界面组采集；任何字段可为 `null`，表示不适用 / 未提供 |
| `AgentRequest` | `action`、`userInput`、`editorContext`、`stagedDiff` | 见下方「各操作对字段的要求」 |
| `Citation` | `document`、`pageOrScope` | 一条来源引用：来源文档 + 页码或范围 |
| `AgentResponse` | `text`、`citations`、`generalKnowledgeOnly` | `text` 为回答正文；`citations` 为提供给 LLM 的知识块来源；`generalKnowledgeOnly` 为 `true` 表示没有检索到课程材料 |
| `KnowledgeChunk` | `text`、`document`、`pageOrScope`、`score` | 一个知识块及其来源，`score` 越大越相关 |

各操作对 `AgentRequest` 字段的要求：

| 操作 | `userInput` | `editorContext` | `stagedDiff` |
| --- | --- | --- | --- |
| `ASK` | 用户的问题（必填） | 可选，右键提问时带选中代码 | 不用 |
| `GENERATE_UNIT_TEST` | 自然语言描述的测试场景 / 需求（必填，来自文本输入框） | 必填，`selectedCode` 或 `sourceCode` 为选中的类 / 方法 | 不用 |
| `GENERATE_COMMIT_MESSAGE` | 可空 | 不用 | 必填，staged 改动（git diff 格式），界面组采集 |
| `CREATIVE` | 待定 | 待定 | 待定 |

### 2.2 接口

```java
// agent 核心组实现，界面组调用。同步阻塞，界面组必须在后台线程调用，禁止在 EDT 调用。
public interface AgentService {
    AgentResponse respond(AgentRequest request);
}

// RAG 组实现，agent 核心组调用。按 score 降序返回，无结果返回空表，不返回 null。
public interface KnowledgeBase {
    List<KnowledgeChunk> retrieve(String query, int topK);
}

// agent 核心组实现真实版本（LLM 接入）；Request 携带系统提示词、消息与可用工具。
public interface LlmService {
    Result complete(Request request);
    // 嵌套数据类型：Message（role: system/user/assistant/tool）、ToolSpec、ToolCall、Request、Result
}

// 各组都可以提供工具实现，注册进 ToolRegistry 后由 agent 循环调用。
public interface Tool {
    LlmService.ToolSpec spec();          // 工具名、描述、参数 JSON Schema
    String execute(String argumentsJson); // 返回文本结果，回填给 LLM
    // 默认包装 execute，旧实现无需修改；检索工具覆盖此方法提供真实来源。
    default ExecutionResult executeWithSources(String argumentsJson);
    record ExecutionResult(String text, List<Citation> citations) {}
}
```

### 2.3 本地 Agent 分支的兼容扩展（供三方联调评审）

本分支按 Agent 完善任务实现以下增量，保留旧构造器和工具方法；合并共享契约前仍需三方评审。

- `Message(role, content, toolCalls, toolCallId, reasoningContent)`：assistant 原样保留调用列表和
  服务返回的 `reasoning_content`；tool 消息用 `toolCallId` 对应调用。普通消息继续使用双参数构造器。
  使用 `Message.assistant(Result)` 和 `Message.tool(callId, text)` 回填完整协议数据。
  旧 `Message.tool(text)` 保留源码兼容，但缺少 ID，真实适配器会拒绝发送。
- `Result(content, toolCalls, reasoningContent)`：保留双参数构造器；无工具调用必须有非空正文。
  `reasoningContent` 仅供协议回填，不展示给用户。
- `Request`、`Message`、`Result`、`ExecutionResult` 对列表取不可变副本，列表不得为 null。
- `Tool.executeWithSources` 返回正文及结构化来源；仅返回字符串的旧工具继续工作，但不产生引用。
  工具负责校验自己的参数字段。失败与无结果的输出不携带来源。
- 最终 `AgentResponse.citations` 合并初次检索及成功工具检索提供给模型的来源，按出现顺序去重。
  不从模型生成的文字提取来源，不伪造文档或页码。错误回答不附课程引用或通用知识声明。
- 文本回答因长度截断但已有正文时，`text` 保留正文并在开头标注“回答未完成”，仍附已提供的来源。
  无正文或存在被截断的工具调用时返回错误说明，且不执行该轮工具；不改变共享数据类型。
- Agent 每次请求独立保存上下文，最多执行 3 轮工具，每轮最多 8 次调用，最多请求模型 4 次。
  最后一轮不再提供工具。提交消息生成仅使用 staged diff 和用户要求，不检索课程材料。
  用户上下文限 60,000 字符，累计对话限 120,000 字符，单次工具输出限 20,000 字符；超限明确报错。
  这些是本插件的资源上限，不代表服务商 token 上限。

## 3. 关键数据流

1. **侧边栏问答**：界面组收问题 → `AgentService.respond(ASK)` → agent 核心组：`KnowledgeBase.retrieve` 检索知识块 → 组装系统提示词（含引用规则）与上下文 → `LlmService`（必要时走工具循环）→ `AgentResponse` → 界面组展示正文 + 引用 / 免责声明。
2. **右键就选中代码提问**：界面组用 `EditorContexts` 采集 `EditorContext` → 同 1（`ASK`）。
3. **单测生成**：界面组弹文本输入框收集自然语言场景 → `respond(GENERATE_UNIT_TEST)` → agent 核心组按场景 + 上下文代码生成 JUnit 测试类 → 界面组展示。
4. **commit message 生成**：界面组采集 staged diff → `respond(GENERATE_COMMIT_MESSAGE)` → agent 核心组分析语义意图并按 Conventional Commits 生成消息 → 界面组展示。

智能操作分工：**界面组**负责触发入口、文本输入框、上下文采集与结果展示；**agent 核心组**负责生成提示词与生成调用；**RAG 和其他组**不参与智能操作本身。

## 4. 全局规则（实现时必须遵守）

- **引用与免责声明**：检索到课程材料时 `citations` 必须给出 `document` + `pageOrScope`；检索不到时 `generalKnowledgeOnly` 必须为 `true`，界面组必须显式声明“回答仅基于通用知识，未引用课程材料”（如 “Response is based on general knowledge; no specific course material is referenced”）。系统提示词里同步约束 LLM 行文引用来源。
- **线程**：`AgentService.respond` 可能长时间阻塞，界面组一律放后台线程执行，只在更新 UI 时回到 EDT。
- **空值**：契约里的 `null` 只表示“不适用 / 未提供”；`List` 一律返回空表而不是 `null`。
- **文案**：所有 UI 文案进 `src/main/resources/messages/MentorJBundle.properties`，用 `MentorJBundle.message(key, ...)` 取，不硬编码。
- **注册**：扩展点 / action / 服务注册在 `plugin.xml`（或平台注解），每条注册加注释说明用途。
- **契约变更流程**：先改本文件 → 三方确认 → 再改代码 → 同一提交或紧随的提交里保持文档与实现一致。

## 5. 联调检查单

- [ ] `./gradlew build` 通过（改动 plugin.xml 或依赖时加跑 `./gradlew verifyPlugin`）
- [ ] `./gradlew runIde`：侧边栏能看到 MentorJ tool window；编辑器右键菜单有两个智能操作入口
- [ ] 提问流程：有问题 + 有检索结果时展示引用（文档 + 页码/范围）；无检索结果时展示通用知识声明
- [ ] 三个智能操作的请求字段符合「各操作对字段的要求」

## 6. 待定事项

- 创意智能操作（`CREATIVE`）的具体方案：待定，枚举与请求字段已预留。
- LLM 服务供应商：同济 AI 服务平台，默认 Base URL 为 `https://agi.tongji.edu.cn/v1`，
  模型为 `DeepSeek-V4.1-Flash`。API Key 通过本地环境变量配置，地址和模型可覆盖，见 README。
- 原生工具调用：本地适配器与循环已实现兼容协议，使用模拟 HTTP 服务验证；
  同济网关对所选模型的工具支持仍需带真实 Key 联调，不能由文本调用示例推定。
- embedding 与向量检索方案：待 RAG 组确定。
- 回答的流式输出：本期不做，`AgentService` 为一次性返回。
