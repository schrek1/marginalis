# Marginalis sandbox IDE

A second IntelliJ instance that runs Marginalis built straight from a worktree, next to Ondra's everyday IDE.
Two uses:

- **The scenario suite** tests every feature end to end: the user's side through the real Swing components, the
  agent's side through the plugin's REST API. It is the Kotlin suite in
  `src/uiTest/kotlin/dev/marginalis/plugin/sandbox/`, run by `./gradlew uiSandboxTest`; `docs/testing.md` is its
  guide.
- **The manual tool** in this folder (`sandbox.py`, `sandbox_ui.py`, `robot.py`) starts one sandbox for a demo or
  hands-on work: Ondra tries a feature without reinstalling dev/stable builds in his own IDE, or an agent works
  with a running sandbox by hand.

The harness is versioned with the code it tests: a feature branch adds its own scenario class and its runbook
(`runbooks/`). The runtime state is not versioned. It lives machine-local in `marginalis-sandbox-runtime/` next to
the checkout, shared by every worktree of the clone (`ide/` = sandbox config/system/plugins, `project/` = the test
project, `run.log`; override `MARGINALIS_SANDBOX_RUNTIME`).

## Running the scenarios

```
./gradlew uiSandboxTest                                         # every scenario, 6 sandboxes in Docker
./gradlew uiSandboxTest --tests '*EnvelopeOnlyWhileUnreadTest'  # one scenario class
./gradlew uiSandboxTest --tests '*StablePrsTest.zoom'           # one case of a class
```

How to add a scenario, the measured settings and the troubleshooting notes are in `docs/testing.md`.

## The manual tool

### Prerequisites (per machine)

- The checkout under test: by default the one the harness sits in (`--feature-dir` or `MARGINALIS_DIR` for
  another).
