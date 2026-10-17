# What you left in the margin survives an IDE restart

- **Key:** MARG-105 (schrek1/marginalis#105)
- **Scenario:** `ThreadsSurviveRestartTest` (tagged `core`)

## Protects

- Line, file and project threads come back after a real stop and start of the IDE, each with its status and its
  messages, your replies included; a resolved thread stays resolved.
- What you had read stays read, and a message you had not read is still marked new.
- The project's own settings stay: the Margin tab's Sort, Live by default, the Guided layout.
- After the start the markers sit on their lines, and the tool window and the Margin tab hold the same threads.

## Automated

`./gradlew uiSandboxTest --tests '*ThreadsSurviveRestartTest'` (or `-Pmarginalis.sandbox.tags=core`). The first case
leaves five threads (a line thread with your reply, a file thread, a project thread you read, a resolved one, one
unread) and the three settings, and checks that state; the second restarts the IDE through
`Sandbox.restart(fresh = false)` and checks every part of it again, every check must PASS.

## Manual

1. Leave threads of each kind, reply to one, resolve one, read some; switch Sort to Newest first, Live by default
   on, Guided layout By file.
2. Quit the IDE and start it again → the same threads, read state and settings; the markers on their lines.

## On main

The behaviour is upstream's own, so there is no fix to compare against. A restart onto a fresh project (the
mutation that drops everything) fails seven of the checks.
