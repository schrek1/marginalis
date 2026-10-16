# Testing Marginalis

How to check a change, for a person or an agent starting on the repository. Three layers, fastest first. A change
is ready for review when the layers it touches pass, and the whole sandbox set passes before it goes into a release.

| Layer | What it covers | Command | Time |
|---|---|---|---|
| Core | model, lifecycle, walks, anchoring, codec (`core/`, pure Kotlin) | `./gradlew :core:test` | seconds |
| Platform (headless) | plugin code against a real in-memory IDE: store, transport, tool window models, actions | `./gradlew test` | about a minute |
| Sandbox scenarios | the real IDE, driven through its Swing components and the agent API | `./gradlew uiSandboxTest` (Docker, 6 sandboxes) | about 4 min |

Run the platform tests against the build's own IDE (IntelliJ Community 2025.2, the platform floor). Do not pass
`-Pmarginalis.localIde` for them: a local Ultimate ships obfuscated classes and every test fails.

## Sandbox scenarios

A scenario starts a sandbox IDE with the plugin built from the checkout, plays the user's side through the real
components (no OS mouse or keyboard, ever) and the agent's side through the REST API, and prints `PASS` / `FAIL`
per check. Each feature has one scenario, a JUnit class on Remote-Robot in
`src/uiTest/kotlin/dev/marginalis/plugin/sandbox/scenarios/`, and a runbook in `sandbox/runbooks/`. Gradle runs
them as `uiSandboxTest`.

### Running the suite

Docker gives every sandbox a virtual 2560x1440 screen, so scenarios behave the same on Windows, macOS, Linux and in
CI, whatever the monitor does, and nothing appears on your desktop. The IDE inside is the platform floor.

- **Needs:** Docker (Desktop on Windows and macOS, Engine on Linux). On Windows give WSL2 room for several IDEs in
  `%USERPROFILE%\.wslconfig`, then `wsl --shutdown` and start Docker again; memory is taken only while used:
  ```
  [wsl2]
  memory=48GB

  [experimental]
  autoMemoryReclaim=gradual
  ```
- **First run on a machine** builds the image and downloads the IDE (about 1.5 GB) into Docker volumes: a few
  minutes, once.
- **The whole set before a PR:** `./gradlew uiSandboxTest`. It builds the checkout once (`sandboxPrebuilt`), then
  runs six sandboxes side by side, the scenario classes longest first; `-Pmarginalis.sandbox.jobs=N` changes the
  count.
- **One scenario while you work:** `./gradlew uiSandboxTest --tests '*EnvelopeOnlyWhileUnreadTest'`.
- **One case of a scenario:** `./gradlew uiSandboxTest --tests '*StablePrsTest.zoom'`. A case may build on the
  threads earlier cases of its class posted, so run one alone only where the class says it stands alone, as
  `StablePrsTest` does.
- **Where things land:** `marginalis-sandbox-runtime/` next to the checkout (`MARGINALIS_SANDBOX_RUNTIME` moves
  it). Per scenario, `<scenario>/run.log` holds the IDE's log, `kotlin-logs/<scenario>.log` the `PASS` / `FAIL`
  lines, `shots/<scenario>/` the shots, and on a timeout `<scenario>/diagnostics/` gets a frame shot while the
  thread dump goes into `run.log`. The failure message names both.
- **Known gaps** (checks that pin an open defect) run only with `MARGINALIS_SANDBOX_KNOWN_GAPS=true`.
- **Without Docker:** `MARGINALIS_SANDBOX_LAUNCH=xvfb` runs the IDE through `sandbox/run-ide-xvfb.sh` on this
  machine (Linux with Xvfb and JDK 25), and `MARGINALIS_SANDBOX_LAUNCH=attach` drives an IDE already answering on
  `MARGINALIS_ROBOT_PORT` (no fresh project, no restarts). Both run one sandbox at a time.
