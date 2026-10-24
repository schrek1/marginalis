# A whole project live

- **Key:** MARG-22 (schrek1/marginalis#22), upstream's implementation since 0.2.5
- **Scenario:** `LiveByDefaultTest`

## Protects

- The ⚡ toggle in the tool window's toolbar ("Make every open thread live") makes every open thread of the project
  live; it is disabled while no agent listens, and going live hands the listening agent a reply it missed.
- A thread's own ⚡ still switches it off, and turning the project off and on again clears that switch.
- A project follows the Settings default until it makes its own choice with the toggle.
- The project's choice survives a restart and lives in `workspace.xml`, not in version control.

## Automated

`./gradlew uiSandboxTest --tests '*LiveByDefaultTest'`

- with no agent listening the toggle is disabled
- without it a reply does not wake the agent
- the toolbar makes the project live while an agent listens
- every open thread is live
- going live hands the listening agent the reply it missed
- a reply in any thread wakes the agent with that thread
- a thread's own switch turns it off under the default
- a thread switched off stays quiet
- turning the project off and on again clears the thread's own switch
- a new project follows the Settings default
- a project without its own choice follows a change in Settings
- the toolbar makes the project live
- the project's own choice survives a restart, over the Settings default
- the choice lives in `workspace.xml`

## Manual

1. With a real agent session waiting in `comment_wait`, switch ⚡ on in the toolbar and reply in any thread →
   the agent wakes with that thread.
2. Compare the toolbar's ⚡ with a thread header's ⚡, in both themes → they agree.

## On main

Not recorded yet.
