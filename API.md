# MentorJ module collaboration contract (API.md)

> This document is the interface agreement for the three teams working in parallel, so that collaboration between modules does not break. **Changing the contract means changing this file first, getting all three teams to agree, and only then changing code**; where the document and the implementation disagree, this file wins.

Team split:

| Module | Responsibility | Package / directory | Must not do |
| --- | --- | --- | --- |
| UI team | Sidebar tool window, editor context menu, the trigger entry points and text inputs of the three intelligent actions, context collection (selected code, staged diff), display of answers, citations and disclaimers | `edu.mentorj.ui`, `src/main/resources/messages/` | No direct LLM calls, no retrieval implementation |
| Agent core team | `AgentLoop` (the loop), the system prompt (with citation rules), tool invocation (`ToolRegistry` / `Tool`), the real `LlmService` implementation (LLM access), the generation prompts for unit tests and commit messages | `edu.mentorj.agent` | No Swing / PSI / EDT |
| RAG and other teams | Document processing (parse course material, chunk, keep page / scope), knowledge base and retrieval, the retrieval tool, CI, docs (including this file) | `edu.mentorj.rag`, CI configuration, `*.md` | No answer text generation |
| Shared contract | Interfaces and data types shared by the three modules; contract only, no implementation | `edu.mentorj.api` | Changes need all three teams to agree |
| Assembly | Wires the three modules together and exposes the single entry point | `edu.mentorj.MentorJProjectService` | Wiring only, no business logic |

The three modules **never import each other**; they collaborate entirely through the interfaces in `edu.mentorj.api`. The only place allowed to import a module's concrete implementation is the assembly point `MentorJProjectService`.

```
UI (ui) ──── AgentRequest / AgentResponse ────▶ agent core (agent)
                                                   │
                                        KnowledgeBase.retrieve / Tool
                                                   ▼
                          RAG (rag): DocumentProcessor → knowledge base → retrieval
```

## 1. Package layout

```
src/main/java/edu/mentorj/
├── api/                        shared contract (interfaces + data types); changes need all three teams
│   ├── AgentAction.java        enum of intelligent action types
│   ├── AgentRequest.java       one request (question / scenario + context)
│   ├── AgentResponse.java      one answer (body + citations + disclaimer flag)
│   ├── AgentService.java       agent entry point interface
│   ├── Citation.java           source citation (document + page/scope)
│   ├── DocumentLibrary.java    document import / clear / index statistics (called by UI, implemented by RAG)
│   ├── EditorContext.java      editor context (file, source, selected code)
│   ├── ImportResult.java       result of one import (indexed documents, chunk count, failure list)
│   ├── KnowledgeBase.java      knowledge base retrieval interface
│   ├── KnowledgeChunk.java     a chunk (content + source + page/scope + score)
│   ├── LlmService.java         LLM call interface (including message / tool call data types)
│   ├── ProgressListener.java   import progress callback (plain Java, no platform types)
│   ├── Statistics.java         current index state (document list, chunk count, index time)
│   ├── StreamListener.java     answer increment callback (plain Java, no platform types)
│   └── Tool.java               tool interface the agent can call
├── agent/                      agent core team: loop, system prompt, tool calls, LLM access
│   ├── AgentLoop.java          AgentService implementation: retrieval → prompt → LLM → tool loop
│   ├── SystemPromptBuilder.java system prompt (TA persona + citation rules + per-action instructions)
│   ├── ToolRegistry.java       tool registration and invocation
│   ├── ChatCompletionsLlmService.java  text generation and native tool call access
│   ├── LlmSettings.java        local environment variable configuration
│   └── LlmException.java       displayable configuration and call errors
├── rag/                        RAG team: document processing, knowledge base, retrieval tool, import service
│   ├── DocumentProcessor.java  course material parsing and chunking (keeps page/scope)
│   ├── KnowledgeSearchTool.java the retrieval tool (implements api.Tool, used by agent tool calls)
│   ├── RagDocumentLibrary.java DocumentLibrary implementation: import → chunk → index → write to disk
│   └── RagKnowledgeBase.java   KnowledgeBase implementation (BM25 + optional embedding hybrid retrieval)
├── ui/                         UI team: sidebar, context menu, intelligent action entry points
│   ├── AgentRequests.java      the single agent call path: background execution, stream merging, cancellation, result text
│   ├── AskCodeAction.java      editor context menu: ask about selected code
│   ├── ChatHistoryStore.java   sidebar history persistence (.idea/mentorj-chat.json)
│   ├── ChatMessage.java        display model of one question/answer or notice
│   ├── ChatStyles.java         theme colors, fonts and card HTML rules
│   ├── ChatTranscript.java     message card list: scrolling, stick to bottom, back to latest
│   ├── CodeHighlighter.java    highlights code blocks with the IDE color scheme
│   ├── CourseMaterialPanel.java          sidebar top: import / clear / index statistics
│   ├── EditorContexts.java     editor context collection
│   ├── GenerateCommitMessageAction.java  generate a Git commit message
│   ├── GeneratePracticeAction.java       generate practice exercises (the creative intelligent action)
│   ├── GenerateUnitTestAction.java       generate a unit test
│   ├── MarkdownRenderer.java   Markdown → HTML (tables, code blocks, escaping)
│   ├── MentorJChatPanel.java             sidebar conversation area: questions, message cards, selected code, history
│   ├── MentorJToolWindowFactory.java     sidebar tool window assembly
│   ├── MessageCard.java        one message card: user bubble / assistant card / error card
│   ├── ResultDialog.java       result dialog: starts streaming on open, stoppable, copyable
│   └── StagedDiff.java         collects git diff --cached as the staged changes
├── MentorJBundle.java          message bundle accessor
└── MentorJProjectService.java  assembly point: builds the knowledge base, tools, AgentService
```

