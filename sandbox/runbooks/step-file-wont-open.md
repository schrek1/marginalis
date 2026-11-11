# A step whose file's preview fails opens as plain text

- **Keys:** MARG-256 (schrek1/marginalis#256), MARG-258 (schrek1/marginalis#258)
- **Scenario:** `StepFileWontOpenTest`

## Protects

- Next Step into a file the IDE closes as it opens it, because its preview fails to build (an OpenAPI spec whose
  Swagger preview crashes in JCEF), opens the step in that file shown as plain text alone, folds the step you leave,
  and shows no balloon. The file keeps opening as plain text until the IDE restarts.
- Opening such a step from the tool window (click, Navigate, the header's step arrows) opens it as plain text too.
- A file the IDE closes even as plain text leaves the step you are on open, and a balloon names the step and its
  file; that step's thread never opens in another file's editor. The same balloon comes from the tool window.
- Next Step into a file that opens still folds the step you leave, once the next one shows.

## Automated

`./gradlew uiSandboxTest --tests '*StepFileWontOpenTest'`

- harness: the failing preview closed the spec once
- step 2 opens in the spec's tab as plain text
- step 1 is folded once step 2 shows
- no balloon when the step opens as plain text
- harness: the failing preview closed the other spec once
- a step opened from the tool window shows in the other spec as plain text
- no balloon for a step opened from the tool window as plain text
- harness: the IDE closed the spec as it opened it
- step 1 stays open where the user is
- step 2 does not open in greeting.py
- a balloon names the step and its file
- a step opened from the tool window into a file that closes names the step and its file
- step 2 opens in the spec's tab
- step 1 is folded once step 2 shows
- no balloon when the move succeeds

The sandbox IDE has no Swagger preview. `SandboxUi.previewFailsOn` closes the spec right after it opens whenever any
editor besides Marginalis's text-only one was built for it, as the IDE does when the preview fails; a plain-text open
stays. `SandboxUi.closeOnOpen` closes the spec however it opens.

## Manual

1. In IntelliJ IDEA Ultimate with the OpenAPI plugin, walk into a step in an OpenAPI spec whose preview fails →
   the spec opens as plain text with the step shown, and the step you left folds.

## Without the fix

Without the text-only reopen (MARG-256 alone), the preview cases end in the balloon: "step 2 opens in the spec's tab
as plain text", "step 1 is folded once step 2 shows" (first case), "a step opened from the tool window shows in the
other spec as plain text" and both "no balloon … as plain text" checks fail; the harness checks and the later cases
pass.
