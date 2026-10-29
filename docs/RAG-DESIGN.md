# MentorJ RAG 设计

本文件记录 RAG 组的设计决策与实测数据，供实现、评审与报告撰写共用。契约部分以 [API.md](../API.md) 为准，此处只讲实现怎么落地以及为什么这样选。

## 1. 语料与边界

| 项 | 值 |
| --- | --- |
| 语料 | `assets` 下 10 个 PDF（Lec-0 ~ Lec-5 + 作业说明） |
| 规模 | 203 页、约 12.7 万字符（≈3 万 token） |
| 文本层 | 均为可提取文本 PDF，`pdftotext` / PDFBox 输出干净；页脚印的页码与 PDF 页序一致 |
| 支持格式 | **仅 PDF**。其余格式进 `ImportResult.failures`，不静默丢弃 |

语料规模决定了设计取向：整个知识库能塞进一次上下文，因此检索的价值不在压缩信息量，而在**可解释、可引用、可评测**。不引入向量数据库，索引就是一个 JSON 文件。

> 不引入向量数据库：数据规模小，且单用户，暴力检索需要的时间远小于查询向量化时网络的耗时。引入向量数据库需要处理更复杂的依赖的同时，也没有带来性能的提升。

## 2. 处理管线

```
PDF → 逐页抽文本 → 页脚/页眉清洗 → 页级切块（超长页二次切分）
    → 分词（BM25 倒排） → embedding（可降级） → 写入 .idea/mentorj-index.json
```

### 2.1 解析

- 依赖：`org.apache.tika:tika-core` + `org.apache.tika:tika-parser-pdf-module`（Tika 4.1.0）。为什么只引 PDF 模块：全量 `tika-parsers-standard-package` 会拉进几十个传递依赖，与 IntelliJ 平台自带库（jackson、commons-*）冲突风险高，而本项目只吃 PDF。
- **分工（实测后修正）**：Tika 负责媒体类型检测（非 PDF 直接失败，报清晰原因）；文本抽取逐页走 PDFBox。原因：Tika 4 的 `PDFParser` 输出一段无页边界的纯文本（`BodyContentHandler` 拿到的是拼接结果），拿不到“第 N 页”这个信息，而页码正是引用的全部前提。PDFBox 本来就作为 Tika 自己的依赖随之引入（同一个引擎），不额外增加依赖面。
- 页码即 PDF 页序（与课件页脚一致，验收时可翻页核对）。
- 已提交的测试资料：`../src/test/resources/documents/two-pages.pdf`（Ghostscript 生成的 2 页已知文本），由 `PdfExtractionTest` 断言检测、页数与逐页文本不串页。

### 2.2 切块规格

- **一页一块**是默认粒度：引用天然精确到页，且不需要在页内猜章节边界。
- 单页文本超过 **800 字符**时页内二次切分：按行累积到上限；单行本身超长则按字符硬切。相邻块把上一块的**最后一行**带进下一块（该行不超过 120 字符时），避免句子被切断。
- `pageOrScope` 形态：`p.12`。因为切块以页为界，一个知识块永不跨页，所以不会出现页码范围；`p.12–13` 只留给将来“合并小页”的改动。
- 页码清洗：只删与当前页号相同的数字，有三种排布，都已在实际课件中出现：

  | 排布 | 实例 | 处理 |
  | --- | --- | --- |
  | 独占一行 | `3` | 删整行 |
  | 粘在标题前 | `2Overview` | 删数字，保留 `Overview` |
  | 跟在页脚后 | `Tools and Scenarios 6` | 删数字，保留文字 |

  正文里的序号不动：Lec-4 第 12 页表格里的步骤编号 `1`–`6` 与页号不同，原样保留。
- **不删重复出现的整行**（跑动的页眉/章节页脚）。本来以为可以按“出现在多数页上”来删，但 Lec-3.4 有 4 处重复的 `}`（代码块），按频率删会把代码删坏；`Call Structure Context Tools Loop Verify` 这类页脚重复 26/44 页，对 BM25 影响有限（文档频率高，idf 自然低）。收益不抵风险，所以不做。
- 清洗与切块都是纯字符串函数，不依赖课件即可测试（`DocumentProcessorTest`）。
- 全量实测（`assets` 下 10 个 PDF）：**203 页 → 218 块**，最长块 794 字符（未越 800），页码零残留。
- 已知损失：PDF 文本层不保留代码缩进，代码示例会被压平；检索仍可用，但不能拿 chunk 当可编译代码。

### 2.3 分词

供 BM25 使用，纯本地、零依赖（`Tokenizer`）：

