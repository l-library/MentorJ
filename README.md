# MentorJ

> An IntelliJ IDEA plugin that serves as a teaching assistant (TA) of the Java Enterprise Application Development course.

MentorJ is a teaching assistant plugin for the Java Enterprise Application Development course. It runs inside IntelliJ IDEA and is built for university students learning Java. It lives in the IDE as a sidebar AI agent that answers course questions and helps with programming. The project is still in early development.

## Features

- The conversation UI is embedded in IntelliJ IDEA (sidebar / tool window): the sidebar AI agent plays a "teaching assistant", talking to learners as a course-specific Q&A chatbot.
  Answers appear as message cards and stream in character by character, with Markdown, highlighted code blocks, and copy/insert actions, plus stop and regenerate. Selected code can be attached to a question, and the history is restored per project.
- RAG course Q&A with cited sources:
  - Course material (slides, for example) is preprocessed into retrievable chunks and indexed.
  - When a user asks a programming-related question, the most relevant chunks are retrieved and sent to the LLM service together with the original question, so the answer combines general Java knowledge with concrete, verifiable information from the course material.
  - An answer built on retrieved material must cite the source document and page/range. When no relevant material was retrieved and the answer can only rest on the LLM's general knowledge, the answer must say so explicitly (for example, "Response is based on general knowledge; no specific course material is referenced").
- Context awareness: it reads the user's current programming context; after selecting a code fragment, a context-menu action asks about that code directly.
- Three intelligent actions:
  - Generate a unit test from a requirement: for the selected Java class or method, a text input first asks the user to describe the scenario or requirement in natural language (such as "test that the user ID does not exist"), then the context source is sent to the LLM to generate a JUnit test class for that scenario.
  - Generate a Git commit message: the staged changes of all files (similar to git diff) are read and handed to the LLM as context, which analyzes what the change means to do (bug fix, new feature, refactor, and so on) and produces a structured commit message following Conventional Commits, shown to the user.
  - Creative intelligent action (generate practice exercises): list the course concepts the selected code involves, then write 3 exercises of increasing difficulty with reference answers, for self-testing after class. The input box may stay empty, in which case the assistant designs the exercise types and difficulty from the code; requirements such as "only bug-finding exercises" or "harder difficulty" are also accepted.

## Development

The build targets IntelliJ IDEA 2025.3.6.1 and needs JDK 21 (the Gradle toolchain downloads it, so no manual install). Common commands:

```bash
./gradlew build   # compile and package the plugin
./gradlew runIde  # start a sandbox IDE with this plugin loaded
./gradlew check   # run the tests
```

## LLM access (Tongji AI service platform)

The plugin streams generations through the Tongji platform's Chat Completions API, and the agent can call the course retrieval tool while generating.
The default URL and model are set from the platform's example, so only `MENTORJ_LLM_API_KEY` is needed before running.

| Variable | Meaning |
| --- | --- |
| `MENTORJ_LLM_API_KEY` | Required; the API key issued by the Tongji platform |
| `MENTORJ_LLM_BASE_URL` | Optional; defaults to `https://agi.tongji.edu.cn/v1` |
| `MENTORJ_LLM_MODEL` | Optional; defaults to `DeepSeek-V4.1-Flash` |
| `MENTORJ_LLM_TIMEOUT_SECONDS` | Optional; request timeout or streaming idle timeout in seconds, default 90, range 1-600 |
| `MENTORJ_LLM_MAX_TOKENS` | Optional; output limit per response, default 8192; the plugin accepts 1-32768 and the value must satisfy the selected model's limit |
| `MENTORJ_LLM_STREAM` | Optional; `true` (default) streams the response, `false` falls back to a single response; use it when a gateway cannot handle streaming |

The default request URL is `https://agi.tongji.edu.cn/v1/chat/completions`, authenticated with
`Authorization: Bearer <API key>`. Requests use `max_tokens: 8192`,
`temperature: 0.7`, `stream: true`, and parse `delta.content` and fragmented streaming tool calls
from SSE; when the server ignores `stream` and returns one JSON body, parsing falls back automatically.
Streaming requests set no overall timeout but use an idle watchdog that fires when no new data arrives,
so long answers are not cut off.
The output budget is raised from the 1000 in the platform example to 8192,
which leaves room for course explanations and code samples; it is a maximum, not a target the model must use.
If an answer is still truncated, the text already received is kept and prefixed with an explicit
"Answer incomplete" notice; generated code may be incomplete.
Reasoning-only output reports the current budget and never shows the reasoning; truncated tool calls are not executed.
The base URL and model can be overridden with the environment variables above; unset means the default, while an explicitly empty value reports an error.
The code appends `/chat/completions` to the base URL and also accepts a full Chat Completions address.
Remote services must use HTTPS; a local service may use HTTP.

