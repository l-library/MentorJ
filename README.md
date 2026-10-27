# MentorJ

> An IntelliJ IDEA plugin that serves as a teaching assistant (TA) of the Java Enterprise Application Development course.

MentorJ 是《Java 企业级应用开发》课程的助教插件，运行在 IntelliJ IDEA 里，面向大学中的 Java 学习者。它以侧边栏 AI Agent 的形式常驻 IDE，提供课程答疑与编程辅助。项目目前处于早期开发阶段。

## 功能

- 交互界面内嵌于 IntelliJ IDEA（侧边栏 / tool window）：侧边栏 AI Agent 身份是“助教”，以课程专属问答聊天机器人的形式与学习者对话。
- RAG 课程问答，回答标注来源：
  - 预处理课程材料（如 PPT），切分成可检索的知识块并建立索引。
  - 用户提出编程相关问题时，检索知识库中最相关的知识块，与原始问题一起交给 LLM 服务，回答结合通用 Java 知识与课程材料中的具体、可验证信息。
  - 基于检索材料生成的回答必须标注来源文档与页码/范围；未检索到相关材料、只能凭 LLM 通用知识回答时，须明确说明（如 “Response is based on general knowledge; no specific course material is referenced”）。
- 上下文感知：读取用户当前的编程上下文；选中代码段后右键，可直接就这段代码提问。
- 三个智能操作：
  - 按需求生成单元测试：对选中的 Java 类或方法，先弹出文本输入框，让用户以自然语言描述要测试的场景或需求（如“测试用户 ID 不存在的情况”），再结合上下文源代码调用 LLM，生成针对该场景的 JUnit 测试类。
  - Git 提交消息生成：读取所有 staged 文件的改动（类似 git diff）作为上下文交给 LLM，分析改动的语义意图（修 bug、新功能实现、重构等），按 Conventional Commits 规范生成结构化的提交消息并展示给用户。
  - 创意智能操作：除上述两项外，团队自行设计并实现的第三个创意智能操作（具体方案待定）。

## 开发

构建目标为 IntelliJ IDEA 2025.3.6.1，需要 JDK 21（Gradle toolchain 自动下载，无需手动安装）。常用命令：

```bash
./gradlew build   # 编译并打包插件
./gradlew runIde  # 启动加载了本插件的沙箱 IDE
./gradlew check   # 运行测试
```

## LLM 接入（同济 AI 服务平台）

插件通过同济平台的 Chat Completions 接口进行非流式生成，Agent 可在生成过程中调用课程检索工具。
已根据平台调用示例设置默认地址和模型，运行前只需配置 `MENTORJ_LLM_API_KEY`。

| 变量 | 内容 |
| --- | --- |
| `MENTORJ_LLM_API_KEY` | 必填，同济平台签发的 API Key |
| `MENTORJ_LLM_BASE_URL` | 可选，默认 `https://agi.tongji.edu.cn/v1` |
| `MENTORJ_LLM_MODEL` | 可选，默认 `DeepSeek-V4.1-Flash` |
| `MENTORJ_LLM_TIMEOUT_SECONDS` | 可选，请求超时秒数，默认 90，范围 1–600 |
| `MENTORJ_LLM_MAX_TOKENS` | 可选，每次响应输出上限，默认 8192；插件接受 1–32768，实际须符合所选模型限制 |

默认请求地址为 `https://agi.tongji.edu.cn/v1/chat/completions`，通过
`Authorization: Bearer <API Key>` 认证。请求使用 `max_tokens: 8192`、
`temperature: 0.7`、`stream: false`。输出额度从平台示例中的 1000 提高到 8192，
为课程解释与代码示例留出空间；这是最大额度，并不要求模型用完。
如果回答仍被截断，已有正文会保留，并在开头明确标注“回答未完成”；生成的代码可能不完整。
只有推理内容、没有回答正文时会提示当前额度，且不展示推理内容；被截断的工具调用不会执行。
Base URL 和模型可通过上述环境变量覆盖；不填写时使用默认值，显式配置为空时会提示错误。
代码会给 Base URL 追加 `/chat/completions`，也支持直接填写完整的 Chat Completions 地址。
远程服务须使用 HTTPS，本机服务可使用 HTTP。