- 小写化，按非字母数字切分（`_`、`.`、`()` 天然成为边界，`snake_case` 因此自动拆开）；
- camelCase 拆成子词（`getUserId` → `get`、`user`、`id`），Java 术语才不会整词失配；
- **拆开后同时保留整词**（`getUserId` 额外索引 `getuserid`）。不这么做的话 `@BeforeEach` 会被拆成 `before`、`each` 两个停用词，整词彻底丢失 —— 实测中这条让“What does @BeforeEach do?”从零命中变成正确命中 Lec-3.4 p.4；
- 去英文停用词（约 90 个）与单字符拉丁词；
- CJK 字符按单字 + 相邻二元组（bigram）入索引，保证中文查询不会因分词缺失而零命中。

停用词只对拉丁词生效，代码标识符与中文不受影响。

## 3. 索引结构

`<project>/.idea/mentorj-index.json`，UTF-8，`version` 用于将来升级时丢弃旧索引：

```json
{
  "version": 1,
  "generatedAt": "2026-10-30T09:12:00Z",
  "chunks": [
    { "document": "Lec-3.4-JUnit.pdf", "pageOrScope": "p.2", "text": "…" }
  ]
}
```

- **只存知识块**：倒排表、文档清单、页数都从 `chunks` 现算，不落盘。全课程材料只有两百多个块，启动重建倒排是毫秒级；存下来就要维护一份可能和知识块不一致的副本。
- 全量 218 块实测 **115 KB**。
- 向量（512 维）接入后作为 `chunks[].vector` 字段加进来，`version` 升到 2。向量存 512 维（`dimensions` 参数做 MRL 截断）：2560 维会让文件大一个量级，对几百个块的检索质量几乎无感。
- 索引按项目存放：换项目即换知识库；`../.idea` 已被 ignore，不随仓库分发。
- 失效策略：`importDocuments` 是全量重建，不做增量与自动重扫。启动时只加载 JSON，不校验原文件是否还在（用户可能换了机器），需要更新就重新导入一次。`version` 不匹配时直接丢弃旧索引，不做迁移。

## 4. 检索

混合检索，两路排名融合（`RagKnowledgeBase`）：

1. **BM25**（`k1=1.2`、`b=0.75`）在 chunk 文本上检索，纯内存倒排表；得分为 0 的块不返回 —— 否则调用方会把无关材料当成引用。
2. **Embedding** 余弦相似度（向量已归一化，等价于点积）。
3. **RRF 融合**：`Σ 1 / (60 + rank_i)`，每路各取前 20 名参与融合，最终取 topK。
4. **降级**：没有 `MENTORJ_LLM_API_KEY`、网络失败或索引里没有向量时，只用 BM25。

同分处理：按块序号排序兜底，保证输出稳定可测。

### 4.1 score 的口径

`KnowledgeChunk.score` 是 RRF 融合分，归一化到 (0, 1]：在每一路都排第一的块得 1.0。

**已知局限，必须告知界面组**：只有一路（当前只有 BM25）时，归一化后的分数只反映名次，不反映匹配强度 —— top-3 会得到 1.000 / 0.984 / 0.968 这类几乎无差别的值。所以界面**不要把 score 当百分比展示**，它只能用来排序或标注“第几位”。两路都接入后（Day 4）才有真实区分度：两路都靠前的块接近 1.0，只在一路出现的块掉到 0.5 附近。

之所以不用 BM25 原始分当 score：它没有上界（可能是 0.5 也可能是 18），界面无法展示，也没法和语义分放在一起比较。

### 4.2 实测数据（2026-10-28，同济 AGI 平台）

| 项 | 实测 |
| --- | --- |
| Base URL | `https://agi.tongji.edu.cn/v1`（OpenAI 兼容） |
| 模型 | `Qwen3-Embedding`（平台另提供 `Qwen3-Reranker`，本期不用） |
| 维度 | 默认 2560，传 `dimensions` 可降到 512 |
| 归一化 | 返回向量已 L2 归一化（实测模长 1.0），余弦相似度即点积 |
| 并发上限 | 套餐限制 2 个并发请求，超出返回 HTTP 429 `并发请求数已达套餐上限` |
| 批大小 | 8 条/请求。16 条约 42 秒、32 条 180 秒不返回（2560 维时测），所以固定 8 |
| 全量导入（218 块） | **2.3 秒**（512 维、批 8、2 并发）；导入 10 个课件实际耗时以 `docs` 下实测为准 |
| 单次查询向量化 | **30–55 毫秒**（512 维、单条） |

因此导入实现为：批大小 8、最多 2 个并发请求（信号量，实测超了就是 429）、单请求超时 60 秒、429/5xx 退避重试 2 次。提问路径单独一条：**超时 5 秒**，失败即本次会话退回纯 BM25 并记日志，不再每次提问都去等超时。

早期（2560 维）测出“8 条要 5–7 秒”，与后来 512 维的 2.3 秒/218 块差距很大：维度截断同时降低了存储与延迟，这是选 512 维的第二个理由。

