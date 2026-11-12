# A walkthrough reads in step order, or by folder, or by file

- **Key:** upstream MuhammadFarag/marginalis#146 (replaces the fork's MARG-10 (schrek1/marginalis#10) Guided layout)
- **Scenario:** `WalkthroughLayoutTest`

## Protects

- Each walkthrough is its own section in the tool window, named after the walkthrough. By default it lists every
  step in step order, resolved ones in their place, and each row names its file.
- The Walkthrough Layout menu in the tool window header switches to *By Folder* (the folder tree) or *By File* (one
  node per file, files in the order of their first step). *Group Consecutive Steps by File* folds neighbouring steps
  in one file (or at project level) under one node; a file the walk comes back to appears again.
- The arrows walk open steps by number in every layout. Each project remembers its layout and grouping in
  `workspace.xml`.
- A walkthrough whose every step is resolved leaves the top level and shows as one group under Resolved.

## Automated

`./gradlew uiSandboxTest --tests '*WalkthroughLayoutTest'`, on a 33-step walkthrough, an 18-step one that hops between
files, and a 9-step one that opens with three project-level steps and comes back to project level after file steps:

- by default the layout is By Step Number, every step reads in order, nothing is grouped and every row names its file
- with grouping on, the hopping walk folds into runs (resolved step 1 in its place) and the service comes back as
  steps 6 and 9; project-level steps 1–3 and 8–9 fold under project nodes
- By Folder groups the steps under folders
- By File gathers the service's steps 2, 3, 6 and 9 in one node, files follow their first step
- in By File Next walks the open steps by number
- the layout and the grouping survive a restart and live in `workspace.xml`
- resolving every step of the 9-step walkthrough moves it, whole, under Resolved

## Manual

1. Switch layouts with a step selected → the same step stays selected.
2. Right-click a walkthrough's section → Resolve Walkthrough asks once, then the walkthrough moves to Resolved.
