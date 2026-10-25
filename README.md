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

目录结构、开发环境、Git 协作与编码约定见 [CONVENTION.md](CONVENTION.md)，三个小组的模块协作接口约定见 [API.md](API.md)。