## 2. Contract list (`edu.mentorj.api`)

### 2.1 Data types

| Type | Fields | Meaning |
| --- | --- | --- |
| `AgentAction` | `ASK`, `GENERATE_UNIT_TEST`, `GENERATE_COMMIT_MESSAGE`, `CREATIVE` | What a request does; `CREATIVE` is the creative intelligent action (generate practice exercises around the selected code) |
| `AgentRequest` | `action`, `userInput`, `editorContext`, `stagedDiff` | See "Field requirements per action" below |
| `AgentResponse` | `text`, `citations`, `generalKnowledgeOnly` | `text` is the answer body; `citations` are the sources of the chunks given to the LLM; `generalKnowledgeOnly` being `true` means no course material was retrieved |
| `Citation` | `document`, `pageOrScope` | One source citation: source document + page or range |
| `EditorContext` | `filePath`, `sourceCode`, `selectedCode` | Collected by the UI team; any field may be `null`, meaning not applicable / not provided |
| `ImportResult` | `documents`, `chunks`, `failures` | Result of one import: indexed document names, total chunk count, failure reasons (each shaped `file name: reason`) |
| `KnowledgeChunk` | `text`, `document`, `pageOrScope`, `score` | One chunk and its source; a larger `score` means more relevant |
| `ProgressListener` | `onProgress(done, total, message)` | Import progress callback; called on the import thread, and the UI team switches back to the EDT itself |
| `Statistics` | `documents`, `chunks`, `indexedAt` | Current index state; `indexedAt` is `null` before the first import |
| `StreamListener` | `onText(delta)`, `onToolCalls(toolNames)`, `isCancelled()` | Answer increment callback; runs on the caller's background thread, and the UI team switches back to the EDT itself |

Field requirements for `AgentRequest` per action:

| Action | `userInput` | `editorContext` | `stagedDiff` |
| --- | --- | --- | --- |
| `ASK` | the user's question (required) | optional; carries the selected code when asking from the context menu | unused |
| `GENERATE_UNIT_TEST` | the test scenario / requirement in natural language (required, from the text input) | required; `selectedCode` or `sourceCode` is the selected class / method | unused |
| `GENERATE_COMMIT_MESSAGE` | may be empty | unused | required; the staged changes (git diff format), collected by the UI team |
| `CREATIVE` | optional, the focus / difficulty / exercise type; when empty the selected code drives retrieval and the design | required; `selectedCode` or `sourceCode` is the subject of the exercises | unused |

### 2.2 Interfaces