## 5. 评测

- **golden 集**：约 25 条英文问题，每条标注期望文档 + 页码，覆盖各个课件与“课件里确实没有”的负例。
- **指标**：top-5 文档级命中率、页级命中率、MRR；负例看是否返回空表（决定 `generalKnowledgeOnly`）。
- **单测**：BM25 与融合逻辑离线跑（不联网、确定性）；embedding 相关用环境变量 gate，缺失则跳过。
- 数据进报告“RAG 的数据结构与检索”一节，也是演示时的证据。

### 5.1 冒烟结果与 BM25 / 混合对比（2026-10-30，218 块）

同一批查询分别跑纯 BM25 与混合（BM25 + Qwen3-Embedding + RRF），下表是逐条核对过页面内容后的结论：

| 查询 | 纯 BM25 top-1 | 混合 top-1 | 判断 |
| --- | --- | --- | --- |
| `what happens when an exception is not caught` | Lec-4 p.23（AI 开发） | Lec-3.1 p.3，且前三名全在异常课件 | **混合明显更好** |
| `how do I declare a class with fields and a constructor` | Lec-2 p.31（匿名类） | 仍是 p.31，但 p.2/p.3 变成 p.12（Point 类：字段 + 构造器）、p.6（Constructors） | **混合改善名次，没修好 top-1** |
| `how do I declare a generic type parameter` | 第 3 名跑到反射课件 | 前三名全在泛型课件 | 混合更聚焦 |
| `What does StringBuilder do and why is it faster?` | 第 2 名 Lec-5 p.17（无关） | 第 2 名 Lec-1 p.31（Strings） | 混合更好 |
| `What does @BeforeEach do?` | 只有 1 条命中 | 3 条命中，全在 Lec-3.4 | 混合覆盖更全 |
| `what is the difference between an interface and an abstract class` | 与混合完全一致 | 一致 | 无差别 |

两条必须记住的结论：

1. **我在 Day 2 预测“embedding 能修好 constructor 那条”，没有兑现。** 它只把第 2、3 名换成了更相关的页，top-1 依旧是匿名类。词面重叠强的短查询，BM25 依然主导，这是 RRF 融合的正常表现，不能当作 bug 藏起来。
2. **负例没有守住。** `how do I read a file line by line` 在全部课件里根本不存在（grep 确认无 `BufferedReader`/`readAllLines`/`FileReader`），但两路都返回了三条不相关页面，其中纯 BM25 的第一名是“Live Demo with TRAE”。这意味着**目前没有相关性闸门**：只要有一个词命中就会返回结果，agent 会拿无关页面当引用，作业要求里“检索不到就声明仅通用知识”那条实际上是失效的。

第 2 点是 Day 5（golden 集）必须解决的第一件事：给检索加一道门槛（例如命中词覆盖率和/或归一化分数下限），让“材料里没有”能真的返回空表。

## 6. 接线与线程

```
MentorJProjectService(Project)        ← 组装点，只接线
  ├─ RagKnowledgeBase(.idea/mentorj-index.json)
  ├─ RagDocumentLibrary(base, new DocumentProcessor())  → 界面组拿 DocumentLibrary
  ├─ KnowledgeSearchTool(base)                          → 注册进 ToolRegistry
  └─ AgentLoop(base, llm, prompts, tools)               → 界面组拿 AgentService
```

项目没有基础目录（默认项目 / light project）时索引路径为 `null`，知识库退化为纯内存，功能不变、重启才丢。

线程约定，两条都是硬的：

1. **构造函数不碰磁盘。** 项目级 service 在 EDT 上实例化（首次 `getService`），所以索引在首次
   `retrieve` / `statistics` 时才加载，且由 `synchronized` 保证只加载一次。
2. **导入走后台线程。** `importDocuments` 会花几十秒（将来还要调 embedding），界面组负责放后台并
   用 `ProgressListener` 回调进度；回调在导入线程上，更新 UI 要自己切回 EDT。

并发安全：写入（`index` / `clear` / `ensureLoaded`）互斥，读取用 volatile 快照，所以“后台导入 +
侧边栏同时提问”不会读到半个索引。

失败降级：写盘失败 → 本次会话内存可用，日志告警；索引文件损坏 → 按空索引处理，日志告警，用户重新
导入即可；单个文档解析失败 → 进 `ImportResult.failures()`，不影响其余文档。

## 7. 已知限制

- 扫描版 PDF（无文本层）会解析出空文本，目前作为失败项上报，不做 OCR。
- 中文提问检索英文课件召回显著偏低：不做翻译与改写（导入的材料语言未知，写死中→英是错误假设），此时如实降级为“仅通用知识”。
- 索引不随仓库走，评审者 clone 后需要自备课件导入一次。
- 没有增量索引：加一个文件也要全量重建，代价约 1.5 分钟。
