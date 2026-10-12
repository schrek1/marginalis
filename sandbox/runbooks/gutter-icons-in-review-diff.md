# Thread icons in pull request review diffs

- **Key:** MARG-20 (schrek1/marginalis#20)
- **Tags:** smoke, regression
- **Scenario:** `stable_prs.py` --only review_diff_icons

## Protects

- A review diff, or any diff that shows a file from a commit, shows thread icons in the gutter on the threads'
  lines in that version of the file.
- An added file's one-sided diff shows them too.
- Clicking an icon opens the thread in the diff.

## Automated

`py sandbox/scenarios/stable_prs.py --only review_diff_icons`

- icons sit on the threads' lines in the PR text
- an added file's one-sided diff shows icons too

## Manual

1. Open the review diff of a real GitHub pull request with threads on changed lines → the icons sit on those
   lines, not shifted by the lines the pull request added above them.
2. Click an icon → the thread opens inside the diff.

## On main

Not recorded yet.
