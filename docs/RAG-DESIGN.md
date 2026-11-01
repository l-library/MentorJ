# MentorJ RAG design

This document records the RAG team's design decisions and measured data, shared by implementation, review and report writing. The contract lives in [API.md](../API.md); this file covers only how the implementation lands and why the choices were made.

## 1. Corpus and boundaries

| Item | Value |
| --- | --- |
| Corpus | 10 PDFs under `assets` (Lec-0 to Lec-5 plus the assignment brief) |
| Size | 203 pages, about 127k characters (roughly 30k tokens) |
| Text layer | All are PDFs with extractable text, and `pdftotext` / PDFBox output is clean; the page number printed in the footer matches the PDF page order |
| Supported formats | **PDF only**. Other formats land in `ImportResult.failures` and are never dropped silently |

The corpus size drives the design: the whole knowledge base fits in one context, so retrieval's value is not compressing information but being **explainable, citable and evaluable**. There is no vector database; the index is a single JSON file.

> Why no vector database: the data is small and single-user, and a brute-force scan takes far less time than vectorizing the query over the network. A vector database would add more complex dependencies without any performance gain.

## 2. Processing pipeline

```
PDF → extract text page by page → clean footers/headers → chunk by page (split overlong pages again)
    → tokenize (BM25 inverted index) → embed (degradable) → write .idea/mentorj-index.json
```

### 2.1 Parsing

- Dependencies: `org.apache.tika:tika-core` + `org.apache.tika:tika-parser-pdf-module` (Tika 4.1.0). Why only the PDF module: the full `tika-parsers-standard-package` pulls in dozens of transitive dependencies with a high risk of clashing with libraries the IntelliJ platform already ships (jackson, commons-*), while this project only consumes PDFs.
- **Split of work (revised after measuring)**: Tika detects the media type (a non-PDF fails immediately with a clear reason); text extraction goes page by page through PDFBox. The reason: Tika 4's `PDFParser` emits one plain-text run without page boundaries (what `BodyContentHandler` receives is the concatenation), so "page N" is unavailable, and the page number is the entire premise of a citation. PDFBox already comes in as a dependency of Tika itself (the same engine), so no new dependency surface is added.
- The page number is the PDF page order (matching the slide footer, so a reviewer can flip pages and check).
- Committed test data: `../src/test/resources/documents/two-pages.pdf` (two pages of known text produced by Ghostscript), which `PdfExtractionTest` asserts for detection, page count and per-page text never bleeding across pages.

### 2.2 Chunking specification

- **One chunk per page** is the default granularity: citations are precise to the page by construction, with no need to guess section boundaries inside a page.
- A page whose text exceeds **800 characters** is split again inside the page: lines accumulate up to the limit; a single line longer than the limit is cut hard by character count. Adjacent chunks carry the **last line** of the previous chunk into the next one (when that line is at most 120 characters), so sentences are not cut apart.
- `pageOrScope` shape: `p.12`. Because chunking respects page boundaries, a chunk never spans pages, so no page range appears; `p.12-13` is reserved for a future "merge small pages" change.
- Page number cleaning: only a number equal to the current page number is removed, in three layouts, all of which occur in the real decks:

  | Layout | Example | Handling |
  | --- | --- | --- |
  | Alone on a line | `3` | Remove the whole line |
  | Glued to the front of a heading | `2Overview` | Remove the number, keep `Overview` |
  | After a footer | `Tools and Scenarios 6` | Remove the number, keep the text |

  List numbers in the body are left alone: the step numbers `1`-`6` in the Lec-4 page 12 table differ from the page number and are kept as they are.
