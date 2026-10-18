# schreko_stable

Ondra's everyday Marginalis: upstream `main` plus the fork's features below. Each feature lands as one squash
commit through a pull request into this branch, after the gate (core tests and every sandbox scenario) passed.
No force-push, no direct commits. A release is `<upstream version>-schreko.N` (git tag = version); `builds.md`
and the fork issues say which release carries what. A feature build (`…-schreko.N-dev`) never comes from here.
`MARG-N` is the fork issue (or, for the older features, the fork PR) `schrek1/marginalis#N`. The features stay
upstream-ready branches off `main`; this file never leaves this branch.

| Key | Feature | Branch | Fork PR | Upstream issue | Applied head |
|---|---|---|---|---|---|
| MARG-18 | Steps in the same file stay in the editor you are reading | `fix/stay-in-diff-on-resolve` | schrek1/marginalis#1 | MuhammadFarag/marginalis#39 | `8714a4c` |
| MARG-19 | Tables and strikethrough in message bodies; a wide table scrolls sideways | `feat/gfm-tables-scroll` | schrek1/marginalis#2 | MuhammadFarag/marginalis#40 | `16be761` |
| MARG-20 | Thread icons in pull request review diffs | `feat/gutter-icons-in-review-diff` | schrek1/marginalis#3 | MuhammadFarag/marginalis#41 | `4e2167a` |
| MARG-21 | Zooming with a thread open keeps the code view where the mouse points | `fix/no-scroll-on-zoom` | schrek1/marginalis#4 | MuhammadFarag/marginalis#42 | `0e2bb1d` |
| MARG-22 | Live by default: every open thread of a project can be live at once | `feat/live-by-default` | schrek1/marginalis#5 | MuhammadFarag/marginalis#43 | `f25bbc1` |
| MARG-23 | Margin text renders as typed: line breaks, blank lines and spacing | `fix/margin-text-spacing` | schrek1/marginalis#6 | MuhammadFarag/marginalis#44 | `fb15583` |
| MARG-24 | Tool window arrows keep their place and walk Guided steps by number | `fix/toolwindow-keeps-selection` | schrek1/marginalis#7 | MuhammadFarag/marginalis#45 | `67663be` |
| MARG-25 | Expand and Collapse in the tool window; folded folders survive rebuilds | `feat/section-expand-collapse` | schrek1/marginalis#8 | MuhammadFarag/marginalis#46 | `848760c` |
| MARG-26 | Guided steps show just their number, not the label glued to it | `fix/guided-step-number` | schrek1/marginalis#9 | MuhammadFarag/marginalis#47 | `fd8ff53` |
| MARG-27 | The Margin tab reads oldest first, with a Sort menu per project | `feat/project-tab-order` | schrek1/marginalis#15 | MuhammadFarag/marginalis#48 | `ed5d181` |
| MARG-12 | Shorten the history of long threads | `feat/MARG-12-shorten-long-history` | schrek1/marginalis#31 | MuhammadFarag/marginalis#49 | `aa52485` |
| MARG-11 | Select a thread in the tool window from the thread itself | `feat/MARG-11-select-thread-in-tool-window` | schrek1/marginalis#34 | MuhammadFarag/marginalis#50 | `3da3df3` |
| MARG-10 | Lay out the Guided section by step number, by folder or by file | `feat/MARG-10-guided-layout` | schrek1/marginalis#37 | MuhammadFarag/marginalis#51 | `eb1e2e4` |
| MARG-29 | Sandbox harness in the repo, with one runbook per feature | `chore/MARG-29-sandbox-in-repo` | schrek1/marginalis#40 | — | `ba2ef0a` |
| MARG-28 | Runbooks and scenarios for the features already in schreko_stable | `docs/MARG-28-runbooks` | — | — | `a90290b` |
| MARG-17 | Walkthrough steps in other files of a review diff open in that diff | `feat/MARG-17-steps-open-in-review-diff` | schrek1/marginalis#52 | — | `ebbe7f7` |
| MARG-50 | An open thread shows the agent's reply that arrived while its tab was in the background | `fix/MARG-50-api-reply-shows-inline` | schrek1/marginalis#54 | — | `dfe48c5` |
| MARG-36 | The ✉ envelope shows only while something in the thread is unread | `feat/MARG-36-envelope-only-while-unread` | schrek1/marginalis#57 | — | `50f05dc` |
| MARG-48 | The Margin tab shows only when there is something in it | `fix/MARG-48-no-empty-margin-tab` | schrek1/marginalis#55 | — | `6a0ebec` |
| MARG-45 | The reply you are typing in the Margin tab stays put while other threads change | `fix/MARG-45-margin-tab-keeps-reply-in-place` | schrek1/marginalis#58 | — | `02b80a1` |
| MARG-60 | Select Opened Thread works with a review diff in front | `fix/MARG-60-locate-in-review-diff` | schrek1/marginalis#64 | — | `63a26b4` |
| MARG-81 | Sandbox scenarios in Docker, in parallel from one build, with a testing guide | `chore/sandbox-header-press` | — | — | `da69c29` |
| MARG-86 | A new project thread no longer stalls the IDE while its REST call opens the Margin tab | `fix/MARG-86-tab-open-after-rest` | — | — | `9ba1083` |
| MARG-63 | Headless tests for the sandbox checks: 68 move out of the UI suite, plus twins of the UI-only ones (part 1) | `feat/MARG-63-headless` | — | — | `65c8920` |
| MARG-90 | Sandbox scenarios pass reliably side by side: the test project lives on the container's disk, starts wait until the IDE settles, one run per machine | `chore/MARG-90-suite-stability` | — | — | `ccaf1c7` |
| MARG-63 | The sandbox scenarios as a Kotlin suite run by Gradle, as stable as the Python one, which it will replace (part 2) | `feat/MARG-63-kotlin-suites` | — | — | `8117435` |
| MARG-63 | The stable gate runs the Kotlin suite; the Python scenarios retire, sandbox.py stays for demos (part 3) | `chore/MARG-63-gate-on-kotlin` | — | — | `0a5361e` |
| MARG-38 | Every pull request runs the sandbox scenarios in CI, on the free runner: Linux file ownership fixed and the waits that a slow machine needs | `feat/MARG-38-ci-e2e` | — | — | `fe39057` |
| MARG-96 | Open Thread on a project thread the agent just started always brings the Margin tab to the front | `fix/MARG-96-reveal-wins` | — | — | `ab808b8` |
| MARG-85 | Core tests no longer flake on Windows: threads created in the same instant keep their arrival order | `fix/MARG-85-deterministic-order` | — | — | `e31b1e0` |
| MARG-83 | Stable releases are built in CI and served to every machine from one plugin repository on GitHub Pages | `feat/MARG-83-ci-releases` | — | — | `fe26212` |
| MARG-92 | Sandbox failure paths: each scenario starts from the default app settings, and a restart never starts from an older project copy | `fix/MARG-92-sandbox-failure-paths` | — | — | `ca42e7b` |
| MARG-101 | CI keeps its Gradle cache under the 10 GB limit: only the build job writes it | `fix/MARG-101-ci-cache-one-writer` | — | — | `fe9eafa` |
| MARG-43 | Sandbox scenarios: a core tag runs the critical flows alone | `feat/MARG-43-core-tag` | — | — | `2bd8f09` |
| MARG-103 | Sandbox scenarios fail on errors the plugin logs | `feat/MARG-103-fail-on-plugin-errors` | — | — | `febd7d0` |
| MARG-117 | A stalled IDE no longer blames Marginalis: the Margin tab's editor provider takes no read lock | `fix/MARG-117-provider-no-read-lock` | — | — | `510f774` |
| MARG-98 | Stable releases itself once CI is green on a merge | `feat/MARG-98-release-on-green-ci` | — | — | `0e28007` |
| MARG-104 | E2E scenario: the agent and the user take turns in the margin | `feat/MARG-104-agent-user-loop` | — | — | `bfcdc6b` |
| MARG-105 | E2E scenario: what the user left in the margin survives an IDE restart | `feat/MARG-105-threads-survive-restart` | — | — | `7378c33` |
| MARG-106 | E2E scenario: the user starts a thread on a line, a selection or a whole file | `feat/MARG-106-user-starts-thread` | — | — | `e749dc4` |
| MARG-107 | E2E scenario: a thread follows its code while the user edits | `feat/MARG-107-anchoring-through-edits` | — | — | `f538111` |
| MARG-119 | Sandbox scenarios run the plugin users get, without the test libraries | `fix/MARG-119-sandbox-runs-the-shipped-plugin` | — | — | `b57fcc3` |
| MARG-123 | CI actions on their Node.js 24 majors | `chore/MARG-123-ci-actions-node24` | — | — | `7d6f4ae` |
| MARG-122 | Every CI job that runs Gradle takes a checked IDE index | `chore/MARG-123-ci-actions-node24` | — | — | `22ff681` |
| MARG-131 | A thread whose code an edit removed stays orphaned | `fix/MARG-131-no-pin-after-edit` | — | — | `07fdd66` |
| MARG-126 | Sandbox scenarios run on the open-source IntelliJ IDEA 2026.2 | `chore/MARG-126-sandbox-on-2026-2` | — | — | `0e653ec` |
| MARG-136 | The plugin compiles for the floor's Kotlin stdlib (API and language 2.2) | `fix/MARG-133-tab-open-without-blocking` | — | — | `26fb956` |
| MARG-133 | The Margin tab comes back once the project's configuration is done | `fix/MARG-133-tab-open-without-blocking` | — | — | `aa1dbb6` |
| MARG-141 | CI runs on ubuntu-24.04 until the move to Ubuntu 26 is made on purpose | `chore/MARG-141-pin-ubuntu-24-04` | — | — | `c3bb791` |
| MARG-140 | ci.yml is actionlint-clean | `chore/MARG-140-actionlint-clean` | — | — | `6b40848` |
| MARG-149 | The plugin's Overview lists the fork features newest first, under their release | `feat/MARG-149-overview-by-release` | — | — | `ef351e8` |
| MARG-141 | Gradle 9.8.1, with the distribution's checksum | `chore/MARG-141-gradle-9-8-1` | — | — | `32faa3c` |
| MARG-135 | Sandbox plugins come from the host's Gradle, the sandbox IDE from the image | `chore/MARG-135-ide-in-image` | — | — | `bc44b25` |
| MARG-141 | CI builds the plugin once: package, then test, verify and e2e on its zip | `chore/MARG-141-job-graph` | — | — | `5ab8b5e` |
| MARG-134 | The Plugin Verifier checks the floor and the latest IntelliJ IDEA, from one version | `chore/MARG-134-floor-and-latest` | — | — | `4fbff33` |
| MARG-101 | CI deletes the Gradle cache entries a newer one has replaced | `chore/MARG-101-cache-prune` | — | — | `4267445` |
| MARG-158 | CI's latest IntelliJ IDEA is 2026.2.3 | `chore/MARG-158-latest-ide-2026-2-3` | — | — | `500c3b7` |
| MARG-156 | A sandbox stop exits the IDE as a user's Exit does, instead of deadlocking it | `fix/MARG-156-shutdown-hang` | — | — | `bca818c` |
