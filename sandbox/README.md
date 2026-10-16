# Marginalis sandbox IDE

A second IntelliJ instance that runs Marginalis built straight from a worktree, next to Ondra's everyday IDE.
Two uses:

- **Ondra tries a feature** without reinstalling dev/stable builds in his own IDE.
- **An agent tests a feature end to end**: the user's side through the real Swing components, the agent's side
  through the plugin's REST API.

The harness is versioned with the code it tests: every checkout carries the scenarios for what it has, and a
feature branch adds its own scenario and its runbook (`runbooks/`). The runtime state is not versioned. It lives
machine-local in `marginalis-sandbox-runtime/` next to the checkout, shared by every worktree of the clone
(`ide/` = sandbox config/system/plugins, `project/` = the test project, `run.log`, `scenario.log`; override
`MARGINALIS_SANDBOX_RUNTIME`).

## Prerequisites (per machine)

- The checkout under test: by default the one the harness sits in (`--feature-dir` or `MARGINALIS_DIR` for
  another).
- IntelliJ IDEA in `%LOCALAPPDATA%\Programs\IntelliJ IDEA Ultimate` (used as the sandbox's platform).
- JDK 21 in `~/.jdks/corretto-21*` or `C:\Program Files\Java\jdk-21*`.
- Python 3.12, standard library only. On the laptop `python` resolves to 3.10 (system PATH wins) — run the
  scripts with `py` there.
- PowerShell 7 (`pwsh`) with the `VirtualDesktop` module (`Install-Module VirtualDesktop -Scope CurrentUser`).

## Commands

```
python sandbox.py start [--feature-dir PATH] [--fresh]   # build from PATH, start the IDE, wait until it answers
python sandbox.py status
python sandbox.py stop                                   # graceful exit (flushes workspace.xml), then kill
python robot.py js "<rhino script>" [--edt]              # run JS inside the sandbox JVM, print its String result
python robot.py find "<xpath>"                           # components in the robot's hierarchy
python robot.py screenshot out.png --frame               # the sandbox frame, painted off-screen
python scenarios/api_reply_rerenders.py                 # an end-to-end scenario: PASS/FAIL per check
python run_all.py [--tag smoke]                          # regression (every scenario) or smoke
```

- `start` runs `gradlew runIdeForUiTests` in the feature dir with `--init-script init.gradle`, so the repo's build
  stays untouched. First start ~2 min, later ~1 min. `--fresh` recopies `project-template/` (wipes the project's
  `.idea`, i.e. its workspace.xml and threads).
- The sandbox's REST port is found by `ping`ing 63342–63361 for the one whose open project is the sandbox
  project. No port is fixed: each JetBrains IDE takes the next free one in start order (on the laptop DataGrip
  holds 63342 and Ondra's IntelliJ 63343). Remote Robot listens on 8082 (`MARGINALIS_ROBOT_PORT`).
- Ondra can use the running sandbox by hand like any IDE window.
- `start` moves the sandbox window to virtual desktop 3 ("diy") as soon as it appears (`move_to_desktop.ps1`,
  PowerShell module `VirtualDesktop`), so restarts do not flash on his working desktop. For a demo he switches
  to that desktop. `MARGINALIS_SANDBOX_DESKTOP=none` keeps the window where it opens.

## Headless screen (night runs, CI, any OS)

UI scenarios depend on the window size: a sleeping monitor makes Windows report 640x480 and clamps the frame
until thread panels get no width. `run-ide-xvfb.sh` runs the sandbox IDE on a virtual 2560x1440 screen (Xvfb),
with the floor IDE (IntelliJ Community from the build) as CI uses it.

- **Any OS with Docker:** `MARGINALIS_SANDBOX_DOCKER=1 python sandbox.py start` builds the image from
  `Dockerfile` (Xvfb, JDK 25) and runs container `marginalis-sandbox-<robot port>` with the checkout mounted
  read-only. The robot port is published as is, the IDE's REST port as `robot port + 10000`, clear of
  the host's IDEs on 63342+. Build output, IDE config and the Gradle home stay in per-container Docker volumes
  (a new Gradle home starts as a copy of volume `marginalis-gradle`); the test project stays on the host in
  `marginalis-sandbox-<robot port>/project`. Containers on different robot ports run side by side.
- **One build, many sandboxes:** `MARGINALIS_SANDBOX_DOCKER=1 MARGINALIS_SANDBOX_PREBUILT=<folder> python
  sandbox.py build` builds the checkout once and leaves its plugins (Marginalis and robot-server) in the folder. A
  sandbox started with the same `MARGINALIS_SANDBOX_PREBUILT` runs the IDE straight from them, without Gradle: it is
  up in about 10 s instead of a minute, and parallel sandboxes no longer build at the same time. The stable gate
  does this on its own in Docker mode.
- **Ports:** robot 8082 and up, REST robot + 10000 (18082 and up). Both stay below Windows' dynamic port range
  (49152 and up), where services take ports at random.
- **Linux / CI without Docker:** install `xvfb` and JDK 25, then `sandbox/run-ide-xvfb.sh` with
  `MARGINALIS_SANDBOX_RUNTIME` and `MARGINALIS_ROBOT_PORT` set.

## Files

| file | what |
|---|---|
| `init.gradle` | registers `runIdeForUiTests`: shared sandbox dir, robot-server plugin 0.11.23, first-run dialogs off, opens the test project |
| `sandbox.py` | start / stop / status, port discovery |
| `robot.py` | minimal HTTP client for robot-server: `call_js`, `run_js`, `find_all`, `screenshot` |
| `sandbox_ui.py` | Marginalis steps: agent REST calls (`agent_starts_project_thread`, `agent_waits`), user UI steps (`user_replies`, `press_tool_window_action`, `toggle_thread_live`), store reads (`is_live`, `live_by_default`, `message_bodies`) |
| `scenarios/` | one file per feature; each restarts the sandbox as it needs |
| `runbooks/` | one file per feature: what it protects, its scenario, the manual checks; tags for smoke and regression |
| `run_all.py` | every scenario (`--tag smoke`: the smoke runbooks' scenarios), with a Markdown summary for the gate |
| `fixtures/` | larger thread sets a scenario seeds, e.g. a review-sized walkthrough |
| `project-template/` | the test project copied on `--fresh` |

## Lessons learned

The generic rules (no OS input, hover-only header actions, forced saves before stop, Rhino class loading,
`retrieveAny` decoding, painted screenshots) live in the `intellij-plugin-sandbox-testing` skill — read it
before changing `robot.py`, `sandbox_ui.py` or `sandbox.py`. Marginalis-specific:

- robot-server 0.11.23 (the latest, May 2024) works with IntelliJ 2026.2.3; the sandbox runs in free mode
  ("Unlock Ultimate"), no license dialog.
- Marginalis REST: `project` is the project's *name* (`project`), not its path; `comment_wait` returns at once
  with `reason: "live"` on a live wake, so a "nothing wakes" check is a short `timeout` (6 s).

## Adding a scenario

Write a runbook for the feature (`runbooks/README.md`), then copy `scenarios/api_reply_rerenders.py`: a `Scenarios` class whose methods are GIVEN/WHEN/THEN in plain calls to
`sandbox_ui`, `check(name, passed, detail)` per behaviour, and `restart(fresh=...)` where a scenario needs a new
project or a persisted state. Add missing steps to `sandbox_ui.py` rather than inline JS in the scenario.
