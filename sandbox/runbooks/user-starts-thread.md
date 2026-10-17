# You start a thread on a line, a selection or a whole file

- **Key:** MARG-106 (schrek1/marginalis#106)
- **Scenario:** `UserStartsThreadTest` (tagged `core`)

## Protects

- "Add Marginalis Comment" on the caret's line opens a draft; after Submit the agent's `comment_list` has the thread
  on that line, with the line as its anchor text, written by you.
- The same action on a selection anchors the thread on the selection's line and keeps the selected words as its
  segment.
- "Comment on File" starts a thread about the whole file: no line.

## Automated

`./gradlew uiSandboxTest --tests '*UserStartsThreadTest'` (or `-Pmarginalis.sandbox.tags=core`). Each case runs the
plugin's editor action as its context menu does, submits the draft it opens, and reads the thread back through the
agent's `comment_list`; every check must PASS.

## Manual

1. Put the caret on a line, Add Marginalis Comment (alt shift M or the editor's context menu), write, Submit → the
   thread sits on that line.
2. Select a few words, the same action → the thread shows the selection as its quote.
3. Comment on File from the context menu → the thread sits above the first line, about the file.

## On main

The behaviour is upstream's own. An action that ignores the selection fails "the selected words are its segment".
