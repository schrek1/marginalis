# A walkthrough in a review diff stays in that diff

- **Key:** MARG-17 (schrek1/marginalis#17)
- **Tags:** regression
- **Scenario:** `review_diff_walkthrough.py`

## Protects

- Following a guided walkthrough in a pull/merge request review diff, Next Step, Previous Step and Resolve keep
  the user in the diff:
  - a step in another file of the review switches the diff to that file, scrolls to the step and opens its thread;
  - a step in the same file scrolls the diff to it, also when the diff shows a revision copy (the review branch is
    not checked out).
- A step the diff cannot show still opens in the file's own tab: a file outside the review, or a line the review
  removed.

## Automated

`py sandbox/scenarios/review_diff_walkthrough.py` (until the branch carries the in-repo harness:
`PYTHONPATH=D:/OSOBNI/programming/marginalis-MARG-29/sandbox py sandbox/scenarios/review_diff_walkthrough.py
--feature-dir D:/OSOBNI/programming/marginalis-MARG-17`).

The sandbox has no GitHub/GitLab review, so `review_diff_ui.py` builds the same kind of diff from the platform:
a `MutableDiffRequestProcessor` (the processor both review diffs run on) listing `src/review_target.py` and
`src/greeting.py` as revision copies, in an editor tab, switching files asynchronously. The two `harness:` checks
fail, and nothing else runs, when that diff cannot be built — the scenario never passes on a different diff.

- harness: the review diff runs on MutableDiffRequestProcessor; it shows its first file
- same file, revision copy (Next Step) — the review diff stays in front; the diff shows the step's file; the
  step's thread opens in the diff; the diff scrolls to the step's line in the review's text
- next, another file of the review (Next Step) — the same four checks
- previous, back to the first file (Previous Step) — the same four checks
- resolve, another file of the review (Resolve with auto-advance) — the same four checks
- anchor not in the review's text — the step opens in the file's own tab; its thread opens there
- file outside the review — the same two checks

## Manual

1. In your own IDE (dev build), open a GitLab merge request or GitHub pull request in its review diff, combined
   diff off, with the branch not checked out, and a walkthrough whose steps span two of its files → Next Step and
   Resolve switch the diff to the other file and open the step there; Previous Step comes back.
2. Same with the branch checked out → the same behaviour.
3. A step in a file the review does not change → opens in that file's editor tab.
4. Combined diff mode on → steps open in file tabs, as before (not covered).
5. If a step in another file switches the diff but its thread does not open: save `idea.log` (look for "Review
   diff file list unavailable") and attach it to MARG-17.

## On main

Expected to fail on `main` and on the base this branch builds on (#1 + #3): every case that should stay in the
diff (16 checks) opens a file tab there instead, because #1 matches only a diff whose document is the local file
and nothing switches the diff's file. The two file-tab cases and the harness checks pass on `main` too: they guard
the fallback.
