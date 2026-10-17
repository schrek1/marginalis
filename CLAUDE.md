# Marginalis — agent notes

JetBrains plugin: in-editor agent↔human comment threads. Open work lives
in GitHub issues; design history lives in `docs/marginalis-handover.md`
(the original brief), `docs/navigate-handover.md` and
`docs/iconography-handover.md` (the 0.1.29 icon family); `docs/BACKLOG.md`
is the shipped record and decision log.

## Building

- The tools a machine needs (JDK, Docker, `gh`, actionlint, …) are in
  `docs/dev-tools.md`; a tool the work starts to rely on goes in there.
- How to test a change, from unit tests to the sandbox scenarios in
  Docker, is in `docs/testing.md`: read it before writing or running
  tests. New tests assert with Kotest matchers (`shouldBe`,
  `shouldContainExactly`, …), not `kotlin.test`'s `assertEquals`.
- `./gradlew buildPlugin` → zip in `build/distributions/`;
  `./gradlew :core:test` runs the domain tests (`scripts/test-core.sh`
  runs them in a single JVM for environments where forked test workers
  can't connect back); `./gradlew test` runs the plugin's platform tests
  against a real in-memory IDE.
- CI (`.github/workflows/`) is the honest environment: compiles against
  the real `ideaIC-2025.2` floor and runs the Plugin Verifier
  (`recommended()` IDE set). Local builds may compile against a newer
  local IDE via the `marginalis.localIde` property, so floor violations
  surface in CI, not locally.
- Releases: stamp `[Unreleased]` in `CHANGELOG.md` as the new version,
  bump `version` in `build.gradle.kts`, commit, push, tag `v*` → CI
  builds, signs, publishes to the Marketplace stable channel and creates
  the GitHub Release. The changelog section becomes the Marketplace
  "What's New". Pushing to `main` without a tag releases nothing.
- Dev builds for hands-on review: temporarily set `version` to
  `<next>-devN`, `./gradlew buildPlugin`, then `git checkout
  build.gradle.kts` — the zip's version makes it obvious which build the
  IDE runs (`ping` reports it).

## Dogfooding — the margin protocol

This project reviews itself in its own margins. When the reviewer's IDE
is running the plugin:

- Sweep unread margin comments at the start of a turn; replies land there,
  born unread.
- Never edit a file with open threads — drive each to resolution first
  (its conclusion becomes part of the edit, or reply why it needs none).
  An fyi blocks only while a user reply on it awaits your answer, and
  is never yours to resolve.
- The resolver is the completer: RESOLVED means the outcome is in the
  code (or explicitly moot). Approval is a reply — often a one-click
  Agree; land the change, then resolve.
- Features are reviewed by live demo, not by walkthrough: build a dev
  zip, seed demo threads once `ping` reports that version, and wait in
  the margin. Use a review walkthrough only when the user asks for one;
  resolve demo threads once the change ships.
- End a turn that expects the user's reply by starting `comment_wait` in
  the background (the served guide has the pattern); the user's Hand
  Back — or a Submit in a Live thread — wakes you. After a live wake,
  answer and wait again.

## Project conventions

- API `line` parameters are 1-based (as agents read files); the core
  model's `CommentThread.line` is 0-based. Convert at the transport
  boundary.
- Architecture: `core/` is pure Kotlin (model, lifecycle, walks,
  AnchorPolicy, ThreadsCodec) — it imports nothing from the plugin
  module, ever. The plugin module is adapters: transport, Swing/editor
  UI, VFS + file I/O, markers (MarginalisStore pairs core threads with
  live RangeHighlighters).
- Roadmap discipline: open work lives in GitHub issues (`gh issue list`)
  — check there before starting, file new findings there, and close the
  issue when the work lands. `docs/BACKLOG.md` is the historical shipped
  record and decision log, append-only. Don't gold-plate ahead of the
  agreed item.
- Issue labels — apply on filing, keep current: `api` (the agent-facing
  HTTP contract and served guide) and `ux` (the in-IDE experience) are
  areas, both when a change spans them; `diff` narrows `ux` to thread
  icons and panels in diff and review views; `qol` marks friction removers
  on what already works (batchable between feature releases), as
  opposed to new capability; `refine` means a design conversation with
  the user must settle before code — never start a `refine` issue
  without that conversation, and drop the label once it happens.
- Guided vocabulary is "walkthrough"/"steps" (wire param `walkthrough`,
  UI actions First/Previous/Next/Last Step).
- The served guide (`src/main/resources/marginalis/agent-guide.md`) is
  the agent contract: every behaviour or wire change lands there.
  `skills/marginalis/SKILL.md` is only a bootstrap that points at the
  guide; change it only when the bootstrap itself changes, since every
  user must re-run the skills CLI to pick it up.
- `CHANGELOG.md` is the Marketplace "What's New": entries are business
  oriented, outcome first, in the owner's voice
  (`personal-style:writing-style`), with no internal mechanics. The
  `<description>` in `plugin.xml` follows the same voice.
- Comments: write self-documenting code. Treat the urge to comment as a
  sign a rename or refactor is missing; comment only what the code can't
  say (typically a non-obvious platform quirk). Update comments a change
  makes false. One deliberate exception: a test that pins a known defect
  carries `// FixMe: Known issue - #NN <one line>` above it; the fix flips
  that test and removes the marker.
- Icons: SVGs in `src/main/resources/icons/`, loaded through
  `MarginalisIcons`. Brand icons are single theme-neutral SVGs (no
  `_dark` twin), designed dark-first; monochrome toolbar icons (e.g.
  Hand Back) keep the `_dark` pair. Turn signals are ✉ (your move) and ✈
  (the agent's). Some platform surfaces are plain strings (tab titles,
  tool window titles) and can only carry text.
- Platform floor: `since-build` 252 (2025.2). Prefer long-stable public
  API; CI's Plugin Verifier fails on internal / scheduled-for-removal
  usage, which a local build against a newer IDE won't catch.