```java
// Implemented by the agent core team, called by the UI team. Blocks synchronously, so the UI team must
// call it on a background thread and never on the EDT.
public interface AgentService {
    AgentResponse respond(AgentRequest request);
    // The variant with an increment callback; the default implementation degrades to one callback with
    // the whole body once generation finishes, so existing implementations need no change.
    default AgentResponse respond(AgentRequest request, StreamListener listener) {
        AgentResponse response = respond(request);
        if (!listener.isCancelled() && response.text() != null) {
            listener.onText(response.text());
        }
        return response;
    }
}

// Answer increment callback. Plain JDK types; the UI team switches back to the EDT to update the page.
public interface StreamListener {
    void onText(String delta);
    default void onToolCalls(List<String> toolNames) {}  // this round ended in tool calls, so earlier increments are not the final answer

    default boolean isCancelled() { return false; }      // polled before every increment and every tool execution
}

// Implemented by the RAG team, called by the agent core team. Returns chunks in descending score order,
// an empty list when nothing matches, never null.
public interface KnowledgeBase {
    List<KnowledgeChunk> retrieve(String query, int topK);

    // Chunks for a query the relevance gate rejects: unverified, never cited, and default-empty so an existing
    // implementation keeps compiling and keeps the old "no context" behaviour.
    default List<KnowledgeChunk> lowConfidenceContext(String query, int topK) { return List.of(); }
}

// Implemented by the RAG team, called by the UI team: import course material, clear the index, query its state.
// Blocking methods, so the UI team must call them on a background thread; may run concurrently with retrieve.
public interface DocumentLibrary {
    ImportResult importDocuments(List<Path> documents, ProgressListener progress);
    void clear();
    Statistics statistics();
}

// The real implementation (LLM access) comes from the agent core team; Request carries the system prompt,
// the messages and the available tools.
public interface LlmService {
    Result complete(Request request);
    // With a listener it streams as far as the server supports; the default implementation degrades to one
    // callback with the whole body once generation finishes.
    default Result complete(Request request, StreamListener listener) {
        Result result = complete(request);
        if (!listener.isCancelled() && result.toolCalls().isEmpty() && result.content() != null) {
            listener.onText(result.content());
        }
        return result;
    }
    // Nested data types: Message (role: system/user/assistant/tool), ToolSpec, ToolCall, Request, Result
}

// Any team can supply tool implementations; once registered in ToolRegistry the agent loop calls them.
public interface Tool {
    LlmService.ToolSpec spec();          // tool name, description, parameter JSON Schema
    String execute(String argumentsJson); // returns the text result fed back to the LLM
    // Wraps execute by default, so old implementations need no change; retrieval tools override it to give real sources.
    default ExecutionResult executeWithSources(String argumentsJson);
    record ExecutionResult(String text, List<Citation> citations) {}
}
```

`importDocuments` means a **full rebuild**: clear the existing index → parse each given document → chunk → build the index → write to disk. Only PDFs are supported; unsupported formats and parse failures all land in `failures`, without throwing and without affecting the other documents. `progress` may be `null`. The index is persisted in the project's `.idea/mentorj-index.json`, loaded automatically after an IDE restart, and does not require the original documents to still exist.

`DocumentLibrary` was added by the RAG team, and the UI team may depend on it only after all three teams agree.

Semantics of the streaming callbacks:

- Concatenating the `onText` increments in order equals the final `AgentResponse.text()` / `Result.content()`; an implementation may call back once with the whole text.
- `onToolCalls` means this round ended in tool calls: the increments received so far are not the final answer, so the UI should clear them and show the tool status.
  Tool names are given in call order, for display only, and are not part of the protocol.
- `isCancelled()` is polled at least before every increment and before every tool execution; once it returns `true` the implementation closes the connection as soon as possible,
  and the request ends with a displayable "cancelled" error instead of a half-finished answer.
- Callbacks happen on the caller's background thread; touching Swing inside a callback is forbidden.
- A streaming failure (connection drop, idle timeout, malformed data, filtered) produces the same displayable error as a one-shot request, without leaking the response body or the key.

### 2.3 Compatibility extensions on the local agent branch (for joint review)

This branch implements the following additions for the agent completion task while keeping the old constructors and tool methods; all three teams still need to review before the shared contract is merged.

