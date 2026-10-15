# A walkthrough reads in step order, or by folder, or by file

- **Key:** MARG-10 (schrek1/marginalis#10), MARG-47 (schrek1/marginalis#47)
- **Tags:** regression
- **Scenario:** `guided_layout.py`

## Protects

- By default the Guided section lists a walkthrough's steps in step order, as Next and Previous walk them.
  Consecutive steps in one file fold under a node for that file showing the step range; a lone step stays a row
  that names its file; a file the walk comes back to appears again in its place. Consecutive project-level steps
  (no file) fold the same way under a Project node showing the step range; a lone one stays a row.
- The Guided Layout menu in the tool window header switches to *By folder* (the folder tree) or *By file* (one
  node per file with its full path, files in the order of their first step, every step of the file inside).
- The arrows walk step numbers in every layout. Each project remembers its layout in `workspace.xml`.

## Automated

`py sandbox/scenarios/guided_layout.py`, on a 33-step walkthrough, an 18-step one that hops between files, and a
9-step one that opens with three project-level steps and comes back to project level after file steps:

- by default the layout is By step number, and the steps read in step order
- By folder groups the steps under folders
- By step number has no folder nodes, keeps step order, and each lone step's row names its file
- in the hopping walk, consecutive steps in one file fold under one node (four runs) and a file the walk comes
  back to appears again in step order
- in the project-first walk, steps 1–3 fold under a Project node, the lone project-level step 5 stays a row, and
  steps 8–9 after the file steps fold under a Project node of their own; Next walks steps 1 to 9 through them
- By file has a node per file with its full path, files follow their first step, steps inside follow their numbers,
  and in the hopping walk the service holds steps 2, 3, 6 and 9
- in By file Next walks the step numbers
- the layout survives a restart and lives in `workspace.xml`

## Manual

1. Fold a run in By step number (a file run or a Project run), step past it with Next → the walk does not skip
   the folded steps.
2. Switch layouts with a step selected → the same step stays selected.

## On main

There is no Guided Layout menu: `main` shows only the folder tree, so every check fails.
