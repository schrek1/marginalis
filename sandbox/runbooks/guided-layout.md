# A walkthrough reads in step order, or by folder, or by file

- **Key:** MARG-10 (schrek1/marginalis#10)
- **Tags:** regression
- **Scenario:** `guided_layout.py`

## Protects

- By default the Guided section lists a walkthrough's steps in step order, as Next and Previous walk them.
  Consecutive steps in one file fold under a node for that file showing the step range; a lone step stays a row
  that names its file; a file the walk comes back to appears again in its place.
- The Guided Layout menu in the tool window header switches to *By folder* (the folder tree) or *By file* (one
  node per file with its full path, files in the order of their first step, every step of the file inside).
- The arrows walk step numbers in every layout. Each project remembers its layout in `workspace.xml`.

## Automated

`py sandbox/scenarios/guided_layout.py`, on a 33-step walkthrough and an 18-step one that hops between files:

- by default the layout is By step number, and the steps read in step order
- By folder groups the steps under folders
- By step number has no folder nodes, keeps step order, and each lone step's row names its file
- in the hopping walk, consecutive steps in one file fold under one node (four runs) and a file the walk comes
  back to appears again in step order
- By file has a node per file with its full path, files follow their first step, steps inside follow their numbers,
  and in the hopping walk the service holds steps 2, 3, 6 and 9
- in By file Next walks the step numbers
- the layout survives a restart and lives in `workspace.xml`

## Manual

1. Fold a run in By step number, step past it with Next → the walk does not skip the folded steps.
2. Switch layouts with a step selected → the same step stays selected.

## On main

There is no Guided Layout menu: `main` shows only the folder tree, so every check fails.
