# Runbooks

One file per feature: what it protects, how the sandbox checks it, and what only a person can check. A smoke
run or a full regression can be done from any checkout, at any time. A feature branch brings its runbook and
its scenario with it.

## Format

```
# <what the user gets>

- **Key:** MARG-N (schrek1/marginalis#N)
- **Tags:** smoke, regression
- **Scenario:** `<scenario>.py` [--only …]

## Protects      the behaviours, as the user sees them
## Automated     the command and the checks it runs; every check must PASS
## Manual        numbered steps → expected result, for what a robot cannot see or drive
## On main       what fails without the feature (a scenario that passes on main protects nothing)
```

`run_all.py` reads the `Tags` and `Scenario` lines, so keep each of them on one line.

## Tags

- `regression`: every runbook. The full run is the sandbox half of the stable gate.
- `smoke`: a quick check that a build works at all: the agent loop, the editor, the tool window and the
  Margin tab.

## Running

From the checkout under test (on the laptop `py`, elsewhere `python` 3.12):

```
py sandbox/run_all.py --tag smoke                   # the smoke runbooks' scenarios
py sandbox/run_all.py                               # regression: every scenario
py sandbox/scenarios/<scenario>.py                  # one runbook's automated part
```

`--feature-dir <checkout>` tests another checkout with this one's harness. The manual part runs in the sandbox
IDE (virtual desktop 3, "diy"), or in your own IDE on a dev build.