- **Repeated whole lines are not removed** (running headers / section footers). It seemed reasonable to delete lines that appear on most pages, but Lec-3.4 has 4 repeated `}` lines (code blocks) and frequency-based deletion would corrupt the code; a footer like `Call Structure Context Tools Loop Verify` repeats on 26 of 44 pages and has limited effect on BM25 (high document frequency means low idf naturally). The gain does not cover the risk, so it is not done.
- Cleaning and chunking are pure string functions, testable without the decks (`DocumentProcessorTest`).
- Full measurement (the 10 PDFs under `assets`): **203 pages → 218 chunks**, longest chunk 794 characters (under 800), zero page number residue.
- Known loss: the PDF text layer does not preserve code indentation, so code samples come out flattened; retrieval still works, but a chunk cannot be used as compilable code.

### 2.3 Tokenization

Used by BM25, purely local and dependency-free (`Tokenizer`):

- Lowercase, split on non-alphanumerics (`_`, `.`, `()` are boundaries by nature, so `snake_case` splits automatically);
- Split camelCase into subwords (`getUserId` → `get`, `user`, `id`), so Java terms do not fail to match as whole words;
- **Keep the whole word as well as the parts** (`getUserId` also indexes `getuserid`). Without this, `@BeforeEach` splits into `before` and `each`, both stopwords, and the whole term is lost — in practice this took "What does @BeforeEach do?" from zero hits to a correct hit on Lec-3.4 p.4;
- Drop English stopwords (about 90) and single-character Latin words;
- Index CJK characters as unigrams plus adjacent bigrams, so a Chinese query never misses everything just because tokenization cannot cut it.

Stopwords apply to Latin words only; code identifiers and Chinese are unaffected.

## 3. Index structure

`<project>/.idea/mentorj-index.json`, UTF-8, with `version` so a future upgrade can discard old indexes:

```json
{
  "version": 1,
  "generatedAt": "2026-10-30T09:12:00Z",
  "chunks": [
    { "document": "Lec-3.4-JUnit.pdf", "pageOrScope": "p.2", "text": "…" }
  ]
}
```

- **Only chunks are stored**: the inverted index, the document list and the page counts are all derived from `chunks`, not persisted. The whole course material is only a bit over two hundred chunks, so rebuilding the inverted index at startup takes milliseconds; persisting it would mean maintaining a copy that can drift out of step with the chunks.
- The full 218 chunks measure **115 KB**.
- Vectors (512 dimensions) join as a `chunks[].vector` field and `version` moves to 2. Vectors are stored at 512 dimensions (the `dimensions` parameter does MRL truncation): 2560 dimensions would make the file an order of magnitude larger with almost no effect on retrieval quality across a few hundred chunks.
- The index lives per project: another project means another knowledge base; `../.idea` is already ignored, so it never ships with the repo.
- Invalidation: `importDocuments` is a full rebuild with no incremental update and no automatic rescan. Startup only loads the JSON and does not check whether the original files still exist (the user may have changed machines); re-import when an update is needed. A mismatched `version` discards the old index instead of migrating it.

## 4. Retrieval

Hybrid retrieval that fuses two rankings (`RagKnowledgeBase`):

1. **BM25** (`k1=1.2`, `b=0.75`) over the chunk text with a pure in-memory inverted index; chunks scoring 0 are not returned, since the caller would otherwise cite irrelevant material.
2. **Embedding** cosine similarity (vectors are normalized, so this equals the dot product).
3. **RRF fusion**: `Σ 1 / (60 + rank_i)`, taking the top 20 from each ranking into the fusion and returning topK overall.
4. **Degradation**: with no `MENTORJ_LLM_API_KEY`, on a network failure, or with no vectors in the index, BM25 only.

Ties break on chunk index, which keeps the output stable and testable.

### 4.1 Relevance gate

Without a gate, a single matching term returns results, so content the slides never cover still picks up a fake citation — while the assignment explicitly requires stating "general knowledge only" when retrieval finds nothing. The gate has to answer: **do these decks actually cover this question?**

