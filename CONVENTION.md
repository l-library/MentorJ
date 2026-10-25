# MentorJ 开发约定
面向全体成员与 AI agent 的工程约定。产品背景与功能规划见 [README.md](README.md)。

## 1. 项目总体结构

MentorJ 是一个 **IntelliJ Platform 插件**，使用 **Java 21** 开发，Gradle（Kotlin DSL）构建。

```
.
├── .run/                       预置 Run/Debug 配置（Run IDE with Plugin / Run Tests / Run Verifications）
├── gradle/
│   ├── wrapper/                Gradle Wrapper（当前 9.7.1）
│   └── libs.versions.toml      依赖版本目录（versions 统一在这里声明）
├── src/
│   ├── main/
│   │   ├── java/               插件源码，包前缀 edu.mentorj
│   │   └── resources/
│   │       ├── META-INF/plugin.xml   插件清单（id、名称、vendor、扩展点注册）
│   │       └── messages/             文案 bundle
│   └── test/
│       └── java/               测试源码，目录结构镜像 main
├── build.gradle.kts            构建定义：Java 21 toolchain、IntelliJ Platform 依赖与测试框架
├── settings.gradle.kts         依赖仓库与 Gradle 插件版本
├── gradle.properties           group=edu.mentorj、version、configuration/build cache 开关
├── .editorconfig               机器强制的编辑格式规则
├── .gitattributes              机器强制的换行规则
├── API.md                      模块协作契约（三个小组的接口约定）
├── CHANGELOG.md                变更日志（Keep a Changelog 格式）
└── README.md                   产品背景与功能规划
```

- 插件 id 为 `edu.mentorj.MentorJ`（plugin.xml 的 `<id>`），**发布后不可更改**。Gradle 的 `rootProject.name` / `group` 与插件 id 功能不同，无须一致。
- 编译目标为 IntelliJ IDEA `2025.3.6.1`（build.gradle.kts 的 `intellijIdea(...)`）；测试用 JUnit 4 + IntelliJ Platform Test Framework。
- 依赖与版本统一写在 `gradle/libs.versions.toml`，不要在 build.gradle.kts 里写死版本号。
- plugin.xml 里的 `<vendor>`、`<description>` 目前还是模板占位内容，正式发布前需替换。

## 2. 开发环境

- **JDK 21**：由 Gradle toolchain + foojay-resolver 自动下载，本机无需手动安装；若要直接在 IDE 中编译，把项目 SDK 设为 JDK 21。
- **Gradle**：一律用仓库自带的 wrapper `./gradlew`（Windows 用 `gradlew.bat`），不要用本机安装的 Gradle，避免版本漂移。wrapper 升级通过 `./gradlew wrapper` 提交，不手改 `gradle-wrapper.properties`。
- **IDE**：IntelliJ IDEA，按 Gradle 项目导入；开启 EditorConfig 支持（Settings → Editor → Code Style → Enable EditorConfig support）。
- 已启用 configuration cache 与 build cache（gradle.properties）。构建脚本改动后若出现缓存相关异常，先用 `--no-configuration-cache` 复现再排查。

常用命令：

| 命令 | 作用 |
| --- | --- |
| `./gradlew build` | 编译并打包插件 |
| `./gradlew test` / `./gradlew check` | 运行测试与检查 |
| `./gradlew runIde` | 启动加载了本插件的沙箱 IDE（即 .run 的 Run IDE with Plugin，可直接 Debug） |
| `./gradlew verifyPlugin` | 校验插件与目标 IDE 的兼容性 |
| `./gradlew patchChangelog` | 发布时把 `[Unreleased]` 段落落成正式版本段 |

提交前用 `git diff --check` 自查空白与换行问题。

## 3. Git 协作规范

### 3.1 分支模型（Git Flow，只有 main 和 feat 分支）

- **main**：稳定分支，任何时候都可发布。只接受 PR 合入，不直接 push。
- **feat/\<名称\>**：功能分支，从 main 拉出，PR 合回 main 后**立即删除**（短命分支）。纯缺陷修复可用 `fix/<名称>`。
- 命名用 kebab-case 英文短语，如 `feat/action-toolbar`、`fix/npe-on-empty-project`。
- 一个分支只承载一件事（一个功能或一个修复），做完即合，不长期挂账。

### 3.2 Commit 信息（Conventional Commits）

