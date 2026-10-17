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
