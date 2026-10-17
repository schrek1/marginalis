# The Margin tab reads oldest first, with a Sort menu per project

- **Key:** MARG-27 (schrek1/marginalis#27)
- **Scenario:** `ProjectTabOrderTest`

## Protects

- The Margin tab lists the oldest thread first, and a reply does not move a thread. Walkthrough steps read
  1, 2, 3.
- The Sort menu switches to Newest first or Recent activity, and each project remembers its choice.
- A new thread's draft sits on the side where the chosen order puts new threads.

## Automated

`./gradlew uiSandboxTest --tests '*ProjectTabOrderTest'`

- by default the tab lists the oldest thread first, and a reply does not move it
- project-level steps posted as 3, 1, 2 read 1, 2, 3
- the Sort menu stores Newest first for the project
- newest first lists the latest created thread first, steps still 1, 2, 3
- recent activity puts the thread with the latest reply first
- with oldest first a new thread's draft sits below the threads
- with newest first the draft sits above the threads
- the project's Sort choice survives a restart
- the choice lives in `workspace.xml`

## Manual

1. Open the Sort menu in the Margin tab → it lists the three orders, with the current one ticked.
2. Pick Newest first and restart the IDE → the tab still reads newest first, and another project still reads
   oldest first.

## On main

Not recorded yet.
