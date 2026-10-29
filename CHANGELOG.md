<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MentorJ Changelog

## [Unreleased]

### Added

- GitHub Actions CI pipeline running Checkstyle lint, build, tests, and plugin verification on pushes and pull requests to `main`.
- Course material retrieval: PDFs are parsed into page-level knowledge chunks and indexed with BM25 plus semantic embeddings, so answers cite the source document and page. Questions the material does not cover are reported as general knowledge only.

### Changed

- Fill in the `<vendor>` and `<description>` placeholders in `plugin.xml`.
- The plugin bundles only Tika's PDF parser, and no longer ships BouncyCastle, `pdfbox-tools`, `picocli` or `commonmark`, which text extraction never reaches and which the IDE already provides.
