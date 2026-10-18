# MentorJ agent guidelines

Engineering conventions for everyone working on this repo: team members and AI agents alike. Product background lives in [README.md](README.md). This file mirrors [CONVENTION.md](CONVENTION.md), the Chinese original. Change both in the same commit.

## 1. Project layout

MentorJ is an IntelliJ Platform plugin written in Java 21 and built with Gradle (Kotlin DSL).

```
.
├── .run/                       Predefined run configurations (Run IDE with Plugin / Run Tests / Run Verifications)
├── gradle/
│   ├── wrapper/                Gradle Wrapper (currently 9.7.1)
│   └── libs.versions.toml      Version catalog; declare every dependency version here
├── src/
│   ├── main/
│   │   ├── java/               Plugin sources, package prefix edu.mentorj
│   │   └── resources/
│   │       ├── META-INF/plugin.xml   Plugin manifest (id, name, vendor, extension registration)
│   │       └── messages/             Message bundles
│   └── test/
│       └── java/               Test sources, mirroring the layout of main
├── build.gradle.kts            Java 21 toolchain, IntelliJ Platform dependency and test framework
├── settings.gradle.kts         Repositories and Gradle plugin versions
├── gradle.properties           group=edu.mentorj, version, configuration/build cache flags
├── .editorconfig               Editor rules enforced by tooling
├── .gitattributes              Line-ending rules enforced by tooling
├── CHANGELOG.md                Changelog in Keep a Changelog format
└── README.md                   Product background
```

- The plugin id is `edu.mentorj.MentorJ` (the `<id>` element in plugin.xml). It cannot change after the first release. Gradle's `rootProject.name` and `group` serve a different purpose and do not need to match it.
- The compile target is IntelliJ IDEA `2025.3.6.1`, set by `intellijIdea(...)` in build.gradle.kts. Tests use JUnit 4 with the IntelliJ Platform Test Framework.
- Put dependency and version declarations in `gradle/libs.versions.toml`. Keep literal version numbers out of build.gradle.kts.
- `<vendor>` and `<description>` in plugin.xml are still template placeholders and must be replaced before release.

## 2. Development environment

- JDK 21 comes from the Gradle toolchain plus foojay-resolver, which downloads it, so no manual install is needed. Set the project SDK to JDK 21 if you want the IDE to compile directly.
- Always build with the checked-in wrapper, `./gradlew` (`gradlew.bat` on Windows), so everyone uses the same Gradle version. Upgrade the wrapper by running `./gradlew wrapper` and committing the result; edit `gradle-wrapper.properties` by hand only as a last resort.
- Open the repo in IntelliJ IDEA as a Gradle project and turn on EditorConfig support (Settings → Editor → Code Style → Enable EditorConfig support).
- Configuration cache and build cache are on (see gradle.properties). If a cache error appears after you change build scripts, reproduce it with `--no-configuration-cache` before digging further.

| Command | What it does |
| --- | --- |
| `./gradlew build` | Compile and package the plugin |
| `./gradlew test`, `./gradlew check` | Run tests and checks |
| `./gradlew runIde` | Start a sandbox IDE with the plugin loaded (same as the Run IDE with Plugin configuration, debugger works) |
| `./gradlew verifyPlugin` | Check plugin compatibility against the target IDE |
| `./gradlew patchChangelog` | At release time, turn the `[Unreleased]` section into a dated version section |

Run `git diff --check` before committing to catch whitespace and line-ending problems.

## 3. Git workflow

### 3.1 Branches (Git Flow, main and feat only)

- `main` is the stable branch and is always releasable. It takes changes through pull requests only, never direct pushes.
- `feat/<name>` is a feature branch cut from `main` and deleted as soon as its pull request lands, so it lives for days, not weeks. Use `fix/<name>` for a pure bug fix.
- Name branches with a short English kebab-case phrase, for example `feat/action-toolbar` or `fix/npe-on-empty-project`.
- One branch carries one thing: a single feature or a single fix. Merge it when it is done.