**Why not an absolute cosine threshold.** The first attempt gated on "cosine ≥ 0.50", and in practice a wording change alone pushed queries across it: `How do I read a file line by line?` scores 0.545 (passes) while the all-lowercase `how do I read a file line by line` scores only 0.423 (rejected). Five wordings of the same question sit only 0.91-0.98 apart, which is as large as the gap to the threshold, so an absolute threshold is unreliable.

**The final rules** (measured over 218 chunks and 18 queries):

| Signal | Purpose | Threshold |
| --- | --- | --- |
| `max(cosine) − median(cosine)` | whether one chunk is clearly on point | ≥ 0.30 |
| `max(cosine)` | settles it when everything is relevant | ≥ 0.60 |
| single chunk cosine | trims off-topic chunks ranked below the top one out of the citations | ≥ 0.45 |
| query term coverage (fallback with no vectors) | lexical backstop | ≥ 0.50 |

The two query-level signals combine with **OR**: either one holding means the corpus covers the question; when neither holds the result is empty and the agent sets `generalKnowledgeOnly=true`.

Measured distribution: true positives have a `max − median` floor of **0.319** and negatives peak at **0.280**, with no overlap; for `max`, true positives bottom out at 0.589 and negatives peak at 0.545.

After changing the embedding model or the corpus, all four numbers must be measured again before touching the thresholds; they cannot be reused as they are.

### 4.2 What score means

`KnowledgeChunk.score` is the RRF fusion score normalized into (0, 1]: a chunk ranked first by every path scores 1.0.

**A known limitation the UI team must be told about**: with only one path (currently only BM25) the normalized score reflects rank only, not match strength — the top-3 come out as nearly indistinguishable values like 1.000 / 0.984 / 0.968. So the UI **must not show score as a percentage**; it can only order results or label "rank N". Real separation arrives once both paths are in (Day 4): a chunk near the top of both paths approaches 1.0, and a chunk on one path only drops to around 0.5.

Why the raw BM25 score is not used as the score: it has no upper bound (it may be 0.5 or 18), so the UI could not display it and it could not be compared with a semantic score.

### 4.3 Measured data (2026-10-28, Tongji AGI platform)

| Item | Measured |
| --- | --- |
| Base URL | `https://agi.tongji.edu.cn/v1` (OpenAI-compatible) |
| Model | `Qwen3-Embedding` (the platform also offers `Qwen3-Reranker`; measured and rejected, see §5.3) |
| Dimensions | 2560 by default; passing `dimensions` lowers it to 512 |
| Normalization | Returned vectors are already L2-normalized (magnitude measured at 1.0), so cosine similarity equals the dot product |
| Concurrency limit | The plan allows 2 concurrent requests; beyond that returns HTTP 429, with the platform's own message meaning "concurrent request limit reached" |
| Batch size | 8 inputs per request. 16 inputs take about 42 seconds and 32 return nothing after 180 seconds (measured at 2560 dimensions), so 8 is fixed |
| Full import (218 chunks) | **2.3 seconds** (512 dimensions, batch 8, 2 concurrent); the real time to import the 10 decks is whatever is measured under `docs` |
| Vectorizing one query | **30-55 milliseconds** (512 dimensions, single input) |

So the import implementation is: batch size 8, at most 2 concurrent requests (a semaphore, since exceeding it really does return 429), a 60 second per-request timeout, and backoff retries twice on 429/5xx. The question path is separate: **a 5 second timeout**, and on failure this session falls back to pure BM25 with a log entry instead of waiting for a timeout on every question.

An early measurement at 2560 dimensions gave "5-7 seconds for 8 inputs", very different from the later 2.3 seconds per 218 chunks at 512 dimensions: truncating dimensions lowers storage and latency at the same time, which is the second reason for choosing 512.

## 5. Evaluation

- **Golden set**: about 25 English questions, each annotated with the expected document plus page, covering each deck and negatives that the decks genuinely do not cover.
- **Metrics**: document-level top-5 hit rate, page-level hit rate, MRR; negatives are checked for an empty result (which decides `generalKnowledgeOnly`).
- **Unit tests**: BM25 and the fusion logic run offline (no network, deterministic); embedding-related tests are gated by environment variables and skipped when absent.
- The data feeds the "RAG data structures and retrieval" section of the report and is evidence during the demo.