- `Message(role, content, toolCalls, toolCallId, reasoningContent)`: an assistant message keeps the call list and the
  `reasoning_content` the service returned exactly as given; a tool message maps back to its call via `toolCallId`. Ordinary messages keep using the two-argument constructor.
  Use `Message.assistant(Result)` and `Message.tool(callId, text)` to fill in the complete protocol data.
  The old `Message.tool(text)` stays for source compatibility, but it lacks an ID and a real adapter refuses to send it.
- `Result(content, toolCalls, reasoningContent)`: the two-argument constructor is kept; a result without tool calls must have a non-empty body.
  `reasoningContent` is only for protocol round trips and is never shown to the user.
- `Request(systemPrompt, messages, tools, thinking)`: the three-argument constructor is kept and allows reasoning. `thinking = false` asks the service to turn the
  model's reasoning pass off for that call; the client sends it as `chat_template_kwargs: {"thinking": false}`, the only form the Tongji gateway honours, and only
  when the flag is false, so a stricter gateway never receives the field. The agent core sets it for the three smart actions and leaves it on for `ASK`.
- Reasoning text is read from `reasoning_content` or, when a gateway names the field differently, from a string `reasoning` delta. Either one fills
  `Result.reasoningContent`; a non-string `reasoning` value is ignored rather than failing the call, because that field has no contract.
- `Request`, `Message`, `Result` and `ExecutionResult` take immutable copies of their lists, and the lists may not be null.
- `Tool.executeWithSources` returns the body plus structured sources; an old tool that returns only a string keeps working but produces no citations.
  Each tool validates its own parameter fields. Output for a failure or an empty result carries no sources.
- When `retrieve` returns nothing, the agent core team may fetch one chunk with `KnowledgeBase.lowConfidenceContext` and pass it to the model as **unverified** context: the prompt forbids citing or naming it, it never reaches `citations`, and `generalKnowledgeOnly` stays `true`. The UI team sees no difference, and the retrieval tool marks such a chunk the same way in its text.
- The final `AgentResponse.citations` merges the sources given to the model from the initial retrieval and from successful tool retrievals, deduplicated in order of appearance.
  Sources are never extracted from text the model generated, and documents or page numbers are never invented. An error answer carries no course citations and no general knowledge notice.
- When a text answer is truncated by the length limit but a body already exists, `text` keeps the body and prefixes an "Answer incomplete" notice, still with the sources already provided.
  With no body, or with a truncated tool call, it returns an error message and does not execute that round's tools; the shared data types do not change.
- The agent keeps context per request, runs at most 3 tool rounds with at most 8 calls each and at most 4 model requests.
  The last round offers no tools. Commit message generation uses only the staged diff and the user's requirements, and retrieves no course material.
  User context is limited to 60,000 characters, the accumulated conversation to 120,000, and a single tool output to 20,000; exceeding a limit is an explicit error.
  These are this plugin's resource limits and not the provider's token limits.

## 3. Key data flows

0. **Importing course material**: the UI team picks files → `DocumentLibrary.importDocuments(paths, progress)` (background thread) → the RAG team parses the PDFs, chunks by page, builds the index, writes `.idea/mentorj-index.json` → returns `ImportResult` → the UI team shows the indexed document count, the chunk count and the failure list.
1. **Sidebar Q&A**: the UI team collects the question → `AgentService.respond(ASK, listener)` → the agent core team retrieves chunks with `KnowledgeBase.retrieve`, falling back to one unverified `lowConfidenceContext` chunk when the relevance gate rejects the question → assembles the system prompt (with citation rules) and the context → `LlmService` (running the tool loop when needed) → `AgentResponse` → the UI team shows the body plus citations / disclaimer.
2. **Asking about selected code from the context menu**: the UI team collects an `EditorContext` with `EditorContexts` → same as 1 (`ASK`).
3. **Unit test generation**: the UI team shows a text input to collect the natural-language scenario → `respond(GENERATE_UNIT_TEST)` → the agent core team generates a JUnit test class from the scenario and the context code → the UI team shows it.
4. **Commit message generation**: the UI team collects the staged diff → `respond(GENERATE_COMMIT_MESSAGE)` → the agent core team analyzes the intent and writes a Conventional Commits message → the UI team shows it.
5. **Practice exercise generation (the creative action)**: the UI team shows a text input that may stay empty to collect the focus / difficulty → `respond(CREATIVE)` → the agent core team uses the selected code or the user input as the retrieval query, lists the concepts from the course material and writes 3 exercises with reference answers → the UI team shows them.

