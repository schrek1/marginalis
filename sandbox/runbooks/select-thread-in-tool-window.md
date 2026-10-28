# Find a thread in the tool window in one click

- **Key:** MARG-11 (schrek1/marginalis#11), MARG-171 (schrek1/marginalis#171)
- **Scenario:** `SelectThreadInToolWindowTest`

## Protects

- *Select in Marginalis* in a thread's header opens the tool window and selects that thread: a walkthrough step in
  its Guided section, any other thread in the section that lists it, a resolved one inside the folded Resolved
  section.
- It selects its own thread wherever the editor's caret is, and the same from a review diff and from the Margin tab.
- The tool window header has no *Select Opened Thread*: it guessed the thread from the caret and could land on the
  file's row in Open instead of the step. The action stays in Find Action and can take a shortcut.

## Automated

`./gradlew uiSandboxTest --tests '*SelectThreadInToolWindowTest'`. On a 33-step walkthrough plus two plain threads:

- Select in Marginalis selects step 27, in its Guided section
- a thread outside any walkthrough is selected in Open
- a resolved thread is selected inside the folded Resolved section
- the tool window header has no Select Opened Thread
- a step on line 1, with the caret on line 2 and the focus in the editor: Select in Marginalis selects its step, not
  the file under the caret, in its Guided section
- Select in Marginalis in a review diff selects its step, in its Guided section
- Select in Marginalis in the Margin tab selects its project thread

## Manual

1. In a long walkthrough, open a deep step from the Margin tab and press *Select in Marginalis* → the tool window
   comes up with the folders down to the step unfolded and the step selected, scrolled into view.
2. With the tool window filtered (e.g. *Blockers Only*), select a thread the filter hides → the filter resets to *All* and
   the thread is selected.

## On main

Before MARG-11 neither button exists. Before MARG-171 the header still shows *Select Opened Thread*, and that case
fails.
