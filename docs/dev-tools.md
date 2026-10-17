# Tools for working on Marginalis

What a machine needs to build, test and ship Marginalis, for a person or an agent. Commands are for Windows
(`winget`); on Linux or macOS use the package manager's package of the same name. Keep this list current: a tool
the work starts to rely on goes in here with the change that needs it.

| Tool | What for | Install (Windows) |
|---|---|---|
| Git | the repository; Git Bash runs the `.sh` scripts and `./gradlew` | `winget install Git.Git` |
| JDK 25 | the Gradle toolchain (the build emits Java 21 bytecode) | `winget install Amazon.Corretto.25.JDK` |
| JDK 21 | `-Pmarginalis.localIde` builds against a local IntelliJ | `winget install Amazon.Corretto.21.JDK` |
| Docker Desktop | the sandbox scenarios (`./gradlew uiSandboxTest`) and the manual sandbox | `winget install Docker.DockerDesktop` |
| Python 3.12 | `sandbox/sandbox.py` (manual sandbox, demos) and `scripts/stable_channel.py` | `winget install Python.Python.3.12` |
| GitHub CLI (`gh`) | pull requests, their CI checks, issues, workflow runs | `winget install GitHub.cli`, then `gh auth login` (HTTPS, browser) |
| actionlint | checks `.github/workflows/*.yml` before a push; a tag-triggered workflow cannot run before it is merged | `winget install rhysd.actionlint` |
| ShellCheck | actionlint uses it for the workflows' `run:` scripts; also checks `scripts/*.sh` | `winget install koalaman.shellcheck` |
| IntelliJ IDEA (optional) | a local IDE for `-Pmarginalis.localIde` and for hands-on review of dev builds | JetBrains Toolbox |

After `winget` installs, open a new terminal (or restart the app hosting it) so the new `PATH` applies.

Checks before pushing a workflow change:

```bash
actionlint .github/workflows/*.yml
```