Set the environment variables in IntelliJ's **Run IDE with Plugin** run configuration, then start the sandbox IDE.
From a terminal, set the variables in that terminal and run `./gradlew --no-daemon runIde`.
The API key lives only in local run configurations or environment variables; never write it into the source or a shared `.run` file.
The project does not load `.env` files automatically. Restart the sandbox IDE after changing an environment variable.
If you previously set `MENTORJ_LLM_MAX_TOKENS=1000` explicitly, remove it to pick up the new default,
or set it to `8192`; an explicit value wins over the default. An already running older plugin also needs a rebuild and a sandbox restart before verification.

The implementation uses the JDK HTTP client and the Gson the platform already provides, with no added dependency. Missing configuration, authentication failures, rate limits,
timeouts, network errors, invalid responses and truncated answers are reported in the answer area; raw error responses and the key are never shown.
Requests are not retried automatically, to avoid double billing. Successful responses keep their course citations and general knowledge notice.

The agent core is implemented:

- `ASK`: retrieves once, then answers from the question, the file source and the selected code; the model may retrieve again.
- `GENERATE_UNIT_TEST`: requires a test scenario and source, then generates a JUnit test against the real method signatures;
  a requested version wins, and JUnit 4 is the default. The result still has to be compiled and run in the target project.
- `GENERATE_COMMIT_MESSAGE`: requires a non-empty staged diff and writes an English Conventional Commit from the staged changes and the user's extra requirements only.
  It retrieves no course material and performs no Git operation.
- Tool loop: sends `tools`, keeps the assistant `tool_calls`, and fills results back with `tool_call_id`,
  running at most 3 tool rounds with 8 calls each and at most 4 model requests; the last round disables tools and asks for the final answer.
- Source aggregation: structured sources from the initial retrieval and from tool retrievals are deduplicated while keeping document and page;
  when no course material was retrieved the general knowledge flag stays set, and a call error never masquerades as an empty retrieval.
- Parameter validation, unknown-tool and tool-failure feedback, request cancellation, and input and context limits;
  a single user context is capped at 60,000 characters, the accumulated conversation at 120,000, and tool output at 20,000.
  Each request is handled independently and the previous turn is not sent to the model; the sidebar keeps its display history locally only (`.idea/mentorj-chat.json`).

The native tool protocol is verified against a local mock service; the Tongji gateway's tool-calling support for
`DeepSeek-V4.1-Flash` still needs a joint test with a real key, since a text example does not prove that a gateway supports tools. The adapter keeps the service's
reasoning for the following tool round without displaying it, reading it from `reasoning_content` or from the `reasoning` delta this gateway sends. The three
smart actions ask for that pass to be off with `chat_template_kwargs: {"thinking": false}`, so a generated test, commit message or exercise is not delayed by
deliberation the interface never shows, while a question still allows one; see
[DeepSeek tool calling](https://api-docs.deepseek.com/guides/thinking_mode/#tool-calls).
The shared interface extension keeps the old constructors and string tool methods; see [API.md](API.md) for the change notes and the joint-review items.

The UI is fully wired: the sidebar streams answers as message cards, and a user can ask questions, import course material, inspect cited sources,
copy or regenerate answers, copy or insert code blocks, and restore the local history; the editor context menu asks about the selected code and
generates JUnit tests or practice exercises, and the Tools menu generates a commit message. All four entry points share one background execution
path with streaming increments and cancellation, and results uniformly show the body, the cited sources and the general knowledge notice.

How to verify: run `./gradlew check build`. The tests use a local mock HTTP service and never call the Tongji platform or consume quota.
They cover text generation, tool protocol round trips, the loop limits, citation deduplication, request isolation, parameter and error handling.
With a real key configured, ask "explain Java dependency injection" in the sandbox sidebar and confirm a model answer comes back;
verify extra retrieval and citations once RAG has material loaded. Passing local tests does not mean the real platform integration is done.
RAG is wired to the course material index: after importing material, answers carry page citations, and a miss shows the general knowledge notice.

For layout, development environment, Git workflow and coding conventions see [AGENTS.md](AGENTS.md) (English) or its Chinese original [CONVENTION.md](CONVENTION.md); for the module contract between the three teams see [API.md](API.md).
