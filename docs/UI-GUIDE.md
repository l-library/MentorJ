# MentorJ UI guide

This document is the UI team's usage guide and self-check list, covering the assignment's *simple user manual (including UI samples)*.
The contract lives in [API.md](../API.md); this file covers only what the user sees, what to expect, and what each message means.

## 1. Installation

### 1.1 The install package (daily use)

```bash
./gradlew buildPlugin
```

The artifact is `build/distributions/MentorJ-1.0.0-SNAPSHOT.zip`. In the IDE open `Settings → Plugins → gear icon →
Install Plugin from Disk`, select the zip, and restart. Once installed it works in any project, not just this repository.

### 1.2 Sandbox (development verification)

```bash
./gradlew runIde
```

This starts a 2025.3.6.1 sandbox IDE with its own configuration directory. Re-run it after a code change; there is no need to install the package by hand.

### 1.3 Configuring the API key

The plugin reads environment variables only and has no settings UI:

| Variable | Required | Default |
| --- | --- | --- |
| `MENTORJ_LLM_API_KEY` | Yes | None; when absent every request returns a configuration error |
| `MENTORJ_LLM_BASE_URL` | No | `https://agi.tongji.edu.cn/v1` |
| `MENTORJ_LLM_MODEL` | No | `DeepSeek-V4.1-Flash` |
| `MENTORJ_LLM_STREAM` | No | `true`; set it to `false` when the gateway cannot handle streaming |
| `MENTORJ_LLM_TIMEOUT_SECONDS` | No | `90`; request or streaming idle timeout in seconds |
| `MENTORJ_LLM_MAX_TOKENS` | No | `8192`; output limit per answer |

The environment variable must already exist **when the IDE process starts**:

```powershell
$env:MENTORJ_LLM_API_KEY = "your key"
& "E:\idea\bin\idea64.exe"
```

An IDEA started from the Start menu does not inherit variables from your terminal, and that is the only reason to see "not configured" after setting a key.

## 2. UI overview

The sidebar tool window `MentorJ` (the vertical button on the right, or `View → Tool Windows → MentorJ`) has three sections:

```
┌──────────────────────────────────────────────┐
│ Import course material   Clear index          │
│ Indexed 3 documents, 412 knowledge chunks     │
│ (10-31 20:12)                    Clear chat   │
├──────────────────────────────────────────────┤
│ ┌ MentorJ                            12:04 ┐ │
│ │ Encapsulation bundles data and the …      │ │
│ └───────────────────────────────────────────┘ │
│   Sources (course material):                  │
│   — Lec-3.pdf (P12-P14)                       │
│   8.2s                                        │
│   [Copy] [Regenerate]                         │
│                        ┌ You          12:06 ┐ │
│                        │ an example         │ │
│                        └────────────────────┘ │
├──────────────────────────────────────────────┤
│ Attached selected code: Foo.java:12-20  Remove│
│ Searching course material…                    │
│ [ Ask a question…                    ] [Send] │
└──────────────────────────────────────────────┘
```

Message cards: user questions are right-aligned; an assistant card holds only the body, while the sources, the elapsed time and the action buttons sit on a
**separate row** below the card, so sources never squeeze the body. The body renders as Markdown, and code blocks carry a language label with
`Copy` / `Insert` links.

Besides the sidebar there are four entry points: three in the editor context menu (ask about selected code / generate unit test / generate practice exercises) and
one in the Tools menu (generate Git commit message).

## 3. Usage paths

### 3.1 Importing course material

1. Click `Import course material` at the top; the file chooser accepts PDFs only
2. The status row goes `Importing N files…` → `Importing (1/3): file name` → `Import complete: 3 documents, 412 knowledge chunks.`
3. When some files failed, the row appends `1 files failed to import:` plus the file names, so **an import failure is never swallowed silently**
4. The status row becomes `Indexed 3 documents, 412 knowledge chunks (time)`

`Clear index` asks for confirmation first; afterwards answers fall back to general knowledge.

### 3.2 Course Q&A (the F0 main path)

Type a question and press Enter or click `Send`. What to watch:

| Moment | UI |
| --- | --- |
| Immediately | The question becomes a bubble on the right and an empty assistant card appears; Send greys out, `Stop` becomes available, and `Thinking…` shows above the input area |
| Streaming | Text appears character by character in the assistant card; while retrieving, the status becomes `Searching course material…`, and text from tool rounds is discarded |
| With citations | Below the answer card, `Sources (course material):` and `— file name (P14-P16)` appear on their own row |
| Without citations | Below the answer: `Answer is based on general knowledge; no course material was cited.` |
| Cancelled midway | The text received so far is kept and marked `This request was cancelled.`, the buttons return, and `Regenerate` is available |
| Done | `Copy` and `Regenerate` appear below the answer; a code block header shows `Copy` / `Insert` |
| Failure | The card turns red and shows the error text the agent assembled (a missing key, a network problem), and the UI does not wrap it again |

Three things that are easy to misjudge:

- **The UI must respond the moment Send is clicked.** A stall of a few seconds means the request runs on the UI thread, which is a defect.
- **Empty `Sources` does not mean general knowledge.** Only the explicit "based on general knowledge" sentence means that; when neither appears, the request failed.
- **Chat history is display-only and local.** Each request is independent, so a follow-up does not carry the previous turn; the sidebar does save the last 50 messages in
  the project's `.idea/mentorj-chat.json`, restored after closing the tool window or restarting the IDE, and `Clear conversation` deletes them.
- **Streaming depends on the gateway.** With `MENTORJ_LLM_STREAM=false` the answer appears all at once and the rest of the UI behaves the same.

The three context-menu actions and the Tools menu entry all open a result dialog: opening it starts the request, the body streams in,
`Stop` cancels and keeps what was received, and `Copy` below the answer copies the raw Markdown.

### 3.3 Asking about selected code

Select code → right-click `Ask About Selected Code` → type the question → the result dialog appears.

- When nothing is selected, the menu item is not shown
- The output goes to a dialog rather than the sidebar, because this is a single task whose result is one copyable text and seeing all of it at once matters more

### 3.4 Generating a unit test (F1-1)

Select a Java class or method → right-click `Generate Unit Test` → describe the scenario in the multi-line input
(for example, `test that a missing user ID throws IllegalArgumentException`) → the result dialog returns a JUnit class.

| Situation | Message |
| --- | --- |
| Confirming an empty input | `Describe the scenario to test first.` |
| No code selected | `Select the Java class or method to test first.` |
| A non-Java file | The menu item is not shown |

### 3.5 Generating practice exercises (the F1-3 creative action)

Select Java code → right-click `Generate Practice Exercises` → the input box **may stay empty**.

- Empty: the teaching assistant uses the selected code itself as the retrieval query and picks the exercise types and difficulty on its own
- Filled in: it constrains the focus, for example `only bug-finding exercises` or `add one adaptation exercise`

Output structure: first the concepts this code covers, then 3 exercises of increasing difficulty, each with a reference answer; when course material matches, page numbers are cited as usual.
Its design goal is the opposite of the other actions: it sets exercises to test you rather than writing code for you.

### 3.6 Generating a Git commit message (F1-2)

Run `git add` to stage the changes first, then use `Tools → Generate Git Commit Message`.

1. A `Reading staged changes` progress appears; the UI team runs `git diff --cached` itself
2. The result dialog shows a Conventional Commits message that can be copied in one click

| Situation | Message |
| --- | --- |
| No staged changes | `No staged changes. Run git add to stage the files you want to commit.` |
| Not a Git project | `The project has no local directory, so Git changes cannot be read.` |
| No git on PATH | `Cannot find the git command. Make sure Git is installed and callable on PATH.` |
| No answer within 30 seconds | `Reading staged changes timed out. Try again.` |

### 3.7 Message actions and selected code

- `Copy`: copies the raw Markdown of the whole answer; `Copy` in a code block header copies only that fragment.
- `Regenerate`: shown only on the newest answer; it removes the old card and retries the same request, and the stored history follows along.
- `Insert`: inserts the code block at the caret in the current editor and can be undone with Ctrl+Z; with no editor open, a message appears above the input area.
- After selecting code, move focus to the input box and `Attached selected code: file name:start-end` appears above it; click `Remove` to drop it.
  Only the selected fragment is sent, not the whole file, to save context budget.
- Enter sends, Shift+Enter inserts a newline, Esc stops generation; the input box grows automatically up to 5 lines.

## 4. Screenshot list (for the report)

| Id | Content | Feature |
| --- | --- | --- |
| S1 | Sidebar empty state plus the course material section | F0 UI integration |
| S2 | An answer with citations, the citation row visible | F0 cited sources |
| S3 | An answer without citations, the general knowledge notice visible | F0 general knowledge notice |
| S4 | Import progress plus the import statistics | F0 course material |
| S5 | The four context-menu entries (and hidden when nothing is selected) | F0 / F1 entry points |
| S6 | The unit test input box plus the generated JUnit class | F1-1 |
| S7 | The practice exercise dialog output | F1-3 |
| S8 | The commit message dialog | F1-2 |
| S9 | Streaming in progress with the `Searching course material…` status | F0 streaming |
| S10 | Message actions: code block `Copy` / `Insert`, whole-answer `Copy` / `Regenerate` | F0 message actions |
| S11 | Selected code chip plus the multi-line input (Shift+Enter) | F0 context |

For `S5`, take two shots: one with code selected and one without, to prove the greying logic in `update()`.

## 5. Known limitations

- **No cross-request context**: questions are independent of each other; the history is displayed locally only and never sent to the model as context again.
- **Streaming depends on the gateway**: when a gateway rejects `stream: true`, fall back with `MENTORJ_LLM_STREAM=false`;
  text already shown during a tool round is discarded, keeping only the final answer.
- **No generation logic here**: exercises, tests and commit messages are all produced by the agent team; the UI only collects input and presents results.
- **Commit messages depend on the git command line**: Git4Idea is not a dependency, in exchange for the plugin not being coupled to a Git plugin version.
- **Only PDFs can be imported**: other formats land in the failure list instead of being dropped silently.