- IntelliJ IDEA in `%LOCALAPPDATA%\Programs\IntelliJ IDEA Ultimate` (used as the sandbox's platform).
- JDK 21 in `~/.jdks/corretto-21*` or `C:\Program Files\Java\jdk-21*`.
- Python 3.12, standard library only. On the laptop `python` resolves to 3.10 (system PATH wins) — run the
  scripts with `py` there.
- PowerShell 7 (`pwsh`) with the `VirtualDesktop` module (`Install-Module VirtualDesktop -Scope CurrentUser`).

Docker mode needs only Docker and Python.

### Commands

```
python sandbox.py start [--feature-dir PATH] [--fresh]   # build from PATH, start the IDE, wait until it answers
python sandbox.py status
python sandbox.py stop                                   # exits as a user would; killed only if not out in 60 s
python sandbox.py clean                                  # Docker: drop this port's container and its volumes
python sandbox.py locked <command...>                    # run a one-off experiment under the machine lock
python robot.py js "<rhino script>" [--edt]              # run JS inside the sandbox JVM, print its String result
python robot.py find "<xpath>"                           # components in the robot's hierarchy
python robot.py screenshot out.png --frame               # the sandbox frame, painted off-screen
```

- `start` runs `gradlew runIdeForUiTests` in the feature dir with `--init-script init.gradle`, so the repo's build
  stays untouched. First start ~2 min, later ~1 min. `--fresh` recopies `project-template/` (wipes the project's
  `.idea`, i.e. its workspace.xml and threads).
- The sandbox's REST port is found by `ping`ing 63342–63361 for the one whose open project is the sandbox
  project. No port is fixed: each JetBrains IDE takes the next free one in start order (on the laptop DataGrip
  holds 63342 and Ondra's IntelliJ 63343). Remote Robot listens on 8082 (`MARGINALIS_ROBOT_PORT`).
- Ondra can use the running sandbox by hand like any IDE window. A script drives it with the steps in
  `sandbox_ui.py` (agent REST calls, user UI steps, store reads), the same JavaScript the Kotlin suite runs.
- `start` moves the sandbox window to virtual desktop 3 ("diy") as soon as it appears (`move_to_desktop.ps1`,
  PowerShell module `VirtualDesktop`), so restarts do not flash on his working desktop. For a demo he switches
  to that desktop. `MARGINALIS_SANDBOX_DESKTOP=none` keeps the window where it opens.
- `locked` waits for the machine-wide sandbox run lock `marginalis-sandbox-runs/.lock` next to the checkout, the
  one `./gradlew uiSandboxTest` takes, holds it while the command runs and then releases it, but only while it is
  still its own. The lock ends with the command, so wrap the whole experiment (start, work, stop):
  `py sandbox/sandbox.py locked py my_experiment.py`. Never create or delete the lock by hand.

### Docker mode (any OS, a fixed screen)

UI work depends on the window size: a sleeping monitor makes Windows report 640x480 and clamps the frame until
thread panels get no width. In Docker the sandbox IDE runs on a virtual 2560x1440 screen (Xvfb). The prebuilt
sandboxes run JetBrains' open-source IntelliJ IDEA of the latest release (`marginalis.latestIde` in
`gradle.properties`, passed to `Dockerfile` as `SANDBOX_IDE`, a layer of the image, from the
JetBrains/intellij-community releases), the platform in daily use, not the plugin's 2025.2 floor that the build
compiles and verifies against: up to 2025.3 the IDE runs on JBR 21, where a platform thread can keep any plugin's
class loader, so an update without a restart cannot be tested there. The open-source build has no licensing: the
unified distribution asked for a subscription on CI runners and blocked the scenarios. Run mode (the IDE through
Gradle) still runs the floor.

- `MARGINALIS_SANDBOX_DOCKER=1 python sandbox.py start` builds the image from `Dockerfile` (Xvfb, JDK 25) and runs
  container `marginalis-sandbox-<robot port>` with the checkout mounted read-only. The robot port is published as
  is, the IDE's REST port as `robot port + 10000`, clear of the host's IDEs on 63342+. Build output, IDE config and
  the Gradle home stay in per-container Docker volumes. The IDE works on a copy of the test project on the container's
  disk; the host folder `marginalis-sandbox-<robot port>/project` is the copy prepared before a start and read back
  after a stop. Containers on different robot ports run side by side.
- **One build, many sandboxes:** `MARGINALIS_SANDBOX_DOCKER=1 MARGINALIS_SANDBOX_PREBUILT=<folder> python
  sandbox.py build` builds the checkout once with this machine's Gradle (`init.gradle`, so any checkout works) and
  leaves its plugins (the distribution's Marginalis and robot-server) in the folder. A sandbox started with the same
  `MARGINALIS_SANDBOX_PREBUILT` runs the image's IDE straight from them, without Gradle: it is up in about 10 s
  instead of a minute. `./gradlew uiSandboxTest` does the same through `sandboxPrebuilt`.
- **Ports:** robot 8082 by default, REST robot + 10000. Both stay below Windows' dynamic port range (49152 and up),
  where services take ports at random. The Kotlin suite claims 8100 and up, clear of these.
- **Linux / CI without Docker:** install `xvfb` and JDK 25, then `sandbox/run-ide-xvfb.sh` with
  `MARGINALIS_SANDBOX_RUNTIME` and `MARGINALIS_ROBOT_PORT` set (the suite does this with
  `MARGINALIS_SANDBOX_LAUNCH=xvfb`).

## Files

| file | what |
|---|---|
| `ui-ide.gradle` | registers `runIdeForUiTests`: shared sandbox dir, robot-server plugin 0.11.23, first-run dialogs off, opens the test project; `build.gradle.kts` applies it |
| `init.gradle` | applies `ui-ide.gradle` to a checkout whose build lacks it (an upstream-ready feature branch) |
| `sandbox.py` | start / stop / status / clean / build, port discovery, `locked` (the machine lock) |
| `robot.py` | minimal HTTP client for robot-server: `call_js`, `run_js`, `find_all`, `screenshot` |
| `sandbox_ui.py` | Marginalis steps: agent REST calls (`agent_starts_project_thread`, `agent_waits`), user UI steps (`user_replies`, `press_tool_window_action`, `toggle_thread_live`), store reads (`is_live`, `live_by_default`, `message_bodies`) |
| `Dockerfile`, `docker-entrypoint.sh` | the sandbox image, for this tool and the Kotlin suite alike |
| `run-ide-xvfb.sh` | the sandbox IDE on a virtual screen without Docker |
| `move_to_desktop.ps1` | moves the native sandbox window to its virtual desktop |
| `runbooks/` | one file per feature: what it protects, its Kotlin scenario, the manual checks |
| `fixtures/` | larger thread sets to seed a demo, e.g. a review-sized walkthrough (the suite's port: `fixtures/Walkthroughs.kt`) |
| `project-template/` | the test project copied on `--fresh` |

## Lessons learned

The generic rules (no OS input, hover-only header actions, forced saves before stop, Rhino class loading,
`retrieveAny` decoding, painted screenshots) live in the `intellij-plugin-sandbox-testing` skill — read it
before changing `robot.py`, `sandbox_ui.py` or `sandbox.py`. Marginalis-specific:

- robot-server 0.11.23 (the latest, May 2024) works with IntelliJ 2026.2. The open-source build has no license
  dialog; the unified distribution opened "Manage Subscriptions" on CI runners, though not locally.
- Marginalis REST: `project` is the project's *name* (`project`), not its path; `comment_wait` returns at once
  with `reason: "live"` on a live wake, so a "nothing wakes" check is a short `timeout` (6 s).