### 5.1 Golden set results (2026-10-30, 218 chunks)

`src/test/resources/golden/course-questions.tsv`: 15 positives (annotated with the expected document plus page, each checked page by page) and 4 negatives (genuinely absent from the decks). `GoldenSetTest` runs on a machine with a key configured, and CI skips it.

| Configuration | Page-level top-5 hits | Negatives leaked |
| --- | --- | --- |
| Absolute cosine threshold 0.50 only | comparable to the row below | **2 of 4 leaked** |
| Pure BM25 plus coverage gate (the no-key shape) | 12/15 = 0.80 | 1 of 4 leaked |
| **BM25 + semantics + relative gate (the shipped shape)** | **14/15 = 0.93** (MRR 0.81) | **0 leaked** |

The only miss is a **false rejection**: `How do I write a lambda expression for a comparator?` returns an empty result, while Lec-3.3 p.2 is exactly "Lambda Expressions". Its `max` is 0.576 and its `max−median` is 0.245, both just short; but recovering it would also admit `How do I read a file line by line?` (0.550 / 0.277) and `Angular vs React` (0.511 / 0.198), which are numerically indistinguishable from it. **The choice here is precision over recall**: better to answer "general knowledge only" for this one than to cite the wrong page.

To get both, the right move would be a reranker layer (the platform offers `Qwen3-Reranker`): take the top-10 candidates first, then score them with a cross-encoder. **Measured on 2026-11-05 and rejected, see §5.3.**

### 5.2 Smoke results and BM25 / hybrid comparison (2026-10-30, 218 chunks)

The same queries were run through pure BM25 and through the hybrid (BM25 + Qwen3-Embedding + RRF); the table gives the conclusion after checking the page content of each hit by hand:

| Query | Pure BM25 top-1 | Hybrid top-1 | Verdict |
| --- | --- | --- | --- |
| `what happens when an exception is not caught` | Lec-4 p.23 (AI development) | Lec-3.1 p.3, and the top three are all in the exceptions deck | **Hybrid clearly better** |
| `how do I declare a class with fields and a constructor` | Lec-2 p.31 (anonymous classes) | still p.31, but p.2/p.3 became p.12 (the Point class: fields + constructor) and p.6 (Constructors) | **Hybrid improved the ranks but did not fix top-1** |
| `how do I declare a generic type parameter` | rank 3 landed in the reflection deck | the top three are all in the generics deck | Hybrid more focused |
| `What does StringBuilder do and why is it faster?` | rank 2 was Lec-5 p.17 (irrelevant) | rank 2 was Lec-1 p.31 (Strings) | Hybrid better |
| `What does @BeforeEach do?` | only 1 hit | 3 hits, all in Lec-3.4 | Hybrid covers more |
| `what is the difference between an interface and an abstract class` | identical to hybrid | identical | No difference |

Two conclusions to remember:

1. **My Day 2 prediction that "embeddings will fix the constructor query" did not come true.** It only swapped ranks 2 and 3 for more relevant pages, and top-1 is still anonymous classes. For short queries with strong lexical overlap BM25 still dominates, which is normal RRF behavior and must not be hidden as a bug.
2. **The negatives were not held.** `how do I read a file line by line` does not exist anywhere in the decks (grep confirms no `BufferedReader`/`readAllLines`/`FileReader`), yet both paths returned three irrelevant pages, with pure BM25 ranking "Live Demo with TRAE" first. That means **there was no relevance gate at that point**: a single matching term returns results, the agent cites irrelevant pages, and the assignment's "state general knowledge only when retrieval finds nothing" requirement was effectively broken.

