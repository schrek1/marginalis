# Testing Marginalis

How to check a change, for a person or an agent starting on the repository. Three layers, fastest first. A change
is ready for review when the layers it touches pass, and the whole sandbox set passes before it goes into a release.

| Layer | What it covers | Command | Time |
|---|---|---|---|
| Core | model, lifecycle, walks, anchoring, codec (`core/`, pure Kotlin) | `./gradlew :core:test` | seconds |
| Platform (headless) | plugin code against a real in-memory IDE: store, transport, tool window models, actions | `./gradlew test` | about a minute |
| Sandbox scenarios | the real IDE, driven through its Swing components and the agent API | `python sandbox/run_all.py --jobs 6` (Docker) | about 3 min |

Run the platform tests against the build's own IDE (IntelliJ Community 2025.2, the platform floor). Do not pass
`-Pmarginalis.localIde` for them: a local Ultimate ships obfuscated classes and every test fails.

## Sandbox scenarios

A scenario starts a sandbox IDE with the plugin built from the checkout, plays the user's side through the real
components (no OS mouse or keyboard, ever) and the agent's side through the REST API, and prints `PASS` / `FAIL`
per check. Each feature has one scenario in `sandbox/scenarios/` and a runbook in `sandbox/runbooks/`.
`sandbox/README.md` lists the commands and files.

### In Docker (the default)

Docker gives every sandbox a virtual 2560x1440 screen, so scenarios behave the same on Windows, macOS, Linux and in
CI, whatever the monitor does, and nothing appears on your desktop. The IDE inside is the platform floor.

- **Needs:** Docker (Desktop on Windows and macOS, Engine on Linux) and Python 3.12. On Windows give WSL2 room for
  several IDEs in `%USERPROFILE%\.wslconfig`, then `wsl --shutdown` and start Docker again; memory is taken only
  while used:
  ```
  [wsl2]
  memory=48GB

  [experimental]
  autoMemoryReclaim=gradual
  ```
- **First run on a machine** builds the image and downloads the IDE (about 1.5 GB) into Docker volumes: a few
  minutes, once.
- **One scenario while you work:**
  `MARGINALIS_SANDBOX_DOCKER=1 python sandbox/scenarios/<name>.py`
- **The whole set before a PR:**
  `MARGINALIS_SANDBOX_DOCKER=1 python sandbox/run_all.py --jobs 6 --summary gate.md`
  It builds the checkout once, then runs six sandboxes side by side; each takes the next scenario from one queue,
  longest first. Results land in `marginalis-sandbox-runs/<run>/<port>/` next to the checkout (`gate-logs/` per
  scenario, `shots/`), and the summary lists every failing check.
- **Several checkouts at once** (parallel feature work) queue up: a multi-sandbox run takes the machine-wide lock
  `marginalis-sandbox-runs/.lock` and the next one waits, because two runs side by side overload the machine and
  both fail at random. Each run picks free ports and its own folder. Never create or delete the lock by hand: a
  one-off experiment with a sandbox runs as `py sandbox/run_all.py --locked <command...>`, which waits for the lock,
  holds it for the command and releases only its own. A lock whose owner process is gone (a run killed hard) is
  taken over by the next run at once; the Kotlin suite also removes the sandbox containers such a run left behind.

### Measured settings

All 17 scenarios, one checkout, 2026-10-16 on 32 cores and 47 GiB for Docker:

| `--jobs` | Wall time | Failures | Peak CPU | Peak memory |
|---|---|---|---|---|
| 1 | about 25 min (sum of the scenarios, Gradle start each) | | | |
| 4 | 4.5 min | 0 | 18 cores | 5.5 GiB |
| **6** (default) | **3.1 min** | 0 | 18 cores | 7.7 GiB |
| 8 | 2.6 min | 0 | 24 cores | 10.6 GiB |
| 10 | 2.6 min | 0 | 26 cores | 13.0 GiB |
| 12 | 4.0 min | 6 (timeouts) | 24 cores | 15.7 GiB |

Eight is the fastest; past it the IDEs starting at the same time saturate the CPU, and at twelve scenarios time
out. Six is the default: under a 5-minute target with half the machine left, so a second checkout can run its set
at the same time. Alone on the machine, `--jobs 8`. A sandbox uses about 2 GiB.

### Without Docker (Windows, your own IDE version)

`python sandbox/sandbox.py start` runs the sandbox on your desktop against a locally installed IntelliJ
(`%LOCALAPPDATA%\Programs\IntelliJ IDEA Ultimate`, a newer version than the floor). Use it for demos and to try a
change in the version you work in. The screen must stay awake: with the monitor asleep Windows reports 640x480 and
clamps the window until thread panels get no width.

## Writing a scenario

- **User steps never touch the OS mouse or keyboard.** The helpers in `sandbox_ui.py` press buttons through their
  own actions and set text on the components.
- **Locks on the floor IDE.** The robot runs EDT scripts without the lock a real click holds, and 2025.2 refuses
  model reads and caret moves there. Wrap just that call: `read(function () { ... })` for a read,
  `asUser(function () { ... })` for a caret move. Never wrap a whole script: opening an editor under a lock waits on
  background reads the lock holds back, and the IDE hangs.
- **Actions run as their own EDT event.** `press_panel_action` and `press_tool_window_action` schedule the action
  with `invokeLater`, so it gets a click's locks; later robot calls queue behind it and see its outcome.
- **Header buttons** are painted only on hover, so they are pressed through their action. Pass every name a build
  may give the button in one call, `press_tool_window_action("Collapse Folder", "Collapse All Sections")`: one wait
  for the header, not one per name.
- **Screenshots** show the IDE frame painted inside the IDE (`robot.screenshot(..., component)`), never the desktop.
- **A restart** can bring the tool window back hidden; the helpers show it first.
- **The test project lives in the container** (Docker mode). The host folder `marginalis-sandbox-<port>/project` is the
  copy the harness prepares before a start and reads back after a stop. While the IDE runs:
  - read files the IDE writes (`.idea/workspace.xml`, `.idea/marginalis.json`) with `sandbox.project_text(path)`;
  - after writing files into the host folder, call `ui.refresh_project_files()`, which copies them in
    (`sandbox.push_project()`) and refreshes the IDE.

  Never read the host folder for something the running IDE wrote.
- **A start counts as up once the IDE has settled:** indexing over, startup activities passed, the global workspace
  model reloaded from disk (the platform does it in a background write seconds after start), no write action
  pending. Opening an editor while the platform's own startup writes are queued blocks the EDT for 30 s on a busy
  machine.

## Troubleshooting

- `the IDE's build exited with 0 before the IDE came up` or `sandbox not up after 600s`: read the run's `run.log`
  and the IDE's `idea.log` in the container's volume (`/work/runtime/ide/log_runIdeForUiTests/`).
- **Leftover sandboxes** after a crash: `docker ps -a --filter name=marginalis-sandbox` and `docker rm -f` them; a
  leftover takes CPU, memory and ports from the next run. `run_all --jobs` removes its own even when stopped.
- **Ports:** robot 8082 and up, REST robot + 10000. Both stay below Windows' dynamic port range (49152 and up), where
  services take ports at random.
- **Why the project is not a bind mount:** on Windows and macOS a host folder reaches the container through a slow
  file share without file-change events. The IDE froze on file lookups and kept refreshing, and its background
  writes then stalled editor opening for 30 s. On the container's own disk both go away, on every OS and in CI.
- **Gradle in containers:** each container keeps its own Gradle home. A shared one deadlocks, because Gradle hands
  cache locks between processes over loopback, and each container has its own.
