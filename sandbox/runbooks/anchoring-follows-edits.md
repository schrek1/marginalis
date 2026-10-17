# A thread follows its code while you edit

- **Key:** MARG-107 (schrek1/marginalis#107)
- **Scenario:** `AnchoringFollowsEditsTest` (tagged `core`)

## Protects

- Lines inserted above a thread move its marker with its line, and the agent lists it on the moved line, open.
- Erasing the anchored line orphans the thread, and no marker stays behind.
- `comment_reanchor` brings an orphan back on the line the agent names, with the marker there.
- After a rewrite of the whole file, `comment_reanchor_all` finds each thread's code where it moved; a thread whose
  code is gone stays orphaned.

## Automated

`./gradlew uiSandboxTest --tests '*AnchoringFollowsEditsTest'` (or `-Pmarginalis.sandbox.tags=core`). The cases edit
`src/long_module.py` through the editor's document, as typing does, and check the marker in the editor and the
thread in the agent's `comment_list`; every check must PASS.

Known gap, only with `MARGINALIS_SANDBOX_KNOWN_GAPS=true`: Delete Line on the anchored line moves the thread onto
the next line instead of orphaning it (upstream #53).

## Manual

1. Let the agent post a thread, then type lines above it → the marker moves with its line.
2. Erase the line (select it from the end of the line above and delete) → the thread shows as orphaned; ask the
   agent to rescue it → it is back on the line it names.
3. Rewrite the file with a tool, the anchored lines moved → `comment_reanchor_all` puts the threads back.

## On main

The behaviour is upstream's own. A `comment_reanchor_all` that searches only near the old line fails the rescue
checks.
