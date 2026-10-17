# Runbooks

One file per feature: what it protects, how the sandbox checks it, and what only a person can check. A feature
branch brings its runbook and its scenario with it.

## Format

```
# <what the user gets>

- **Key:** MARG-N (schrek1/marginalis#N)
- **Scenario:** `<KotlinClass>` or `<KotlinClass>.<testMethod>`

## Protects      the behaviours, as the user sees them
## Automated     the command and the checks it runs; every check must PASS
## Manual        numbered steps → expected result, for what a robot cannot see or drive
## On main       what fails without the feature (a scenario that passes on main protects nothing)
```

The scenario is a class in `src/uiTest/kotlin/dev/marginalis/plugin/sandbox/scenarios/`; a runbook that covers
one case of a class names its test method too.

## Tags

A tag lives on the scenario class as JUnit `@Tag` (or on one test method, where a runbook covers one case), never
in the runbook: its `Scenario:` line points at the class, so there is one copy to keep. A new tag comes only with a
command or job that selects by it.

- `core`: the critical flows (MARG-42): the agent ↔ user loop, the user starting a thread, anchoring that follows
  edits, threads surviving an IDE restart. `-Pmarginalis.sandbox.tags=core` runs them alone; CI runs every scenario.

## Running

From the checkout under test:

```
./gradlew uiSandboxTest                                         # every scenario
./gradlew uiSandboxTest -Pmarginalis.sandbox.tags=core          # the critical flows alone
./gradlew uiSandboxTest --tests '*<KotlinClass>'                # one runbook's automated part
./gradlew uiSandboxTest --tests '*<KotlinClass>.<testMethod>'   # one case of a class
```

The manual part runs in a sandbox IDE started with `py sandbox/sandbox.py start` (virtual desktop 3, "diy"), or
in your own IDE on a dev build.
