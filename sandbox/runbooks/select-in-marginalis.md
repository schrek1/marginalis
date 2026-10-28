# Find the thread you are reading through Navigate › Select In

- **Key:** MARG-190 (schrek1/marginalis#190)
- **Scenario:** `SelectInMarginalisTest`

## Protects

- *Navigate › Select In* (Alt+F1) lists *Marginalis* for a file that has threads, and choosing it selects the
  thread on the caret's line in the tool window: a walkthrough step in its Guided section.
- On a line without a thread it selects the file.
- A file without threads does not list *Marginalis*.
- In a review diff, the caret's row of the review's text counts: the thread on that row is selected.

## Automated

`./gradlew uiSandboxTest --tests '*SelectInMarginalisTest'`. On a one-step walkthrough plus one plain thread in
`review_target.py`:

- Select In lists Marginalis for a file with threads; the step on the caret's line is selected, in its Guided section
- on a line without a thread the file is selected
- Select In does not list Marginalis for a file without threads
- in a review diff of the file, the thread on the caret's row is selected

## Manual

1. Put the caret in a reply box of an open thread, press Alt+F1 and choose *Marginalis* → that thread is selected,
   wherever the editor's caret is.
2. Filter the tool window to *Blockers Only*, then Select In on an ordinary thread → the filter resets to *All*, the
   thread is selected, and a balloon says the filter was hiding it.
3. Start a new comment and Select In from its reply box → the file is selected, and a balloon says to submit the
   draft to list it.

## On main

Upstream `main` has the same feature; on `schreko_stable` before MARG-190 Select In does not list Marginalis, and
every case fails.
