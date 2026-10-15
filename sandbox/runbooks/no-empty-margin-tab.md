# The Margin tab shows only when there is something in it

- **Key:** MARG-48 (schrek1/marginalis#48)
- **Tags:** regression
- **Scenario:** `no_empty_margin_tab.py`

## Protects

- A project without project threads opens without the Margin tab, the first time too.
- The agent's first project thread opens the tab, unpinned.
- A tab left open with nothing in it does not come back after a restart.
- A project that already has an open project thread opens with the tab, unpinned.

## Automated

`py sandbox/scenarios/no_empty_margin_tab.py`

- a fresh project without project threads opens without the Margin tab
- the agent's first project thread opens the Margin tab
- the Margin tab opened by a thread is not pinned
- the resolved thread leaves the Margin tab open and empty (precondition)
- a restart does not bring back an empty Margin tab
- a project with an open project thread opens with the Margin tab
- that Margin tab is not pinned

## Manual

1. Pin the Margin tab while it has an open project thread, then restart the IDE → the tab comes back pinned.
2. Close the Margin tab while it has an open project thread, then restart the IDE → it stays closed until the
   agent posts or you open a project thread.
3. On a dev build, with the tab open and an open project thread, update the plugin without a restart → the tab
   comes back, pinned only if it was pinned.

## On main

On schreko_stable 7fe12c5, 4 of 7 fail: the fresh project opens with the tab, pinned; the first thread lands in
that pinned tab; the empty tab comes back after the restart; the first open of a project with a thread pins the
tab.
