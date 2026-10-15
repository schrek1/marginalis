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
| MARG-10 | Lay out the Guided section by step number, by folder or by file | `feat/MARG-10-guided-layout` | schrek1/marginalis#37 | MuhammadFarag/marginalis#51 | `a6a78f7` |
| MARG-29 | Sandbox harness in the repo, with one runbook per feature | `chore/MARG-29-sandbox-in-repo` | schrek1/marginalis#40 | — | `ba2ef0a` |
| MARG-28 | Runbooks and scenarios for the features already in schreko_stable | `docs/MARG-28-runbooks` | — | — | `a90290b` |
| MARG-17 | Walkthrough steps in other files of a review diff open in that diff | `feat/MARG-17-steps-open-in-review-diff` | schrek1/marginalis#52 | — | `ebbe7f7` |
| MARG-50 | An open thread shows the agent's reply that arrived while its tab was in the background | `fix/MARG-50-api-reply-shows-inline` | schrek1/marginalis#54 | — | `dfe48c5` |
