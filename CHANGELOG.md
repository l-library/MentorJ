<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MentorJ Changelog

## [Unreleased]

### Added

- Native Chat Completions tool calls with correlated results, bounded agent loops, and retrieved source aggregation.
- Validated action inputs, JUnit and Conventional Commit prompts, cancellation handling, and context limits.
- Configurable output token budget and regression tests for tool protocol, retrieval metadata, and agent isolation.
- Configurable Chat Completions text generation for the Tongji AI platform, with local environment-based credentials.
- Actionable LLM configuration and request errors, plus local HTTP and agent integration tests.
- GitHub Actions CI pipeline running Checkstyle lint, build, tests, and plugin verification on pushes and pull requests to `main`.
- Course material retrieval: PDFs are parsed into page-level knowledge chunks and indexed with BM25 plus semantic embeddings, so answers cite the source document and page. Questions the material does not cover are reported as general knowledge only.
- Sidebar TA chat with cited sources and the general-knowledge notice, plus a course material panel that imports PDFs with progress, reports failures and shows index statistics.
- Editor and Tools menu entries for asking about selected code, generating JUnit tests from a natural-language scenario, and generating a commit message from the staged diff, each running off the EDT with cancellation.
- Creative smart action: an editor entry that turns the selected code into graded practice questions with reference answers, reusing course material retrieval to ground the knowledge points it covers.

### Changed

- Replace the LLM placeholder and include file path and full source context in generation requests.
- Default to Tongji's `DeepSeek-V4.1-Flash` endpoint with configurable generation limits.
- Fill in the `<vendor>` and `<description>` placeholders in `plugin.xml`.
- The plugin bundles only Tika's PDF parser, and no longer ships BouncyCastle, `pdfbox-tools`, `picocli` or `commonmark`, which text extraction never reaches and which the IDE already provides.

### Fixed

- Increase the default output budget from 1000 to 8192 tokens for course explanations and code examples.
- Preserve partial text with a visible incomplete-answer notice instead of discarding it on length limits;
  continue rejecting truncated tool calls and reasoning-only output.