- **Several checkouts at once** (parallel feature work) queue up: a run takes the machine-wide lock
  `marginalis-sandbox-runs/.lock` next to the checkout and the next one waits, because two runs side by side
  overload the machine and both fail at random. Never create or delete the lock by hand: a one-off experiment with
  a sandbox runs as `py sandbox/sandbox.py locked <command...>`, which waits for the lock, holds it for the command
  and releases only its own. A lock whose owner process is gone (a run killed hard) is taken over by the next run at
  once, and the suite then removes the sandbox containers such a run left behind.

### Measured settings

All 17 scenarios, one checkout, 2026-10-16 on 32 cores and 47 GiB for Docker, run by the Python runner this suite
replaced (its `--jobs` set the sandboxes side by side, as `-Pmarginalis.sandbox.jobs` does here):

| Sandboxes | Wall time | Failures | Peak CPU | Peak memory |
|---|---|---|---|---|
| 1 | about 25 min (sum of the scenarios, Gradle start each) | | | |
| 4 | 4.5 min | 0 | 18 cores | 5.5 GiB |
| **6** (default) | **3.1 min** | 0 | 18 cores | 7.7 GiB |
| 8 | 2.6 min | 0 | 24 cores | 10.6 GiB |
| 10 | 2.6 min | 0 | 26 cores | 13.0 GiB |
| 12 | 4.0 min | 6 (timeouts) | 24 cores | 15.7 GiB |

Eight was the fastest; past it the IDEs starting at the same time saturate the CPU, and at twelve scenarios time
out. Six is the default: under a 5-minute target with half the machine left. A sandbox uses about 2 GiB.

The Kotlin suite was measured at the default 6 sandboxes on 2026-10-16: about 4.2 min, 5 runs in a row green.

## Adding a scenario

1. Write the feature's runbook (`sandbox/runbooks/README.md` has the format); its `Scenario` line names the class.
2. Copy an existing class in `src/uiTest/kotlin/dev/marginalis/plugin/sandbox/scenarios/`, e.g.
   `ApiReplyRerendersTest`. A scenario extends `SandboxScenario("<scenario_name>")`, which starts a fresh sandbox
   on a fresh project for the class and stops it at the end; the name picks its folders under the runtime.
3. Each case is a `@Test` (with an `@Order` when the class has several) whose body is
   `case("<case_name>") { ... }`: GIVEN/WHEN/THEN in
   plain calls to the steps, and `check(name, passed, detail)` per behaviour. A case fails when one of its checks
   does, or when it throws. Cases run in order on one sandbox, so a later case may build on an earlier one's
   threads; `Sandbox.restart(fresh = ...)` gives a case a new project or a persisted state.
4. The agent's side is `Agent`, the user's `SandboxUi`, `ToolWindowUi` and `ReviewDiffUi`, larger thread sets
   `fixtures/Walkthroughs.kt`. Add a missing step there rather than inline JavaScript in the scenario.
5. Every wait for a condition goes through `Waits.kt` (`waitUntil`, `awaitUntil`, `awaitValue`): bounded, and a
   timeout records the diagnostics. Prefer one over a fixed sleep, and never fall back to a weaker read when the
   real one fails: the step fails instead.

### Rules for the steps

- **User steps never touch the OS mouse or keyboard.** The steps press buttons through their own actions and set
  text on the components.
- **Locks on the floor IDE.** The robot runs EDT scripts without the lock a real click holds, and 2025.2 refuses
  model reads and caret moves there. Wrap just that call: `read(function () { ... })` for a read,
  `asUser(function () { ... })` for a caret move. Never wrap a whole script: opening an editor under a lock waits on
  background reads the lock holds back, and the IDE hangs.
- **Actions run as their own EDT event.** `SandboxUi.pressPanelAction` and `ToolWindowUi.pressToolWindowAction`
  schedule the action with `invokeLater`, so it gets a click's locks; later robot calls queue behind it and see its
  outcome.
