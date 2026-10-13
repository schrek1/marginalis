# Find a thread in the tool window in one click

- **Key:** MARG-11 (schrek1/marginalis#11)
- **Tags:** regression
- **Scenario:** `select_thread_in_tool_window.py`

## Protects

- *Select in Marginalis* in a thread's header opens the tool window and selects that thread: a walkthrough step in
  its Guided section, any other thread in the section that lists it, a resolved one inside the folded Resolved
  section.
- *Select Opened Thread* in the tool window header selects the thread on the caret's line, or the file when that
  line has none.

## Automated

`py sandbox/scenarios/select_thread_in_tool_window.py`. On a 33-step walkthrough plus two plain threads:

- Select in Marginalis selects step 27, in its Guided section
- a thread outside any walkthrough is selected in Open
- a resolved thread is selected inside the folded Resolved section
- Select Opened Thread selects the thread on the caret's line; on a line without a thread it selects the file

## Manual

1. In a long walkthrough, open a deep step from the Margin tab and press *Select in Marginalis* → the tool window
   comes up with the folders down to the step unfolded and the step selected, scrolled into view.
2. With the tool window filtered (e.g. *Blockers Only*), select a thread the filter hides → the filter resets to *All* and
   the thread is selected.

## On main

Neither button exists: all four cases fail.