### 3.2 Commit messages (Conventional Commits)

- Format: `<type>(<scope>): <subject>`. Write type and subject in English. Use the imperative mood, keep the subject under 72 characters, and leave off the trailing period. The scope is optional.
- Allowed types: `feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore`.
- The optional body explains why the change is needed, wrapped at 72 characters. The footer carries `Closes #123` or `BREAKING CHANGE: ...`.
- One commit does one thing. Keep unrelated formatting changes out of a feature commit (see section 4).

```
feat(toolwindow): add refresh action to result tree

The tree kept stale results after re-running analysis, which made
comparing runs impossible. Closes #12.
```

### 3.3 Pull requests

- Use the Conventional Commit format for the title. The description states the motivation, what changed, and how you verified it (commands run, what you exercised by hand). Add a screenshot or recording for UI changes.
- Before merging:
  - At least one team member approves.
  - `./gradlew check` passes. If the change touches plugin.xml, dependencies, or build scripts, also run `./gradlew verifyPlugin`.
  - User-visible changes get an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md).
- Merge with a merge commit to keep the history. Squash and rebase merges are out.
- Follow-up commits on the branch during review are fine, and force-pushing is fine. History does not need tidying before merge.
- Delete the branch after merging. Link the issue with `Closes #N` in the pull request description or a commit footer.
- Keep pull requests small and focused. Split large work into several.

## 4. File formatting

- All text files use UTF-8 without BOM and LF line endings. The one exception is `*.bat` and `*.cmd`, which use CRLF.
- Indent with spaces, with widths set in [.editorconfig](.editorconfig): Java and XML use 4 spaces, and Gradle Kotlin DSL files (`*.kts`) use 4 spaces as well.
- End every file with a newline and strip trailing whitespace, except in Markdown.
- [.editorconfig](.editorconfig) and [.gitattributes](.gitattributes) are enforced by tooling. Turn on EditorConfig support in your IDE, and run `git diff --check` before committing.
- Keep whole-file reformatting out of commits that change behaviour.

## 5. General coding practice

### Before changing code

- Read the files and their callers first so you know which flows the change affects. Do not guess at runtime behaviour.
- Do what was asked. Leave unrelated refactors, formatting, and fixes alone, and record out-of-scope problems for a separate change.

### Implementation

- Walk the decision ladder and stop at the first option that solves the problem: does this need to exist at all, can I reuse an existing implementation in the repo, does the standard library do it, does the platform provide it, is there an installed dependency for it, can it be one readable line, and otherwise write the smallest working implementation.
- Delete before you add. Fewest files, smallest diff.
- Write boring code. Skip abstractions that earn nothing, interfaces with one implementation, speculative configuration, and scaffolding for future needs.
- Fix the root cause. When you change shared behaviour, read every caller and fix the common path where one exists.
- Comment on the why, since the code already says what. Skip the comment when a good name says it.

### Non-negotiable

- Input validation at trust boundaries, error handling that prevents data loss, security controls, accessibility, and correctness on relevant edge cases survive every simplification.
- Secrets, tokens, and personal configuration never enter the repo.
- Non-trivial new logic leaves one minimal runnable check, either a test or a script. Trivial changes need none. Tests use JUnit 4 with the IntelliJ Platform Test Framework, mirror the directory layout and naming of the code under test, and assert concrete inputs and outputs rather than abstract fixture data.

## 6. IntelliJ plugin specifics

- Confirm behaviour in a running sandbox with `./gradlew runIde`. Unit tests cover the pure logic that can be isolated.
- Do not hardcode UI text. Put it in a message bundle under `src/main/resources/messages/` and reference it by key, which keeps translation open.
- Register extensions, services, and listeners in `plugin.xml` (or with platform annotations) and add a comment saying what each registration is for.
- Deal with platform API deprecation and cross-version compatibility warnings as they appear, and run `./gradlew verifyPlugin` before a release.
- Keep `since-build` and `until-build` consistent with the declared target IDE version.
