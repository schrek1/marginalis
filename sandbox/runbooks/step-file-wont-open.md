# Next stays put when the step's file won't open

- **Key:** MARG-256 (schrek1/marginalis#256)
- **Scenario:** `StepFileWontOpenTest`

## Protects

- Next Step into a file the IDE closes as it opens it (an OpenAPI spec whose Swagger preview crashes in JCEF) leaves
  the step you are on open, and a balloon names the step and its file.
- That step's thread never opens in another file's editor.
- Opening such a step from the tool window (click, Navigate, the header's step arrows) gives the same balloon.
- Next Step into a file that opens still folds the step you leave, once the next one shows.

## Automated

`./gradlew uiSandboxTest --tests '*StepFileWontOpenTest'`

- harness: the IDE closed the spec as it opened it
- step 1 stays open where the user is
- step 2 does not open in greeting.py
- a balloon names the step and its file
- a step opened from the tool window into a file that closes names the step and its file
- step 2 opens in the spec's tab
- step 1 is folded once step 2 shows
- no balloon when the move succeeds

The sandbox IDE has no Swagger preview, so `SandboxUi.closeOnOpen` closes the spec right after it opens, as the IDE
does when that preview fails to build.

## Manual

1. In IntelliJ IDEA Ultimate with the OpenAPI plugin, walk into a step in an OpenAPI spec whose preview fails →
   the step you are on stays open and a balloon names the step and the spec.

## On main

The step you leave folds before the move, so "step 1 stays open" fails, and nothing reports the failed move, so the
balloon checks fail. "step 2 does not open in greeting.py" passes without the fix as well: the sandbox's close comes
after the old code read the selected editor.
