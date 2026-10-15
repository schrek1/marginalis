# Select Opened Thread works with a review diff in front

- **Key:** MARG-60 (schrek1/marginalis#60)
- **Tags:** regression
- **Scenario:** `select_opened_thread_in_review_diff.py`

## Protects

- With a review diff in front, *Select Opened Thread* in the tool window header is enabled, not greyed out.
- It selects the thread on the caret's line in the review's text, or the diff's file when that line has none.

## Automated

`py sandbox/scenarios/select_opened_thread_in_review_diff.py` (from a checkout without the harness:
`PYTHONPATH=<harness>/sandbox py sandbox/scenarios/select_opened_thread_in_review_diff.py --feature-dir <checkout>`)

A review diff of `review_target.py` whose copy moves every line down by three, and one step on `total`:

- the review diff is in front
- Select Opened Thread is enabled with a review diff in front
- on the line of a thread in the review's text it selects that thread
- on a line without a thread it selects the file

## Manual

1. Open a merge request's diff from the IDE's code review, put the caret on a line with a thread icon and press
   *Select Opened Thread* → the tool window selects that thread.
2. In a local diff (e.g. *Show Diff* on a changed file), the same works on the side showing the working copy.

## Without the change

On `schreko_stable` ae2dfad, 3 of 5 fail: the button is greyed out, and pressing it anyway leaves the selection where
it was. With the change all 5 pass.