Division of labor for the intelligent actions: the **UI team** owns the trigger entry points, the text inputs, context collection and result display; the **agent core team** owns the prompts and the generation calls; the **RAG and other teams** do not take part in the actions themselves.

## 4. Global rules (mandatory when implementing)

- **Unverified material**: a chunk that did not pass the relevance gate may inform an answer but must never become a citation, a named page, or evidence for the disclaimer. Only `retrieve` output is citable.
- **Citations and disclaimer**: when course material was retrieved, `citations` must give `document` + `pageOrScope`; when nothing was retrieved, `generalKnowledgeOnly` must be `true` and the UI team must state explicitly that the answer is based on general knowledge only, without cited course material (for example, "Response is based on general knowledge; no specific course material is referenced"). The system prompt constrains the LLM's sourcing in the same way.
- **Threading**: `AgentService.respond` may block for a long time, so the UI team always runs it on a background thread and returns to the EDT only to update the UI; `StreamListener` callbacks arrive on that same background thread, and the UI team merges them and switches back to the EDT itself.
- **Nulls**: in the contract `null` only means "not applicable / not provided"; a `List` is always returned empty instead of `null`.
- **No index**: before any course material is imported, `retrieve` returns an empty list, `AgentResponse.generalKnowledgeOnly` is `true`, and the UI team shows the general knowledge notice.
- **Text**: all UI text goes into `src/main/resources/messages/MentorJBundle.properties`, retrieved via `MentorJBundle.message(key, ...)`, never hardcoded.
- **Registration**: extension points, actions and services are registered in `plugin.xml` (or with platform annotations), with a comment on each registration saying what it is for.
- **Contract change process**: change this file first → all three teams agree → then change the code → keep the document and the implementation consistent in the same commit or the one immediately after.

## 5. Joint integration checklist

- [ ] `./gradlew build` passes (add `./gradlew verifyPlugin` when plugin.xml or dependencies changed)
- [ ] `./gradlew runIde`: the MentorJ tool window appears in the sidebar; the editor context menu has the three intelligent action entries (ask about selected code / generate unit test / generate practice exercises)
- [ ] Question flow: with a question and retrieval results, citations are shown (document + page/scope); without retrieval results, the general knowledge notice is shown
- [ ] Streaming: concatenated increments equal the final answer; a tool round clears the earlier text and shows tool status; cancelling stops within 1 second and keeps what was received
- [ ] Import flow: importing decks returns statistics; after an IDE restart, citations with page numbers are still retrievable; importing a non-PDF file shows the failure reason instead of failing silently
- [ ] The request fields of the three intelligent actions match "Field requirements per action"

## 6. Open items

- Creative intelligent action (`CREATIVE`): settled as "generate practice exercises around the selected code". It first lists the course concepts the code involves,
  then writes 3 exercises of increasing difficulty with reference answers; `userInput` may be empty, and when it is, the selected code (or the whole file) is the retrieval query.
  The UI entry point is the "Generate Practice Exercises" editor context menu item.
- LLM service provider: the Tongji AI service platform, with the default Base URL `https://agi.tongji.edu.cn/v1`
  and the model `DeepSeek-V4.1-Flash`. The API key comes from a local environment variable; the URL and model can be overridden, see the README.
- Native tool calling: the local adapter and loop implement a compatible protocol and are verified with a mock HTTP service;
  whether the Tongji gateway supports tools for the chosen model still needs a joint test with a real key, and a text example cannot settle it.
- Streaming output: wired through `StreamListener`, and both text increments and streaming tool calls are verified against a local mock service;
  the Tongji gateway's `stream: true` compatibility still needs a joint test with a real key, and `MENTORJ_LLM_STREAM=false` is the fallback when it fails.
- Retrieval approach (settled): BM25 lexical retrieval plus embedding semantic retrieval, with the two rankings fused by RRF and a relevance gate that returns an empty list when the corpus does not cover the question; embeddings come from Tongji AGI's `Qwen3-Embedding` (OpenAI-compatible `/v1/embeddings`, 512 dimensions) with the key read from the `MENTORJ_LLM_API_KEY` environment variable. When embeddings are unavailable it degrades to pure BM25 automatically and citations stay valid.
