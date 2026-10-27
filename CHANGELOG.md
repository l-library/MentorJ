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

### Changed

- Replace the LLM placeholder and include file path and full source context in generation requests.
- Default to Tongji's `DeepSeek-V4.1-Flash` endpoint with configurable generation limits.
- Fill in the `<vendor>` and `<description>` placeholders in `plugin.xml`.

### Fixed

- Increase the default output budget from 1000 to 8192 tokens for course explanations and code examples.
- Preserve partial text with a visible incomplete-answer notice instead of discarding it on length limits;
  continue rejecting truncated tool calls and reasoning-only output.