- 格式：`<type>(<scope>): <subject>`。type/subject 用**英文**，subject 用祈使句、不超过 72 字符、结尾不加句号；scope 可省略。
- type 取值：`feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore`。
- body（可选）说明**为什么**这么改，每行不超过 72 字符；footer 可写 `Closes #123`、`BREAKING CHANGE: ...`。
- 一个 commit 只做一件事；不要把无关的格式化改动混进功能 commit（见第 4 节）。

示例：

```
feat(toolwindow): add refresh action to result tree

The tree kept stale results after re-running analysis, which made
comparing runs impossible. Closes #12.
```

### 3.3 PR

- 标题沿用 Conventional Commit 格式；描述至少写清：**动机（为什么）**、**改动内容**、**验证方式**（跑了哪些命令、手测了什么）。UI 改动附截图或录屏。
- 合并前要求：
  - 至少 **1 名成员 approve**；
  - `./gradlew check` 通过；涉及 plugin.xml、依赖或构建脚本的改动，再跑 `./gradlew verifyPlugin`；
  - 用户可见的改动在 [CHANGELOG.md](CHANGELOG.md) 的 `[Unreleased]` 下补一条。
- 合并方式：**merge commit**（保留提交历史），不用 squash / rebase 合并。review 期间的修改直接在分支上追加 commit 或 force-push 均可，合并前不强制整理历史。
- 合并后删除功能分支；PR 描述或 commit footer 用 `Closes #N` 关联 issue。
- PR 保持小而聚焦，大改动拆成多个 PR。

## 4. 编码格式

- 所有文本文件统一 **UTF-8（无 BOM）+ LF**。唯一例外：`*.bat` / `*.cmd` 用 CRLF。
- 缩进用空格，宽度由 [.editorconfig](.editorconfig) 规定：Java / XML 为 **4 空格**；Gradle Kotlin DSL（`*.kts`）构建脚本同为 4 空格。
- 文件末尾保留一个空行；去掉行尾空白（Markdown 除外）。
- [.editorconfig](.editorconfig) 与 [.gitattributes](.gitattributes) 是机器强制的规则：IDE 请开启 EditorConfig 支持；提交前可用 `git diff --check` 检查空白与换行问题。
- 不要在同一个 commit 里做全文件格式化。

## 5. 一般编码须知

### 动手前

- 不臆测：先读文件与调用方，弄清受影响的流程再改；不假设代码行为。
- 只做被要求的事：不顺手重构、不改无关格式、不做无关修复。范围之外的问题记录下来另行处理。

### 实现

- 决策阶梯，停在第一个能解决问题的选项：这个东西需要存在吗 → 复用仓库里已有实现 → 用标准库 → 用平台原生能力 → 用已安装依赖 → 一行能写清就写一行 → 否则写最小可运行实现。
- 优先删除而不是新增。用最少的文件、最小的 diff。
- 写无聊的代码，不写聪明的代码。避免多余抽象、单实现接口、投机性配置、为未来预留的脚手架。
- 修根因，不修症状；改共享行为前先看所有调用方，能修公共路径就修公共路径。
- 注释写「为什么」，不写「做了什么」；能靠命名说清的不写注释。

### 不可妥协

- 边界输入校验、防数据丢失的错误处理、安全控制、可访问性、关键边界 case 的正确性——任何简化都不能牺牲这些。
- 密钥、token、个人配置不入库。
- 非平凡的新逻辑必须留一个最小可运行检查（测试或脚本）；琐碎改动不必补测试。测试用 JUnit 4 + IntelliJ Platform Test Framework，目录结构与命名镜像被测代码；测试里写具体输入输出，不写抽象桩数据。

## 6. IntelliJ 插件开发须知

- 行为验证以 `./gradlew runIde` 的沙箱实测为准，单测只覆盖可隔离的纯逻辑。
- UI 文案不硬编码：放进 `src/main/resources/messages/` 的 message bundle，用 key 引用，为后续国际化留口。
- 扩展点、服务、监听器统一在 `plugin.xml` 注册（或用平台注解），注册项写注释说明用途。
- 平台 API 的 deprecation / 跨版本兼容警告要及时处理；发版前跑 `./gradlew verifyPlugin` 确认兼容性。
- `since-build` / `until-build` 与声明的目标 IDE 版本保持一致。
