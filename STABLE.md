# schreko_stable

Ondra's everyday Marginalis: upstream `main` plus the fork's features below. Each feature lands as one squash
commit through a pull request into this branch, after the gate (core tests and every sandbox scenario) passed.
No force-push, no direct commits. Releases are tagged `stable-<version>`; `builds.md` and the fork PRs say which
release carries what. The features stay upstream-ready branches off `main`; this file never leaves this branch.

| Feature | Branch | Fork PR | Upstream issue | Applied head |
|---|---|---|---|---|
| Steps in the same file stay in the editor you are reading | `fix/stay-in-diff-on-resolve` | schrek1/marginalis#1 | MuhammadFarag/marginalis#39 | `8714a4c` |
| Tables and strikethrough in message bodies; a wide table scrolls side… | `feat/gfm-tables-scroll` | schrek1/marginalis#2 | MuhammadFarag/marginalis#40 | `16be761` |
| Thread icons in pull request review diffs | `feat/gutter-icons-in-review-diff` | schrek1/marginalis#3 | MuhammadFarag/marginalis#41 | `83f863f` |
| Zooming with a thread open keeps the code view where the mouse points | `fix/no-scroll-on-zoom` | schrek1/marginalis#4 | MuhammadFarag/marginalis#42 | `0e2bb1d` |
| Live by default: every open thread of a project can be live at once | `feat/live-by-default` | schrek1/marginalis#5 | MuhammadFarag/marginalis#43 | `f25bbc1` |
| Margin text renders as typed: line breaks, blank lines and spacing | `fix/margin-text-spacing` | schrek1/marginalis#6 | MuhammadFarag/marginalis#44 | `fb15583` |
| Fix/toolwindow keeps selection | `fix/toolwindow-keeps-selection` | schrek1/marginalis#7 | MuhammadFarag/marginalis#45 | `67663be` |
| Feat/section expand collapse | `feat/section-expand-collapse` | schrek1/marginalis#8 | MuhammadFarag/marginalis#46 | `848760c` |
| Guided steps show just their number, not the label glued to it | `fix/guided-step-number` | schrek1/marginalis#9 | MuhammadFarag/marginalis#47 | `fd8ff53` |
| The Margin tab reads oldest first, with a Sort menu per project | `feat/project-tab-order` | schrek1/marginalis#15 | MuhammadFarag/marginalis#48 | `ed5d181` |
