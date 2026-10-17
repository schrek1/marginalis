# Live by default

- **Key:** MARG-22 (schrek1/marginalis#22)
- **Scenario:** `LiveByDefaultTest`

## Protects

- The ⚡ toggle in the tool window's toolbar makes every open thread of the project live.
- A thread's own ⚡ still switches it off, and flipping the project default clears that override.
- The Settings checkbox sets where a new project starts. Changing it leaves existing projects alone.
- The project's choice survives a restart and lives in `workspace.xml`, not in version control.

## Automated

`./gradlew uiSandboxTest --tests '*LiveByDefaultTest'`

- without the default a reply does not wake the agent
- the toolbar switches the project's default on
- every open thread is live
- a reply in any thread wakes the agent with that thread
- a thread's own switch turns it off under the default
- a thread switched off stays quiet
- flipping the default clears the thread's override
- a new project takes the Settings default
- changing Settings leaves an existing project alone
- the project's own choice survives a restart
- the choice lives in `workspace.xml`

## Manual

1. With a real agent session waiting in `comment_wait`, switch ⚡ on in the toolbar and reply in any thread →
   the agent wakes with that thread.
2. Compare the toolbar's ⚡ with a thread header's ⚡, in both themes → they agree.

## On main

Not recorded yet.