- **Header buttons** are painted only on hover, so they are pressed through their action. Pass every name a build
  may give the button in one call, `pressToolWindowAction("Collapse Folder", "Collapse All Sections")`: one wait
  for the header, not one per name.
- **Screenshots** show the IDE frame painted inside the IDE (`shot(name)`, `SandboxUi.screenshotFrame`), never the
  desktop.
- **A restart** can bring the tool window back hidden; the steps show it first.
- **The test project lives in the container** (Docker mode). The host folder `<runtime>/<scenario>/project` is the
  copy the harness prepares before a start and reads back after a stop. While the IDE runs:
  - read files the IDE writes (`.idea/workspace.xml`, `.idea/marginalis.json`) with `Sandbox.projectText(path)`;
  - after writing files into the host folder, call `SandboxUi.refreshProjectFiles()`, which copies them in
    (`Sandbox.pushProject()`) and refreshes the IDE.

  Never read the host folder for something the running IDE wrote.
- **A start counts as up once the IDE has settled:** indexing over, startup activities passed, the global workspace
  model reloaded from disk (the platform does it in a background write seconds after start), no write action
  pending. Opening an editor while the platform's own startup writes are queued blocks the EDT for 30 s on a busy
  machine.

## The manual sandbox (demos, hands-on work)

`sandbox/sandbox.py` with `sandbox/sandbox_ui.py` starts one sandbox IDE you can use by hand or drive from a short
script, with the same steps as the suite. It is no test runner. `sandbox/README.md` lists its commands and files.

- **Native Windows, your own IDE version:** `py sandbox/sandbox.py start` runs the sandbox on your desktop against
  a locally installed IntelliJ (`%LOCALAPPDATA%\Programs\IntelliJ IDEA Ultimate`, a newer version than the floor).
  Use it for demos and to try a change in the version you work in. The screen must stay awake: with the monitor
  asleep Windows reports 640x480 and clamps the window until thread panels get no width.
- **Docker:** `MARGINALIS_SANDBOX_DOCKER=1 py sandbox/sandbox.py start` runs it on the floor IDE in a container on
  a virtual screen, as the suite does.
- `py sandbox/sandbox.py status` says whether it answers, `py sandbox/sandbox.py stop` saves its settings and
  stops it.
- An experiment that runs sandboxes next to other work goes under the machine lock:
  `py sandbox/sandbox.py locked <command...>`. The lock ends with the command, so wrap the whole experiment (start,
  work, stop), not just the start.

## Troubleshooting

- A sandbox that never comes up: the suite reports `the sandbox IDE exited before it came up` or a timeout on
  `the sandbox IDE answers on robot port …`; `sandbox.py` reports `the IDE's build exited with … before the IDE came
  up` or `sandbox not up after 600s`. Read the run's `run.log` and the IDE's `idea.log` in the container's volume
  (`/work/runtime/ide/log_runIdeForUiTests/`).
- **Leftover sandboxes** after a crash: `docker ps -a --filter name=marginalis-sandbox` and `docker rm -f` them; a
  leftover takes CPU, memory and ports from the next run. The suite removes its own when it is stopped, and the
  next run removes those a run killed hard left behind.
- **Ports:** the suite claims robot ports 8100 to 8199, `sandbox.py` uses 8082 (`MARGINALIS_ROBOT_PORT`); in Docker
  the REST port is the robot port + 10000. Both stay below Windows' dynamic port range (49152 and up), where
  services take ports at random.
- **Why the project is not a bind mount:** on Windows and macOS a host folder reaches the container through a slow
  file share without file-change events. The IDE froze on file lookups and kept refreshing, and its background
  writes then stalled editor opening for 30 s. On the container's own disk both go away, on every OS and in CI.
- **Gradle in containers:** each container keeps its own Gradle home. A shared one deadlocks, because Gradle hands
  cache locks between processes over loopback, and each container has its own.