在 IntelliJ 的 **Run IDE with Plugin** 运行配置中设置环境变量，然后启动沙箱 IDE。
使用终端时，在同一终端设置变量后执行 `./gradlew --no-daemon runIde`。
API Key 只保存在本机运行配置或环境变量中；不要写入源码或共享的 `.run` 文件。
项目不会自动加载 `.env` 文件。更改环境变量后需重新启动沙箱 IDE。
如果此前显式设置了 `MENTORJ_LLM_MAX_TOKENS=1000`，请删除该配置以使用新默认值，
或改为 `8192`；显式设置优先于默认值。已运行的旧插件也需重新构建并重启沙箱后验证。

实现使用 JDK HTTP 客户端和平台已有的 Gson，未添加依赖。未配置、认证失败、限流、
超时、网络错误、无效响应或截断回答会在现有回答区域显示说明；不会显示原始错误响应或密钥。
请求不自动重试，避免重复计费。成功响应继续保留课程引用和通用知识声明。

Agent 核心已实现：

- `ASK`：初次检索后，结合问题、文件源码和选中代码生成回答；模型可追加检索。
- `GENERATE_UNIT_TEST`：要求测试场景和源码，按实际方法签名生成 JUnit 测试；
  优先匹配指定版本，未指定时默认 JUnit 4。生成结果仍需在目标项目编译、运行。
- `GENERATE_COMMIT_MESSAGE`：要求非空 staged diff，仅按暂存改动和用户附加要求生成英文 Conventional Commit。
  不检索课程材料，不执行 Git 操作。
- 工具循环：发送 `tools`、保留 assistant `tool_calls`、用 `tool_call_id` 回填结果，
  最多执行 3 轮工具、每轮 8 次，最多请求模型 4 次；最后一轮关闭工具并要求完成回答。
- 来源汇总：初次检索及工具追加检索的结构化来源统一去重，保留文档和页码；
  未检索到课程材料时保留通用知识标志，调用错误不冒充无检索结果。
- 参数校验、未知工具和工具失败反馈、取消请求、超长输入与上下文限制；
  单次用户上下文最多 60,000 字符，累计对话最多 120,000 字符，工具输出最多 20,000 字符。
  每次请求独立处理，当前不保存跨问题的聊天历史。

原生工具协议支持本地模拟服务验证；同济网关和 `DeepSeek-V4.1-Flash` 的工具调用能力
仍须使用真实 Key 联调，文本调用示例不代表网关一定支持工具。适配器保留服务返回的
`reasoning_content` 供后续工具轮次回填，不展示该字段，参见
[DeepSeek 工具调用协议](https://api-docs.deepseek.com/guides/thinking_mode/#tool-calls)。
共享接口扩展保留了旧构造器和字符串工具方法，变更说明及三方联调评审项见 [API.md](API.md)。

当前仍需其他模块完成：右键提问、生成测试与提交消息的 UI 接线，以及课程文档解析、
向量化和真实检索。第三项 `CREATIVE` 尚未确定方案，调用时会明确提示暂不可用。

验证方法：执行 `./gradlew check build`，测试会使用本机模拟 HTTP 服务，不调用同济平台或消耗额度。
测试覆盖文本生成、工具协议往返、循环上限、引用去重、请求隔离、参数和错误处理。
配置真实 Key 后，在沙箱侧边栏提问“解释 Java 的依赖注入”，确认返回模型回答；
RAG 接入材料后再验证追加检索与引用。本地自动测试不代表真实平台联调已经完成。
RAG 当前仍是占位检索，返回空列表，因此会显示通用知识声明。

目录结构、开发环境、Git 协作与编码约定见 [CONVENTION.md](CONVENTION.md)，三个小组的模块协作接口约定见 [API.md](API.md)。
