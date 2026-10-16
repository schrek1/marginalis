# Runbooks

One file per feature: what it protects, how the sandbox checks it, and what only a person can check. A feature
branch brings its runbook and its scenario with it.

## Format

```
# <what the user gets>

- **Key:** MARG-N (schrek1/marginalis#N)
- **Tags:** smoke, regression
- **Scenario:** `<KotlinClass>` or `<KotlinClass>.<testMethod>`

## Protects      the behaviours, as the user sees them
## Automated     the command and the checks it runs; every check must PASS
## Manual        numbered steps → expected result, for what a robot cannot see or drive
## On main       what fails without the feature (a scenario that passes on main protects nothing)
```

The scenario is a class in `src/uiTest/kotlin/dev/marginalis/plugin/sandbox/scenarios/`; a runbook that covers
one case of a class names its test method too.

## Tags

The tags describe a runbook; no command selects scenarios by them.

- `regression`: every runbook. The full run is the sandbox half of the stable gate.
- `smoke`: a quick check that a build works at all: the agent loop, the editor, the tool window and the
  Margin tab.

## Running

From the checkout under test:

```
./gradlew uiSandboxTest                                         # every scenario
./gradlew uiSandboxTest --tests '*<KotlinClass>'                # one runbook's automated part
./gradlew uiSandboxTest --tests '*<KotlinClass>.<testMethod>'   # one case of a class
```

The manual part runs in a sandbox IDE started with `py sandbox/sandbox.py start` (virtual desktop 3, "diy"), or
in your own IDE on a dev build.