That second point was the first thing to fix on Day 5 (the golden set): add a threshold to retrieval (term coverage and/or a normalized score floor) so that "not in the material" really returns an empty result.

### 5.3 Reranker evaluation (2026-11-05, 218 chunks)

The §5.1 miss is a false rejection, and a cross-encoder layer was the recorded way to recover it. `RerankEvaluationTest` measures exactly that, offline: it takes the pre-gate candidate pool (`RagKnowledgeBase.candidates`, top 10 from both paths, gate bypassed) and reranks it with the platform's `Qwen3-Reranker` over `POST /v1/rerank`. The gate has to be bypassed for the question to be answerable at all — a reranker sitting behind it could only reorder what the gate already let through, so it could never recover the miss.

| Configuration | Page-level top-5 | MRR | Threshold gate |
| --- | --- | --- | --- |
| Baseline (shipped gate) | 0.93 (14/15) | 0.81 | negatives: 0 leaked |
| Reranked, gate bypassed | **0.33 (5/15)** | 0.19 | **impossible**: lowest covered top score 0.415, highest uncovered top score 0.769 |

Two findings:

1. **The platform's `/v1/rerank` does not return a relevance score.** The evaluation opens with a sanity check that has no ambiguity in it: given a query, its own text, and two unrelated sentences, the exact match must rank first. It ranked **3 of 3**, below "Bananas are yellow and rich in potassium." The score is dominated by the document alone — across three different queries the same four candidates came out `[1, 2, 3, 0]`, `[1, 2, 3, 0]`, `[2, 1, 3, 0]`, with the document that answers the query last every time — and it falls as a document grows: the query's own text scored 0.687 alone, 0.566 with a line of junk appended, while pure junk scored 0.581. Reranking through it makes retrieval worse, not better (0.93 → 0.33), and no threshold can separate covered from uncovered questions because the scores are inverted. **Do not wire it in**; re-check only if the platform replaces the endpoint, and the sanity check in `RerankEvaluationTest` is what will say so.
2. **The one miss does not need a reranker.** For `How do I write a lambda expression for a comparator?` the expected page (Lec-3.3 p.2) sits at **rank 1 of the pre-gate fused pool** — the gate alone discards it. So the remaining lever is the gate's precision/recall trade-off, not the ranking order: the fused ranking already had the answer first, and only the cosine threshold threw it away. That points at the cheaper alternative — hand the top fused chunk to the LLM as low-confidence context without a citation instead of returning nothing — which needs no new dependency and no extra round trip.

The test is skipped without the decks and the API key, like `GoldenSetTest`. It prints the measurement instead of asserting a hit-rate floor, because a floor would only encode today's corpus; what it does assert is the measurement's own validity condition — the candidate pool must contain the expected page for every covered question (it does, 15/15), otherwise the reranked hit rate would be describing a pool that never had the answer in it. One call per question at the top-10 depth measured 90-500 ms, which the report prints as its `slowest rerank call` line.

## 6. Wiring and threads

```
MentorJProjectService(Project)        ← the assembly point, wiring only
  ├─ RagKnowledgeBase(.idea/mentorj-index.json)
  ├─ RagDocumentLibrary(base, new DocumentProcessor())  → the UI team gets DocumentLibrary
  ├─ KnowledgeSearchTool(base)                          → registered into ToolRegistry
  └─ AgentLoop(base, llm, prompts, tools)               → the UI team gets AgentService
```

When the project has no base directory (the default project / light project) the index path is `null`, the knowledge base degrades to in-memory only, behavior is unchanged, and it is lost only on restart.

Threading rules, both of them hard:

1. **The constructor does not touch the disk.** The project-level service is created on the EDT (the first `getService`), so the index loads on the first
   `retrieve` / `statistics`, and `synchronized` guarantees it loads once.
2. **Import runs on a background thread.** `importDocuments` takes tens of seconds (and will also call the embedding service), so the UI team runs it in the background and
   receives progress through `ProgressListener`; the callback happens on the import thread, and updating the UI means switching back to the EDT yourself.

