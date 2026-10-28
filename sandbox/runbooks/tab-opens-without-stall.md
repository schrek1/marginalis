# Opening a project no longer freezes the IDE while the Margin tab comes back

- **Key:** MARG-88 (schrek1/marginalis#88)
- **Scenario:** `TabOpensWithoutStallTest`

## Protects

- When the Margin tab comes back at startup or after a plugin update while the IDE is busy (a write pending holds
  back the editors' checks), the IDE stays responsive: the tab appears at once and its content follows when the
  checks finish.
- The tab comes back pinned if it was, behind the file you were reading.

## Automated

`./gradlew uiSandboxTest --tests '*TabOpensWithoutStallTest'`

- opening the tab returns while the editor checks are still held back
- opening the tab holds the EDT for less than a second
- the tab is back once the checks finish
- the tab is pinned again
- the file you were reading stays in front

## Manual

1. With "Show the Margin tab when a project opens" on and the Margin tab pinned, restart the IDE on a project that
   is still syncing (e.g. a Gradle project after a clean) → the editor answers right away; the Margin tab shows its
   threads once the project settles.

## On main

Without the fix the first two checks fail: opening the tab holds the EDT until the held-back check gives up
(the scenario holds it for 8 s; a pending write held it for the platform's 30 s).
