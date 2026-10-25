# MentorJ

> An IntelliJ IDEA plugin that serves as a teaching assistant (TA) of the Java Enterprise Application Development course.

MentorJ 是《Java 企业级应用开发》课程的助教插件，运行在 IntelliJ IDEA 里，面向大学中的 Java 学习者。它以侧边栏 AI Agent 的形式常驻 IDE，提供课程答疑与编程辅助。项目目前处于早期开发阶段。

## 功能

- 侧边栏 AI Agent，身份是“助教”，负责与学习者对话。
- 知识问答、辅助编程，覆盖大学 Java 学习者的日常学习场景。
- RAG 检索预处理后的课程材料（如 PPT），基于检索结果给出可信回答。
- 读取用户当前的编程上下文；选中代码段后右键，可直接就这段代码提问。
- 自动化编码过程中常见且重复的任务，例如单元测试生成、git 提交消息生成，以及同类工作。

## 开发

构建目标为 IntelliJ IDEA 2025.3.6.1，需要 JDK 21（Gradle toolchain 自动下载，无需手动安装）。常用命令：

```bash
./gradlew build   # 编译并打包插件
./gradlew runIde  # 启动加载了本插件的沙箱 IDE
./gradlew check   # 运行测试
```

目录结构、开发环境、Git 协作与编码约定见 [CONVENTION.md](CONVENTION.md)。