Concurrency safety: writes (`index` / `clear` / `ensureLoaded`) are mutually exclusive and reads use volatile snapshots, so "import in the background + a sidebar question at the same time" never reads half an index.

Failure degradation: a failed disk write → the index stays usable in memory for this session with a log warning; a corrupt index file → treated as an empty index with a log warning, and the user simply re-imports; a single document failing to parse → recorded in `ImportResult.failures()` without affecting the other documents.

### 6.1 Dependencies and sandbox verification (2026-10-29)

The plugin ends up carrying only **10 jars / 5.4 MB**. Four groups were excluded from the transitive dependencies of `tika-parser-pdf-module`:

| Excluded dependency | Why it can go | Effect |
| --- | --- | --- |
| BouncyCastle (bcprov/bcpkix/bcutil/bcjmail) | only used to verify signed PDFs | duplicates **5700+** classes with the platform, so removing it saves about 9 MB |
| `pdfbox-tools`, `picocli` | used by the Tika command line tool | the extraction path never touches them |
| `commonmark` | used by Tika to parse Markdown | this plugin does not parse Markdown |

The exclusions were **measured**, not guessed from documentation: all 10 PDFs under `assets/` were run through a classpath without those jars, and the result was still 218 chunks with correct page numbers.

Three duplicates must stay (Tika/PDFBox need them at runtime): `slf4j-api` (54 of 56 classes duplicate the platform), `commons-io` (370 of 429), `commons-logging` (18 of 29). The plugin class loader prefers the versions in its own jars, and these libraries have stable versions with backward-compatible interfaces, so the risk is acceptable.

Verification results:

| Check | Result |
| --- | --- |
| `./gradlew verifyPlugin` (IU-253.33813.55) | **Compatible**, no issues |
| Sandbox `runIde` load | `Loaded custom plugins: MentorJ (1.0.0-SNAPSHOT)`, **0 ERROR** entries in the log, no `NoClassDefFoundError` / `LinkageError` |
| Parsing the decks inside the sandbox with the plugin's own 10 jars | 10 PDFs → 218 chunks |

**Not verified**: opening the tool window in the sandbox and the real import flow. The import entry point belongs to the UI team and did not exist yet; also, on this machine GUI automation (xdotool) sends synthesized keystrokes to the user's own running IDE, so it was not worth continuing. Once the UI team ships the entry point, click through it in the sandbox before the demo: import `assets/` → ask a question → check the cited page numbers.

## 7. Known limitations

- A scanned PDF (no text layer) parses to empty text and is currently reported as a failure; there is no OCR.
- A Chinese question against English decks retrieves noticeably less: there is no translation or rewriting (the imported material's language is unknown, so hardcoding Chinese-to-English would be a wrong premise), and in that case the answer honestly degrades to "general knowledge only".
- The index does not ship with the repo, so a reviewer who clones still has to import the decks once.
- There is no incremental indexing: adding one file means a full rebuild (measured at 2.3 seconds, no longer a burden).
- **The gate falsely rejects**: a query around a 0.55 cosine with a 0.25 gap is judged as "the corpus does not cover this", as with the lambda/comparator query. What moves up and down is the precision/recall trade-off, not a bug.
- **No reranker**: the platform's `/v1/rerank` was measured on 2026-11-05 and its scores are not relevance-correlated, so the layer is deliberately absent (§5.3).
- **Without an API key the negatives are not held**: with the coverage gate alone, `how do I read a file line by line` reaches coverage 1.0 because read/file/line all appear, and still returns irrelevant pages.
- The relevance thresholds are corpus-dependent empirical values; changing the corpus or the embedding model requires measuring them again (see §4.1). The thresholds also **depend on corpus size**: the no-key coverage gate was tuned against the real decks of over two hundred chunks, and on the 2-page test fixture it judges a query that hits only 1/3 of its terms as "not covered".
